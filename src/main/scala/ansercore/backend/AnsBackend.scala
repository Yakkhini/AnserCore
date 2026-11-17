/* AnserCore backend top level，对应设计文档 §4.1 的 Backend 子图。
 *
 * Backend 是后端的组合根：CtrlBlock（Decode -> T1 -> Rename -> Dispatch）以及未来的
 * CSR / ROB / LSQ / IQ / Scheduler / DataPath / MemBlock 都在其内部连接。对外的端口只有
 * 前端接口（指令输入、stall/redirect）和未来的访存接口；ROB 提交、CSR 状态、调度反馈等
 * 都属于后端内部总线，不再出现在 AnsBackendIO 中。
 *
 * 当前 CtrlBlock（内含 Dispatch）已存在；CSR/ROB 用 `AnsCsrStub`/`AnsRobStub` 占位，
 * IQ/LSQ/Scheduler 反馈暂用内部常量驱动。
 */
package ansercore.backend

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.dispatch._
import ansercore.backend.exu.csr.AnsCsrStub
import ansercore.backend.issue._
import ansercore.backend.rename._
import ansercore.backend.rob.AnsRobStub

/** Backend 调试镜像；只用于 bring-up，不参与功能握手。 */
class AnsBackendDebugIO(override val params: BundleParams = BundleParams()) extends AnsCtrlBlockDebugIO(params) {

  /** Internal CSR trap bus; mirrored for bring-up. */
  val trapInstInfo = Output(Valid(new TrapInstInfo(params)))

  /** Dispatch -> IQ 的调试镜像；真实 IQ 实现后由 IQ 消费 ready。 */
  val iqEnq = Output(Vec(AnsDispatchParams.IQEnqSum, Valid(new DynInst(params))))
}

/** Backend 对外端口：前端接口（访存接口待 LSU/数据缓存实现后再加）。 */
class AnsBackendIO(val params: BundleParams = BundleParams()) extends Bundle {
  // Frontend -> backend.
  val in = Vec(params.renameWidth, Flipped(Decoupled(new StaticInst(params))))

  // Backend -> frontend.
  val stallReason = Output(new AnsRenameStallReason)
  // TODO: branch/ROB 落地后在 Backend 输出 frontendRedirect（target PC / FTQ 指针）。

  val debug = Output(new AnsBackendDebugIO(params))
}

