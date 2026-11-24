/* EXU 占位实现（仅用于 bring-up）。
 *
 * 真正的 ExuBlock 按 EXU_FU 文档单独实现；这里只提供：
 *   - 固定延迟流水占位：0 拍组合输出，N 拍移位输出；
 *   - 不确定延迟操作使用独立的保持槽，结果保持到 outReady；
 *   - MUL0 的固定 MUL/BKU 与 DIV/CSR/FENCE 可以同时在途；
 *   - FEX0/FEX2 的比较/分类及 F2I 整数结果按固定时刻输出，不进入保持槽。
 *
 * 写回仲裁已迁到 `ansercore.backend.datapath.AnsWbDataPath`。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.AnsRenameRedirect

class AnsExuDelayPipe(val depth: Int, gen: ExuInput, val params: BundleParams = BundleParams()) extends Module {
  require(depth >= 0)
  val io = IO(new Bundle {
    val flush = Input(Valid(new AnsRenameRedirect(params)))
    val enq   = Input(Valid(gen.cloneType))
    val out   = Output(Valid(gen.cloneType))
  })

  if (depth == 0) {
    io.out := io.enq
  } else {
    val valids = io.enq.valid +: Seq.fill(depth)(RegInit(false.B))
    val bits   = io.enq.bits +: Seq.fill(depth)(Reg(gen.cloneType))

    for (i <- 0 until depth) {
      val flushed = AnsIssueUtils.needFlush(bits(i).robIdx, io.flush)
      valids(i + 1) := valids(i) && !flushed
      when(valids(i)) {
        bits(i + 1) := bits(i)
      }
    }

    io.out.valid := valids.last && !AnsIssueUtils.needFlush(bits.last.robIdx, io.flush)
    io.out.bits  := bits.last
  }
}

class AnsExuBlockStubIO(val params: BundleParams = BundleParams()) extends Bundle {
  val flush = Input(Valid(new AnsRenameRedirect(params)))
  val in    =
    Vec(AnsIssueParams.nExu, Flipped(Decoupled(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc)))))
  val out   = Vec(AnsIssueParams.nExu, Valid(new ExuOutput(params, ExecutionUnitParams())))

  /** WbDataPath 是否接收本拍结果；不确定结果在 outReady=0 时保持。 */
  val outReady = Vec(AnsIssueParams.nExu, Input(Bool()))

  val uncertainBusy = Vec(AnsIssueParams.nExu, Output(UInt(FuType.width.W)))
  val uncertainIn   = Vec(
    AnsIssueParams.nUncertainGroups,
    Flipped(Decoupled(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc)))),
  )
  val uncertainOut  = Decoupled(new ExuOutput(params, ExecutionUnitParams()))

}

class AnsExuBlockStub(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsExuBlockStubIO(params))

  def makeOut(uop: ExuInput): ExuOutput = {
    val out = WireInit(0.U.asTypeOf(new ExuOutput(params, ExecutionUnitParams())))
    out.data(0)         := uop.src(0) + uop.src(1)
    out.pdest           := uop.pdest
    out.robIdx          := uop.robIdx
    out.fuType          := uop.fuType
    out.intWen          := uop.intWen
    out.fpWen           := uop.fpWen
    out.ftqPtr          := uop.ftqPtr
    out.commitSelfFlush := uop.commitSelfFlush
    out.lqIdx           := uop.lqIdx
    out.sqIdx           := uop.sqIdx
    out
  }

  // Stub computes on acceptance, then retains the complete result until completion.
  def heldResult(in: DecoupledIO[ExuInput], out: DecoupledIO[ExuOutput]): Bool = {
    val valid   = RegInit(false.B)
    val result  = Reg(chiselTypeOf(out.bits))
    val flushed = AnsIssueUtils.needFlush(result.robIdx, io.flush)
    in.ready  := !valid || flushed
    out.valid := valid && !flushed
    out.bits  := result
    when(in.fire && !AnsIssueUtils.needFlush(in.bits.robIdx, io.flush)) {
      valid  := true.B
      result := makeOut(in.bits)
    }.elsewhen(flushed || out.fire) {
      valid := false.B
    }
    valid && !flushed
  }
  val arb                                                                      = Module(new AnsUncertainCompletionArbiter(params))
  arb.io.flush    := io.flush
  io.uncertainOut <> arb.io.out
  val splitBusy = io.uncertainIn.zipWithIndex
    .map { case (in, i) =>
      val busy = heldResult(in, arb.io.in(i))
      Mux(busy, AnsIssueParams.uncertainOwner.uncertainGroups(i).reduce(_ | _).U(FuType.width.W), 0.U(FuType.width.W))
    }
    .reduce(_ | _)

  for (e <- 0 until AnsIssueParams.nExu) {
    val exu                            = AnsIssueParams.exuByIndex(e)
    // ---- 固定延迟流水 ----
    val fixedLatencyMap: Map[Int, Int] = exu.fuConfigs.flatMap { cfg =>
      cfg.latency.value.map(lat => cfg.fuType -> lat)
    }.toMap
    val fixedLatSet                    = fixedLatencyMap.values.toSet

    val latSel = fixedLatencyMap.toSeq.map { case (fu, lat) => (io.in(e).bits.fuType === fu.U, lat.U) }
    val lat    = if (latSel.nonEmpty) Mux1H(latSel) else 0.U

    val fixedPipes = fixedLatSet.toSeq.sorted.map { d =>
      d -> Module(new AnsExuDelayPipe(d, io.in(e).bits.cloneType, params))
    }
    fixedPipes.foreach { case (d, pipe) =>
      pipe.io.flush     := io.flush
      pipe.io.enq.valid := io.in(e).fire && exu.isFixedFu(io.in(e).bits.fuType) && lat === d.U &&
        !AnsIssueUtils.needFlush(io.in(e).bits.robIdx, io.flush)
      pipe.io.enq.bits  := io.in(e).bits
    }

    val fixedValid =
      if (fixedPipes.isEmpty) false.B
      else VecInit(fixedPipes.map(_._2.io.out.valid)).asUInt.orR
    val fixedUop   =
      if (fixedPipes.isEmpty) 0.U.asTypeOf(io.in(e).bits)
      else Mux1H(fixedPipes.map(_._2.io.out.valid), fixedPipes.map(_._2.io.out.bits))
    val fixedOut   = makeOut(fixedUop)

    io.in(e).ready      := true.B
    io.out(e).valid     := fixedValid
    io.out(e).bits      := fixedOut
    io.uncertainBusy(e) := 0.U
    if (exu.splitUncertain) {
      io.uncertainBusy(e) := splitBusy
      when(io.in(e).valid)(assert(exu.isFixedFu(io.in(e).bits.fuType)))
    } else if (exu.hasUncertainFu) {
      require(!exu.hasCertainFu)
      val result = Wire(Decoupled(new ExuOutput(params, ExecutionUnitParams())))
      val busy   = heldResult(io.in(e), result)
      result.ready        := io.outReady(e)
      io.out(e).valid     := result.valid
      io.out(e).bits      := result.bits
      io.uncertainBusy(e) := Mux(busy, exu.uncertainFuMask.U(FuType.width.W), 0.U)
    }

    // 固定结果必须按固定槽当拍接收；FEX 的整数结果也不再走保持槽。
    when(fixedValid) {
      assert(io.outReady(e), s"exu $e fixed result not accepted")
    }
    if (fixedPipes.nonEmpty) {
      when(PopCount(VecInit(fixedPipes.map(_._2.io.out.valid))) > 1.U) {
        assert(false.B, s"exu $e multiple fixed results in one cycle")
      }
    }

  }
}
