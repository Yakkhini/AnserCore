/* 全局写回通路。
 *
 * ExeUnit 的结果在这里按物理写回口仲裁：
 *   - 固定延迟结果（含 FEX 的 F2I 整数结果）必须由 data0 预约/检查保证唯一 winner，冲突时 assert；
 *   - 固定结果使用 IntWB0/1/2；独立 DIV/CSR/FENCE 完成接口使用 IntWB3；
 *   - 不确定结果通过 fromExuReady 反压，在 EXU 内保持；
 *   - 被 flush 的结果直接丢弃；
 *   - 仲裁后的 int/fp 写口同时作为 PRF 写数据、Scheduler 的 WB 唤醒源和
 *     Dispatch 的 wbPregs 来源；RegCache 复用 int 写口的数据；
 *   - 每个 EXU 和单不定长完成口各有一份 ROB 写回记录。
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.issue.{AnsIssueParams, AnsIssueUtils}
import ansercore.backend.rename.AnsRenameRedirect
import ansercore.backend.rob.AnsRobWriteback

class AnsWbDataPathIO(val params: BundleParams = BundleParams()) extends Bundle {
  val flush = Flipped(Valid(new AnsRenameRedirect(params)))

  val fromExu = Vec(AnsIssueParams.nExu, Flipped(Valid(new ExuOutput(params, ExecutionUnitParams()))))

  /** 结果是否被接收；只有不确定结果允许保持，固定结果拒绝触发断言。 */
  val fromExuReady  = Vec(AnsIssueParams.nExu, Output(Bool()))
  val fromUncertain = Flipped(Decoupled(new ExuOutput(params, ExecutionUnitParams())))

  /** LSU load 结果预留接口；当前由 Backend tie-off。 */
  val fromLsu = Vec(2, Flipped(Valid(new AnsLsuResult(params))))

  val toIntRf = Vec(AnsIssueParams.nIntWb, Output(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))
  val toFpRf  = Vec(AnsIssueParams.nFpWb, Output(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))

  /** RegCache 写口：与 int PRF 写口同源，由 DataPath 内部接到 RegCache。 */
  val rcWrite = Vec(AnsIssueParams.nIntWb, Output(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))

  /** 原 EXU 索引保持不变，末尾为单不定长完成口；ROB 按 robIdx 过滤。 */
  val toRob = Vec(AnsIssueParams.nCompletion, Output(Valid(new AnsRobWriteback(params))))
}

