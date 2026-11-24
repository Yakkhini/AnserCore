/* Scheduler：一类同构 IQ 的管理器。
 *
 * 职责（对应设计文档 §5.1）：
 *   - 把 Dispatch 的 uop 接到各 IQ 入队口；
 *   - 汇总 PRF 写回唤醒并分发到每个 IQ；
 *   - 按 exuIdx 连接跨 Scheduler 的 IQ 唤醒；
 *   - 把 DataPath 的 data0/data1 响应、cancel 信号送回对应 IQ；
 *   - MemScheduler 额外负责 STA0 -> STD0 的 uop 复制（将 store data 源搬到 STD 的 slot0）。
 *
 * 结构对齐香山的 SchedulerImpBase / SchedulerArithImp / SchedulerMemImp：
 * 公共连线在 Base，Dispatch 入队路径按 Scheduler 类型特化，由工厂选择实现类。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

abstract class AnsSchedulerBase(
  val params: BundleParams = BundleParams(),
  val schd:   AnsSchedulerParams,
) extends Module {
  import AnsScheduler.DispatchPort

  schd.validate(params)
  val io = IO(new AnsSchedulerIO(params, schd))

  val nExu = AnsIssueParams.nExu

  protected val iqs: Seq[AnsIssueQueue] =
    schd.iqs.map(p => Module(new AnsIssueQueue(params, p)))

  protected val iqByName: Map[String, AnsIssueQueue] =
    schd.iqs.zip(iqs).map { case (p, m) => p.name -> m }.toMap

  protected val dispatchFedPorts: Seq[DispatchPort] = {
    val bases = schd.dispatchIQs.scanLeft(0)(_ + _.numEnq).init
    schd.dispatchIQs.zip(bases).flatMap { case (p, base) =>
      (0 until p.numEnq).map(e => DispatchPort(iqByName(p.name), e, base + e))
    }
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

  // ---- 逐 IQ 连接 ----
  private val deqPortOffsets = schd.iqs.scanLeft(0)(_ + _.numDeq)
  require(deqPortOffsets.last == schd.numDeqPorts, "dequeue port accounting mismatch")
  schd.iqs.zip(iqs).zip(deqPortOffsets.init).foreach { case ((iqParams, iq), deqBase) =>
    iq.io.flush        := io.flush
    iq.io.ldCancel     := io.ldCancel
    iq.io.data0Cancel  := io.fromDataPath.data0Cancel
    iq.io.data1Cancel  := io.fromDataPath.data1Cancel
    iq.io.wakeUpFromWB := wbWakeups
    iqParams.wakeupSourceExuIdx.zipWithIndex.foreach { case (exuIdx, k) =>
      iq.io.wakeUpFromIQ(k) := io.wakeupIn(exuIdx)
    }

    iqParams.exus.zipWithIndex.foreach { case (exu, d) =>
      val deqPortIdx = deqBase + d
      iq.io.data0Resp(d)       := io.fromDataPath.data0Resp(exu.exuIdx)
      iq.io.data1Resp(d)       := io.fromDataPath.data1Resp(exu.exuIdx)
      iq.io.wbBusyTableRead(d) := io.wbBusyTableRead(exu.exuIdx)
      iq.io.exuBusy(d)         := io.exuBusy(exu.exuIdx)

      io.wbBusyTableWrite(exu.exuIdx) := iq.io.wbBusyTableWrite(d)

      io.deq(deqPortIdx).valid := iq.io.deq(d).valid
      io.deq(deqPortIdx).bits  := iq.io.deq(d).bits
      iq.io.deq(d).ready       := io.deq(deqPortIdx).ready

      io.wakeupOut(exu.exuIdx).valid       := iq.io.wakeupToIQ(d).valid
      io.wakeupOut(exu.exuIdx).bits        := iq.io.wakeupToIQ(d).bits
      io.wakeupOut(exu.exuIdx).bits.rcDest := io.fromDataPath.rcDest(exu.exuIdx)

      io.IQValidNumVec(exu.exuIdx) := iq.io.validCntDeqVec(d)
    }
  }

  // ---- Dispatch 入队 ----

  /** 为给定的 Dispatch 直连口整束接线；特化入队口由子类单独连接。 */
  final protected def bulkConnectDispatch(ports: Seq[DispatchPort]): Unit =
    ports.foreach { port =>
      port.iq.io.enq(port.enqSlot) <> io.fromDispatch(port.dispatchPort)
    }

  /** 由具体子类在构造末尾调用，避免在基类构造器里调用抽象方法。 */
  protected def connectDispatch(): Unit
}

