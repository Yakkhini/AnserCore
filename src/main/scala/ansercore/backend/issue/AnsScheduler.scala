/* Scheduler：一类同构 IQ 的管理器。
 *
 * 职责（对应设计文档 §5.1）：
 *   - 把 Dispatch 的 uop 接到各 IQ 入队口；
 *   - 汇总 PRF 写回唤醒并分发到每个 IQ；
 *   - 按 exuIdx 连接跨 Scheduler 的 IQ 唤醒；
 *   - 把 DataPath 的 data0/data1 响应、cancel 信号送回对应 IQ；
 *   - MemScheduler 额外负责 STA0 -> STD0 的 uop 复制（store data 复用地址源）。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

class AnsScheduler(
  val params: BundleParams = BundleParams(),
  val schd:   AnsSchedulerParams,
) extends Module {
  val io = IO(new AnsSchedulerIO(params, schd))

  val nExu     = AnsIssueParams.nExu
  val iqs      = schd.iqs.map(p => Module(new AnsIssueQueue(params, p)))
  val iqByName = schd.iqs.zip(iqs).map { case (p, m) => p.name -> m }.toMap

  for {
    sta <- schd.iqs.find(_.name == "STA0")
    std <- schd.iqs.find(_.name == "STD0")
  } {
    require(sta.numEnq == std.numEnq, "STA0 and STD0 must have the same enqueue width")
  }

  // ---- 默认输出 ----
  io.IQValidNumVec := 0.U.asTypeOf(io.IQValidNumVec)
  for (i <- 0 until nExu) {
    io.wakeupOut(i).valid  := false.B
    io.wakeupOut(i).bits   := 0.U.asTypeOf(io.wakeupOut(i).bits)
    io.wbBusyTableWrite(i) := 0.U.asTypeOf(io.wbBusyTableWrite(i))
  }

  // ---- 写回唤醒 ----
  val wbWakeups = Wire(Vec(AnsIssueParams.nIntWb + AnsIssueParams.nFpWb, Valid(new AnsWbWakeUp(params))))
  for (k <- 0 until AnsIssueParams.nIntWb) {
    val wb = io.intWriteBack(k)
    wbWakeups(k).valid       := wb.wen
    wbWakeups(k).bits.intWen := true.B
    wbWakeups(k).bits.fpWen  := false.B
    wbWakeups(k).bits.pdest  := wb.addr
  }
  for (k <- 0 until AnsIssueParams.nFpWb) {
    val wb = io.fpWriteBack(k)
    wbWakeups(AnsIssueParams.nIntWb + k).valid       := wb.wen
    wbWakeups(AnsIssueParams.nIntWb + k).bits.intWen := false.B
    wbWakeups(AnsIssueParams.nIntWb + k).bits.fpWen  := true.B
    wbWakeups(AnsIssueParams.nIntWb + k).bits.pdest  := wb.addr
  }

  // ---- Dispatch 入队 ----
  val dispatchFed          = schd.iqs.filterNot(_.name == "STD0")
  private var dispatchPort = 0
  dispatchFed.zipWithIndex.foreach { case (iqParams, iqPos) =>
    val iq = iqByName(iqParams.name)
    for (e <- 0 until iqParams.numEnq) {
      val isSta = schd.schdType == AnsMemScheduler && iqParams.name == "STA0"
      if (isSta) {
        val std    = iqByName("STD0")
        val in     = io.fromDispatch(dispatchPort)
        // STA 只需要地址源 rs1；store data 由 STD0 单独跟踪。
        val staUop = WireInit(in.bits)
        staUop.srcType(1)   := SrcType.none.U
        staUop.srcState(1)  := SourceState.ready.U
        iq.io.enq(e).valid  := in.valid
        iq.io.enq(e).bits   := staUop
        std.io.enq(e).valid := in.valid
        std.io.enq(e).bits  := AnsScheduler.makeStdUop(in.bits)
        in.ready            := iq.io.enq(e).ready && std.io.enq(e).ready
      } else {
        val in = io.fromDispatch(dispatchPort)
        iq.io.enq(e).valid := in.valid
        iq.io.enq(e).bits  := in.bits
        in.ready           := iq.io.enq(e).ready
      }
      dispatchPort += 1
    }
  }
  require(dispatchPort == schd.dispatchEnqPorts, "dispatch port accounting mismatch")

  // ---- 逐 IQ 连接 ----
  private var deqPortIdx = 0
  schd.iqs.zip(iqs).foreach { case (iqParams, iq) =>
    iq.io.flush        := io.flush
    iq.io.ldCancel     := io.ldCancel
    iq.io.data0Cancel  := io.fromDataPath.data0Cancel
    iq.io.wakeUpFromWB := wbWakeups
    iqParams.wakeupSourceExuIdx.zipWithIndex.foreach { case (exuIdx, k) =>
      iq.io.wakeUpFromIQ(k) := io.wakeupIn(exuIdx)
    }

    iqParams.exus.zipWithIndex.foreach { case (exu, d) =>
      iq.io.data0Resp(d)       := io.fromDataPath.data0Resp(exu.exuIdx)
      iq.io.data1Resp(d)       := io.fromDataPath.data1Resp(exu.exuIdx)
      iq.io.wbBusyTableRead(d) := io.wbBusyTableRead(exu.exuIdx)

      io.wbBusyTableWrite(exu.exuIdx) := iq.io.wbBusyTableWrite(d)

      io.deq(deqPortIdx).valid := iq.io.deq(d).valid
      io.deq(deqPortIdx).bits  := iq.io.deq(d).bits
      iq.io.deq(d).ready       := io.deq(deqPortIdx).ready
      deqPortIdx += 1

      io.wakeupOut(exu.exuIdx).valid       := iq.io.wakeupToIQ(d).valid
      io.wakeupOut(exu.exuIdx).bits        := iq.io.wakeupToIQ(d).bits
      io.wakeupOut(exu.exuIdx).bits.rcDest := io.fromDataPath.rcDest(exu.exuIdx)

      io.IQValidNumVec(exu.exuIdx) := iq.io.validCntDeqVec(d)
    }
  }
  require(deqPortIdx == schd.numDeqPorts, "dequeue port accounting mismatch")
}

object AnsScheduler {

  /** Store Data 复用 Store Address 的 rs2：psrc(0) <- psrc(1)。 */
  def makeStdUop(uop: DynInst): DynInst = {
    val out = WireInit(uop)
    out.psrc(0)              := uop.psrc(1)
    out.srcType(0)           := uop.srcType(1)
    out.srcState(0)          := uop.srcState(1)
    out.srcLoadDependency(0) := uop.srcLoadDependency(1)
    out.useRegCache(0)       := uop.useRegCache(1)
    out.regCacheIdx(0)       := uop.regCacheIdx(1)
    out
  }
}
