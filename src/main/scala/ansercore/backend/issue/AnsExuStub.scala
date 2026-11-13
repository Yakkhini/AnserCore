/* EXU / WbDataPath 占位实现（仅用于 bring-up）。
 *
 * 真正的 ExuBlock 与 WbDataPath 按 EXU_FU 文档单独实现；这里只提供：
 *   - 固定延迟流水占位：0 拍组合输出，N 拍移位输出；
 *   - 非确定延迟 EXU（div/csr/fence/fdiv/ldu/stu/mou）用 busy 位阻塞下一条；
 *   - 简易写回仲裁，把结果送到 PRF 写口。
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
      val flushed = if (i == 0) false.B else AnsIssueUtils.needFlush(bits(i).robIdx, io.flush)
      valids(i + 1) := (if (i == 0) valids(i) else valids(i) && !flushed)
      when(valids(i)) {
        bits(i + 1) := bits(i)
      }
    }

    io.out.valid := valids.last
    io.out.bits  := bits.last
  }
}

class AnsExuBlockStubIO(val params: BundleParams = BundleParams()) extends Bundle {
  val flush = Input(Valid(new AnsRenameRedirect(params)))
  val in    =
    Vec(AnsIssueParams.nExu, Flipped(Decoupled(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc)))))
  val out   = Vec(AnsIssueParams.nExu, Valid(new ExuOutput(params, ExecutionUnitParams())))
}

class AnsExuBlockStub(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsExuBlockStubIO(params))

  for (e <- 0 until AnsIssueParams.nExu) {
    val exu = AnsIssueParams.exuByIndex(e)

    val fuLatency: Map[Int, Int] = exu.fuConfigs.map { cfg =>
      cfg.fuType -> cfg.latency.value.getOrElse(1)
    }.toMap
    val latSet                   = fuLatency.values.toSet

    val latSel = fuLatency.toSeq.map { case (fu, lat) => (io.in(e).bits.fuType === fu.U, lat.U) }
    val lat    = Mux1H(latSel)

    val pipes = latSet.toSeq.sorted.map(d => d -> Module(new AnsExuDelayPipe(d, io.in(e).bits.cloneType, params)))
    pipes.foreach { case (d, pipe) =>
      pipe.io.flush     := io.flush
      pipe.io.enq.valid := io.in(e).valid && io.in(e).ready && lat === d.U
      pipe.io.enq.bits  := io.in(e).bits
    }

    // 非确定延迟 EXU 用一个周期的 busy 位阻塞下一条指令。
    val uncertainFuTypes = exu.fuConfigs.filter(_.latency.value.isEmpty).map(_.fuType)
    val isUncertain      =
      if (uncertainFuTypes.nonEmpty)
        uncertainFuTypes.map(t => io.in(e).bits.fuType === t.U).reduce(_ || _)
      else false.B
    val busy             = RegInit(false.B)
    when(io.in(e).valid && io.in(e).ready && isUncertain) {
      busy := true.B
    }.otherwise {
      busy := false.B
    }
    if (uncertainFuTypes.nonEmpty) {
      io.in(e).ready := !busy
    } else {
      io.in(e).ready := true.B
    }

    val pipeValid = VecInit(pipes.map(_._2.io.out.valid))
    val pipeBits  = VecInit(pipes.map(_._2.io.out.bits))
    val uop       = WireInit(0.U.asTypeOf(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc))))
    uop := Mux1H(pipeValid, pipeBits)

    val out = WireInit(0.U.asTypeOf(new ExuOutput(params, ExecutionUnitParams())))
    out.data(0) := uop.src(0) + uop.src(1)
    out.pdest   := uop.pdest
    out.robIdx  := uop.robIdx
    out.intWen  := uop.intWen
    out.fpWen   := uop.fpWen
    out.ftqPtr  := uop.ftqPtr

    io.out(e).valid := pipeValid.asUInt.orR
    io.out(e).bits  := out
  }
}

class AnsWbDataPathStubIO(val params: BundleParams = BundleParams()) extends Bundle {
  val fromExu      = Vec(AnsIssueParams.nExu, Input(Valid(new ExuOutput(params, ExecutionUnitParams()))))
  val intWriteBack = Vec(AnsIssueParams.nIntWb, Output(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))
  val fpWriteBack  = Vec(AnsIssueParams.nFpWb, Output(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))
}

class AnsWbDataPathStub(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsWbDataPathStubIO(params))

  for (p <- 0 until AnsIssueParams.nIntWb) {
    val members = AnsIssueParams.allExus.filter(_.intWbPort == p)
    if (members.nonEmpty) {
      val cand = members.map(e => io.fromExu(e.exuIdx).valid && io.fromExu(e.exuIdx).bits.intWen)
      when(PopCount(VecInit(cand)) > 1.U) {
        assert(false.B, s"int write port $p conflict")
      }
      io.intWriteBack(p).wen  := VecInit(cand).asUInt.orR
      io.intWriteBack(p).addr := Mux1H(cand, members.map(e => io.fromExu(e.exuIdx).bits.pdest))
      io.intWriteBack(p).data := Mux1H(cand, members.map(e => io.fromExu(e.exuIdx).bits.data(0)))
    } else {
      io.intWriteBack(p) := 0.U.asTypeOf(io.intWriteBack(p))
    }
  }

  for (p <- 0 until AnsIssueParams.nFpWb) {
    val members = AnsIssueParams.allExus.filter(_.fpWbPort == p)
    if (members.nonEmpty) {
      val cand = members.map(e => io.fromExu(e.exuIdx).valid && io.fromExu(e.exuIdx).bits.fpWen)
      when(PopCount(VecInit(cand)) > 1.U) {
        assert(false.B, s"fp write port $p conflict")
      }
      io.fpWriteBack(p).wen  := VecInit(cand).asUInt.orR
      io.fpWriteBack(p).addr := Mux1H(cand, members.map(e => io.fromExu(e.exuIdx).bits.pdest))
      io.fpWriteBack(p).data := Mux1H(cand, members.map(e => io.fromExu(e.exuIdx).bits.data(0)))
    } else {
      io.fpWriteBack(p) := 0.U.asTypeOf(io.fpWriteBack(p))
    }
  }
}