/** 纯算术 Scheduler：Dispatch 直连口整束 `<>`。 */
final class AnsSchedulerArith(
  params: BundleParams = BundleParams(),
  schd:   AnsSchedulerParams,
) extends AnsSchedulerBase(params, schd) {
  protected def connectDispatch(): Unit = bulkConnectDispatch(dispatchFedPorts)
  connectDispatch()
}

/** Mem Scheduler：在直连口之外，把 STA0 的 uop 复制给 STD0。 */
final class AnsSchedulerMem(
  params: BundleParams = BundleParams(),
  schd:   AnsSchedulerParams,
) extends AnsSchedulerBase(params, schd) {
  require(iqByName.contains("STA0"), s"${schd.schdType} must contain STA0")
  require(iqByName.contains("STD0"), s"${schd.schdType} must contain STD0")
  require(
    iqByName("STA0").iq.numEnq == iqByName("STD0").iq.numEnq,
    "STA0/STD0 enqueue width mismatch",
  )

  protected def connectDispatch(): Unit = {
    bulkConnectDispatch(dispatchFedPorts.filterNot(_.iq.iq.name == "STA0"))
    connectStaToStd()
  }
  connectDispatch()

  /** 把 STA0 的入队 uop 派生一份给 STD0。
    *
    * Dispatch 的 store uop: slot0 = rs1（地址），slot1 = rs2（store data）。 STA 只保留地址源；STD 只保留搬到 slot0 的 store data。两份 uop
    * 均从原始输入构造，各目标端口只连接一次。
    */
  private def connectStaToStd(): Unit = {
    val srcIQ      = iqByName("STA0")
    val dstIQ      = iqByName("STD0")
    val srcSlot    = 1
    val dstSlot    = 0
    val dstNeutral = (0 until params.numSrc).filterNot(_ == dstSlot)
    require(srcSlot < params.numSrc, s"store data slot $srcSlot out of range")

    for (e <- 0 until srcIQ.iq.numEnq) {
      val in = io.fromDispatch(schd.dispatchPortOf("STA0", e))

      // STA 不使用 store data；清掉后不会等待它。
      val staUop = WireInit(in.bits)
      staUop.srcType(srcSlot)  := SrcType.none.U
      staUop.srcState(srcSlot) := SourceState.ready.U
      val stdUop = makeCopiedUop(in.bits, srcSlot, dstSlot, dstNeutral)

      // Atomic fork: neither child may consume a request unless its peer can too.
      // IQ enqueue ready depends on queue state, not on this valid.
      srcIQ.io.enq(e).valid := in.valid && dstIQ.io.enq(e).ready
      srcIQ.io.enq(e).bits  := staUop
      dstIQ.io.enq(e).valid := in.valid && srcIQ.io.enq(e).ready
      dstIQ.io.enq(e).bits  := stdUop

      // 两个队列都有空间时，才向 Dispatch 返回 ready。
      in.ready := srcIQ.io.enq(e).ready && dstIQ.io.enq(e).ready
      assert(srcIQ.io.enq(e).fire === in.fire, "STA enqueue must match the store input handshake")
      assert(dstIQ.io.enq(e).fire === in.fire, "STD enqueue must match the store input handshake")
    }
  }

  /** 把 srcSlot 的源字段组复制到 dstSlot；neutralize 中的槽置 none/ready。 */
  private def makeCopiedUop(
    uop:        DynInst,
    srcSlot:    Int,
    dstSlot:    Int,
    neutralize: Seq[Int] = Nil,
  ): DynInst = {
    require(srcSlot != dstSlot, "copy slots must differ")
    val out = WireInit(uop)
    out.psrc(dstSlot)              := uop.psrc(srcSlot)
    out.srcType(dstSlot)           := uop.srcType(srcSlot)
    out.srcState(dstSlot)          := uop.srcState(srcSlot)
    out.srcLoadDependency(dstSlot) := uop.srcLoadDependency(srcSlot)
    out.useRegCache(dstSlot)       := uop.useRegCache(srcSlot)
    out.regCacheIdx(dstSlot)       := uop.regCacheIdx(srcSlot)
    for (s <- neutralize if s != dstSlot) {
      out.srcType(s)  := SrcType.none.U
      out.srcState(s) := SourceState.ready.U
    }
    out
  }
}

object AnsScheduler {

  /** Elaboration 阶段的端口映射，不增加硬件寄存器。 */
  final case class DispatchPort(iq: AnsIssueQueue, enqSlot: Int, dispatchPort: Int)

  def apply(params: BundleParams, schd: AnsSchedulerParams): AnsSchedulerBase =
    schd.schdType match {
      case AnsMemScheduler                  => new AnsSchedulerMem(params, schd)
      case AnsIntScheduler | AnsFpScheduler => new AnsSchedulerArith(params, schd)
    }
}