class AnsBackend(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsBackendIO(params))

  val ctrlBlock = Module(new AnsCtrlBlock(params))

  // ---- frontend interface ----
  for (lane <- 0 until params.renameWidth) {
    ctrlBlock.io.in(lane).valid := io.in(lane).valid
    ctrlBlock.io.in(lane).bits  := io.in(lane).bits
    io.in(lane).ready           := ctrlBlock.io.in(lane).ready
  }
  io.stallReason := ctrlBlock.io.stallReason

  // ---- internal backend modules ----
  val csr = Module(new AnsCsrStub(params))
  val rob = Module(new AnsRobStub(params))

  ctrlBlock.io.fromCsr       := csr.io.decodeState
  ctrlBlock.io.disableFusion := csr.io.disableFusion
  ctrlBlock.io.singleStep    := csr.io.singleStep
  csr.io.trapInstInfo        := ctrlBlock.io.trapInstInfo

  ctrlBlock.io.redirect    := rob.io.redirect
  ctrlBlock.io.rabCommits  := rob.io.rabCommits
  ctrlBlock.io.snpt        := rob.io.snpt
  ctrlBlock.io.snptLastEnq := rob.io.snptLastEnq
  ctrlBlock.io.snptIsFull  := rob.io.snptIsFull

  rob.io.enq.needAlloc                     := ctrlBlock.io.enqRob.needAlloc
  rob.io.enq.req                           := ctrlBlock.io.enqRob.req
  ctrlBlock.io.enqRob.canAccept            := rob.io.enq.canAccept
  ctrlBlock.io.enqRob.canAcceptForDispatch := rob.io.enq.canAcceptForDispatch
  ctrlBlock.io.enqRob.isEmpty              := rob.io.enq.isEmpty
  ctrlBlock.io.enqRob.resp                 := rob.io.enq.resp

  // ---- Issue / DataPath ----
  val intSchd  = Module(AnsScheduler(params, AnsIssueParams.intScheduler))
  val fpSchd   = Module(AnsScheduler(params, AnsIssueParams.fpScheduler))
  val memSchd  = Module(AnsScheduler(params, AnsIssueParams.memScheduler))
  val dataPath = Module(new AnsDataPath(params))
  val exuBlock = Module(new AnsExuBlockStub(params))
  val wbDp     = Module(new AnsWbDataPathStub(params))

  val schedulers = Seq(
    intSchd -> AnsIssueParams.intScheduler,
    fpSchd  -> AnsIssueParams.fpScheduler,
    memSchd -> AnsIssueParams.memScheduler,
  )

  val issueFlush = ctrlBlock.io.redirect
  for ((schd, _) <- schedulers) {
    schd.io.flush    := issueFlush
    schd.io.ldCancel := 0.U.asTypeOf(schd.io.ldCancel)
  }
  dataPath.io.flush := issueFlush
  exuBlock.io.flush := issueFlush

  // ---- Dispatch -> Scheduler ----
  private var dpPort = 0
  for {
    (schd, sp) <- schedulers
    p          <- 0 until sp.dispatchEnqPorts
  } {
    schd.io.fromDispatch(p).valid            := ctrlBlock.io.toIssueQueues(dpPort).valid
    schd.io.fromDispatch(p).bits             := ctrlBlock.io.toIssueQueues(dpPort).bits
    ctrlBlock.io.toIssueQueues(dpPort).ready := schd.io.fromDispatch(p).ready
    dpPort += 1
  }
  require(dpPort == AnsDispatchParams.IQEnqSum, "dispatch port accounting mismatch")

  // ---- 各出队口有效条目数（按全局 exuIdx） ----
  ctrlBlock.io.IQValidNumVec := 0.U.asTypeOf(ctrlBlock.io.IQValidNumVec)
  private var iqExuPort = 0
  for {
    (schd, sp) <- schedulers
    iq         <- sp.iqs
    exu        <- iq.exus
  } {
    ctrlBlock.io.IQValidNumVec(exu.exuIdx) := schd.io.IQValidNumVec(exu.exuIdx)
    iqExuPort += 1
  }
  require(iqExuPort == AnsIssueParams.nExu, "IQ valid-count exu accounting mismatch")

  // ---- IQ 唤醒总线：全局合流后统一打一拍，避免组合环 ----
  val wakeupRaw = Wire(Vec(AnsIssueParams.nExu, Valid(new AnsIqWakeUp(params))))
  for (e <- 0 until AnsIssueParams.nExu) {
    val cands = schedulers.map(_._1.io.wakeupOut(e))
    wakeupRaw(e).valid := VecInit(cands.map(_.valid)).asUInt.orR
    wakeupRaw(e).bits  := Mux(cands(0).valid, cands(0).bits, Mux(cands(1).valid, cands(1).bits, cands(2).bits))
  }
  val wakeupReg = Reg(Vec(AnsIssueParams.nExu, Valid(new AnsIqWakeUp(params))))
  for (e <- 0 until AnsIssueParams.nExu) {
    wakeupReg(e).valid := wakeupRaw(e).valid && !AnsIssueUtils.needFlush(wakeupRaw(e).bits.robIdx, issueFlush)
    wakeupReg(e).bits  := wakeupRaw(e).bits
  }
  for ((schd, _) <- schedulers) {
    schd.io.wakeupIn := wakeupReg
  }

  // ---- 唤醒 / 写回 -> Dispatch ----
  val dispatchWakeup = Wire(new AnsIQWakeUpVec(params))
  dispatchWakeup         := 0.U.asTypeOf(dispatchWakeup)
  for ((exuIdx, k) <- AnsIssueParams.intWakeUpExuIdx.zipWithIndex) {
    val w = wakeupReg(exuIdx)
    dispatchWakeup.wakeUpInt(k).valid       := w.valid
    dispatchWakeup.wakeUpInt(k).bits.rfWen  := w.bits.intWen
    dispatchWakeup.wakeUpInt(k).bits.fpWen  := w.bits.fpWen
    dispatchWakeup.wakeUpInt(k).bits.pdest  := w.bits.pdest
    dispatchWakeup.wakeUpInt(k).bits.rcDest := w.bits.rcDest
    dispatchWakeup.wakeUpInt(k).bits.is0Lat := w.bits.is0Lat
  }
  for ((exuIdx, k) <- AnsIssueParams.fpWakeUpExuIdx.zipWithIndex) {
    val w = wakeupReg(exuIdx)
    dispatchWakeup.wakeUpFp(k).valid       := w.valid
    dispatchWakeup.wakeUpFp(k).bits.rfWen  := w.bits.intWen
    dispatchWakeup.wakeUpFp(k).bits.fpWen  := w.bits.fpWen
    dispatchWakeup.wakeUpFp(k).bits.pdest  := w.bits.pdest
    dispatchWakeup.wakeUpFp(k).bits.rcDest := w.bits.rcDest
    dispatchWakeup.wakeUpFp(k).bits.is0Lat := w.bits.is0Lat
  }
  ctrlBlock.io.wakeUpAll := dispatchWakeup

  for ((schd, _) <- schedulers) {
    schd.io.intWriteBack := wbDp.io.intWriteBack
    schd.io.fpWriteBack  := wbDp.io.fpWriteBack
  }

  for (k <- 0 until AnsDispatchParams.nIntWb) {
    ctrlBlock.io.wbPregsInt(k).valid := wbDp.io.intWriteBack(k).wen
    ctrlBlock.io.wbPregsInt(k).bits  := wbDp.io.intWriteBack(k).addr
  }
  for (k <- 0 until AnsDispatchParams.nFpWb) {
    ctrlBlock.io.wbPregsFp(k).valid := wbDp.io.fpWriteBack(k).wen
    ctrlBlock.io.wbPregsFp(k).bits  := wbDp.io.fpWriteBack(k).addr
  }

  ctrlBlock.io.data0CancelToDispatch := dataPath.io.toIq.data0CancelToDispatch
  ctrlBlock.io.ldCancel              := 0.U.asTypeOf(ctrlBlock.io.ldCancel)

  // ---- Scheduler <-> DataPath ----
  for ((schd, sp) <- schedulers) {
    schd.io.fromDataPath    := dataPath.io.toIq
    schd.io.wbBusyTableRead := 0.U.asTypeOf(schd.io.wbBusyTableRead)
    sp.deqExuIdx.zipWithIndex.foreach { case (e, k) =>
      dataPath.io.fromIQ(e).valid     := schd.io.deq(k).valid
      dataPath.io.fromIQ(e).bits      := schd.io.deq(k).bits
      schd.io.deq(k).ready            := dataPath.io.fromIQ(e).ready
      schd.io.wbBusyTableRead(e)      := dataPath.io.wbBusyTableRead(e)
      dataPath.io.wbBusyTableWrite(e) := schd.io.wbBusyTableWrite(e)
    }
  }

  // ---- DataPath <-> EXU / WbDataPath 占位 ----
  for (e <- 0 until AnsIssueParams.nExu) {
    exuBlock.io.in(e).valid    := dataPath.io.toExu(e).valid
    exuBlock.io.in(e).bits     := dataPath.io.toExu(e).bits
    dataPath.io.toExu(e).ready := exuBlock.io.in(e).ready
  }
  dataPath.io.exuOutput    := exuBlock.io.out
  dataPath.io.intWriteBack := wbDp.io.intWriteBack
  dataPath.io.fpWriteBack  := wbDp.io.fpWriteBack
  wbDp.io.fromExu          := exuBlock.io.out

  // PC 镜像：前端进入 Decode 的第一个槽位按 ftqPtr 更新（RVC 下需改为按实际槽位）。
  val pcWrite = Wire(new AnsPcMemWrite(params))
  pcWrite.wen            := io.in(0).valid && ctrlBlock.io.in(0).ready
  pcWrite.ftqPtr         := io.in(0).bits.ftqPtr
  pcWrite.pc             := io.in(0).bits.pc
  pcWrite.target         := io.in(0).bits.pc + 4.U
  dataPath.io.pcMemWrite := pcWrite

  // TODO: 用真实 LSQ 替换；当前永远可入队、返回全 0 lq/sq 索引。
  ctrlBlock.io.lsqEnq.canAccept := true.B
  ctrlBlock.io.lsqEnq.resp      := 0.U.asTypeOf(ctrlBlock.io.lsqEnq.resp)
  ctrlBlock.io.fromMem          := 0.U.asTypeOf(ctrlBlock.io.fromMem)

  // ---- debug mirrors ----
  io.debug.t1Valid       := ctrlBlock.io.debug.t1Valid
  io.debug.renameInValid := ctrlBlock.io.debug.renameInValid
  io.debug.renameInReady := ctrlBlock.io.debug.renameInReady
  io.debug.renameIn      := ctrlBlock.io.debug.renameIn
  io.debug.intRat        := ctrlBlock.io.debug.intRat
  io.debug.fpRat         := ctrlBlock.io.debug.fpRat
  io.debug.fusionInfo    := ctrlBlock.io.debug.fusionInfo
  io.debug.dispatch      := ctrlBlock.io.debug.dispatch
  io.debug.trapInstInfo  := ctrlBlock.io.trapInstInfo

  for (port <- 0 until AnsDispatchParams.IQEnqSum) {
    io.debug.iqEnq(port).valid := ctrlBlock.io.toIssueQueues(port).valid
    io.debug.iqEnq(port).bits  := ctrlBlock.io.toIssueQueues(port).bits
  }
}