class AnsWbDataPath(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsWbDataPathIO(params))

  private val nExu      = AnsIssueParams.nExu
  private val nIntWb    = AnsIssueParams.nIntWb
  private val nFpWb     = AnsIssueParams.nFpWb
  private val stdExuIdx = AnsIssueParams.exuIdxOf("STD0")

  private val flushed = VecInit((0 until nExu).map { e =>
    AnsIssueUtils.needFlush(io.fromExu(e).bits.robIdx, io.flush)
  })
  private val valid   = VecInit((0 until nExu).map(e => io.fromExu(e).valid && !flushed(e)))

  private val intEn = VecInit((0 until nExu).map { e =>
    val exu = AnsIssueParams.exuByIndex(e)
    valid(e) && exu.writesInt.B && io.fromExu(e).bits.intWen
  })
  private val fpEn  = VecInit((0 until nExu).map { e =>
    val exu = AnsIssueParams.exuByIndex(e)
    valid(e) && exu.writesFp.B && io.fromExu(e).bits.fpWen
  })
  private val held  = VecInit((0 until nExu).map { e =>
    val exu = AnsIssueParams.exuByIndex(e)
    (exu.hasUncertainFu && !exu.splitUncertain).B
  })

  // IntWB4/5 完整留给 LSU。原 EXU 整数出口只承载固定结果。
  private val allIntWbExus = AnsIssueParams.allExus
    .filter(_.writesInt)
    .sortBy(e => (e.intWbPriority, e.exuIdx))

  private val intGrant = Wire(Vec(nExu, Bool()))
  private val fpGrant  = Wire(Vec(nExu, Bool()))
  intGrant.foreach(_ := true.B)
  fpGrant.foreach(_ := true.B)

  def connectWritePorts(
    port:    Int,
    members: Seq[ansercore.backend.issue.AnsExuParams],
    isInt:   Boolean,
  ): Unit = {
    val enabled  = VecInit(members.map(e => if (isInt) intEn(e.exuIdx) else fpEn(e.exuIdx)))
    // 仅 FP 写口使用本函数（FEX0/FEX1/FEX2 各占一个 FpWB）；若同口有 fixed 与
    // held，fixed 优先。整数固定/不定长结果使用独立静态写口。
    val fixedReq = VecInit(members.indices.map { i =>
      enabled(i) && !held(members(i).exuIdx)
    })
    val heldReq  = VecInit(members.indices.map { i =>
      enabled(i) && held(members(i).exuIdx)
    })
    val fixedOH  = PriorityEncoderOH(fixedReq.asUInt)
    val heldOH   = PriorityEncoderOH(heldReq.asUInt)
    val winnerOH = Mux(fixedReq.asUInt.orR, fixedOH, heldOH)
    val addr     = Mux1H(winnerOH, members.map(exu => io.fromExu(exu.exuIdx).bits.pdest))
    val data     = Mux1H(winnerOH, members.map(exu => io.fromExu(exu.exuIdx).bits.data(0)))
    val sink     = if (isInt) io.toIntRf(port) else io.toFpRf(port)

    sink.wen  := enabled.asUInt.orR
    sink.addr := addr
    sink.data := data

    if (isInt) {
      io.rcWrite(port).wen  := sink.wen
      io.rcWrite(port).addr := sink.addr
      io.rcWrite(port).data := sink.data
    }

    members.zipWithIndex.foreach { case (exu, i) =>
      val grant = !enabled(i) || winnerOH(i)
      if (isInt) intGrant(exu.exuIdx) := grant
      else fpGrant(exu.exuIdx)        := grant
    }
  }

  def connectIntWritePorts(port: Int): Unit = {
    val targetPorts = VecInit(allIntWbExus.map(_.intWbPort.U))
    val requests    = VecInit(allIntWbExus.indices.map { i =>
      intEn(allIntWbExus(i).exuIdx) && targetPorts(i) === port.U
    })
    val winnerOH    = PriorityEncoderOH(requests.asUInt)
    val addr        = Mux1H(winnerOH, allIntWbExus.map(e => io.fromExu(e.exuIdx).bits.pdest))
    val data        = Mux1H(winnerOH, allIntWbExus.map(e => io.fromExu(e.exuIdx).bits.data(0)))
    val sink        = io.toIntRf(port)

    sink.wen         := requests.asUInt.orR
    sink.addr        := addr
    sink.data        := data
    io.rcWrite(port) := sink

    allIntWbExus.indices.foreach { i =>
      val e = allIntWbExus(i)
      when(intEn(e.exuIdx) && targetPorts(i) === port.U) {
        intGrant(e.exuIdx) := winnerOH(i)
      }
    }
  }

  for (p <- 0 until nIntWb) {
    connectIntWritePorts(p)
  }

  for (p <- 0 until nFpWb) {
    AnsDataPathParams.fpWbGroups.get(p) match {
      case Some(members) => connectWritePorts(p, members, isInt = false)
      case None          => io.toFpRf(p) := 0.U.asTypeOf(io.toFpRf(p))
    }
  }

  // ---- 接受语义与 ROB 写回 ----
  for (e <- 0 until nExu) {
    val out   = io.fromExu(e).bits
    // 只有不确定结果需要保持；FEX 的 F2I 整数结果按固定槽写回。
    val intOk = !intEn(e) || intGrant(e)
    val fpOk  = !fpEn(e) || fpGrant(e)

    io.fromExuReady(e) := Mux(held(e), intOk && fpOk, true.B)

    // 固定结果必须由 data0 预约保证唯一 winner；否则结果会永久丢失。
    when(valid(e) && !held(e)) {
      when(intEn(e))(assert(intGrant(e), s"exu $e lost int write port $nIntWb"))
      when(fpEn(e))(assert(fpGrant(e), s"exu $e lost fp write port $nFpWb"))
    }

    val accepted = valid(e) && io.fromExuReady(e)
    val rob      = io.toRob(e)
    rob.valid              := accepted
    rob.bits.robIdx        := out.robIdx
    rob.bits.exceptionBits := out.exceptionBits
    rob.bits.replayInst    := out.replayInst
    rob.bits.flushPipe     := out.hasException || out.commitSelfFlush
    rob.bits.stdWriteback  := accepted && (e == stdExuIdx).B
    rob.bits.writeFflags   := out.writeFpFlags
    rob.bits.fflags        := out.fpFlags
  }
  // IntWB3 belongs exclusively to the merged uncertain completion channel. A
  // non-writing operation still handshakes and completes at ROB exactly once.
  io.fromUncertain.ready := true.B
  val u        = io.fromUncertain.bits
  val accepted = io.fromUncertain.fire && !AnsIssueUtils.needFlush(u.robIdx, io.flush)
  when(accepted) {
    assert(!u.fpWen, "uncertain scalar completion cannot write the FP register file")
  }
  val port     = io.toIntRf(AnsIssueParams.uncertainIntWbPort)
  port.wen                                      := accepted && u.intWen
  port.addr                                     := u.pdest
  port.data                                     := u.data(0)
  io.rcWrite(AnsIssueParams.uncertainIntWbPort) := port
  val completion = io.toRob(AnsIssueParams.uncertainCompletionIdx)
  completion.valid              := accepted
  completion.bits.robIdx        := u.robIdx
  completion.bits.exceptionBits := u.exceptionBits
  completion.bits.replayInst    := u.replayInst
  completion.bits.flushPipe     := u.hasException || u.commitSelfFlush
  completion.bits.stdWriteback  := false.B
  completion.bits.writeFflags   := u.writeFpFlags
  completion.bits.fflags        := u.fpFlags

}
