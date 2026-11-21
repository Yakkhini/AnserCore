package ansercore.backend.issue

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._

/** MemScheduler 的 Dispatch -> IQ 入队路径测试。
  *
  * 覆盖三件事：
  *   - STA 不等待 store data（slot1 在 Scheduler 入口被清空）；
  *   - STD 从原始 Dispatch slot1 复制 store data 到 slot0；
  *   - Dispatch ready 是 STA/STD 两个 IQ ready 的与。
  *
  * Scheduler 电路较大，测试走 Verilator 后端；Treadle 每拍开销过高。
  */
class AnsSchedulerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams(renameWidth = 4)
  private val schd   = AnsIssueParams.memScheduler

  private val staEnq = schd.dispatchPortOf("STA0", 0)
  private val staDeq = schd.iqs.takeWhile(_.name != "STA0").map(_.numDeq).sum
  private val stdDeq = schd.iqs.takeWhile(_.name != "STD0").map(_.numDeq).sum

  private val addrPreg = 8
  private val dataPreg = 12

  private def clearDynInst(uop: DynInst): Unit = {
    uop.instr.poke(0.U)
    uop.pc.poke(0.U)
    uop.exceptionBits.poke(0.U)
    uop.predictedTaken.poke(false.B)
    uop.ftqPtr.value.poke(0.U)
    uop.ftqPtr.flag.poke(false.B)
    uop.ftqOffset.poke(0.U)
    uop.logicalDest.poke(0.U)
    uop.fuType.poke(0.U)
    uop.fuOpType.poke(0.U)
    uop.intWen.poke(false.B)
    uop.fpWen.poke(false.B)
    uop.waitForward.poke(false.B)
    uop.blockBackward.poke(false.B)
    uop.commitSelfFlush.poke(false.B)
    uop.immType.poke(0.U)
    uop.imm.poke(0.U)
    uop.isMove.poke(false.B)
    uop.commitType.poke(0.U)
    uop.needsRoundingMode.poke(false.B)
    uop.rm.poke(0.U)
    uop.pdest.poke(0.U)
    uop.robIdx.poke(0.U)
    uop.instrSize.poke(0.U)
    uop.lqIdx.poke(0.U)
    uop.sqIdx.poke(0.U)
    uop.eliminatedMove.poke(false.B)
    uop.snapshot.poke(false.B)
    uop.replayInst.poke(false.B)
    for (j <- 0 until params.numSrc) {
      uop.psrc(j).poke(0.U)
      uop.srcType(j).poke(SrcType.none.U)
      uop.srcState(j).poke(SourceState.ready.U)
      uop.srcLoadDependency(j).foreach(_.poke(0.U))
      uop.useRegCache(j).poke(false.B)
      uop.regCacheIdx(j).poke(0.U)
    }
  }

  private def idle(dut: AnsSchedulerBase): Unit = {
    dut.io.flush.valid.poke(false.B)
    dut.io.flush.bits.robIdx.poke(0.U)
    dut.io.flush.bits.flushItself.poke(false.B)
    dut.io.flush.bits.isInterrupt.poke(false.B)

    for (p <- 0 until schd.dispatchEnqPorts) {
      dut.io.fromDispatch(p).valid.poke(false.B)
      clearDynInst(dut.io.fromDispatch(p).bits)
    }
    for (p <- 0 until AnsIssueParams.nIntWb) {
      dut.io.intWriteBack(p).wen.poke(false.B)
      dut.io.intWriteBack(p).addr.poke(0.U)
      dut.io.intWriteBack(p).data.poke(0.U)
    }
    for (p <- 0 until AnsIssueParams.nFpWb) {
      dut.io.fpWriteBack(p).wen.poke(false.B)
      dut.io.fpWriteBack(p).addr.poke(0.U)
      dut.io.fpWriteBack(p).data.poke(0.U)
    }
    for (p <- 0 until AnsIssueParams.nExu) {
      dut.io.wakeupIn(p).valid.poke(false.B)
      dut.io.wakeupIn(p).bits.robIdx.poke(0.U)
      dut.io.wakeupIn(p).bits.intWen.poke(false.B)
      dut.io.wakeupIn(p).bits.fpWen.poke(false.B)
      dut.io.wakeupIn(p).bits.pdest.poke(0.U)
      dut.io.wakeupIn(p).bits.loadDependency.foreach(_.poke(0.U))
      dut.io.wakeupIn(p).bits.is0Lat.poke(false.B)
      dut.io.wakeupIn(p).bits.rcDest.poke(0.U)

      dut.io.fromDataPath.data0Resp(p).valid.poke(false.B)
      dut.io.fromDataPath.data0Resp(p).bits.robIdx.poke(0.U)
      dut.io.fromDataPath.data0Resp(p).bits.fuType.poke(0.U)
      dut.io.fromDataPath.data0Resp(p).bits.resp.poke(IssueRespType.block.U)
      dut.io.fromDataPath.data1Resp(p).valid.poke(false.B)
      dut.io.fromDataPath.data1Resp(p).bits.robIdx.poke(0.U)
      dut.io.fromDataPath.data1Resp(p).bits.fuType.poke(0.U)
      dut.io.fromDataPath.data1Resp(p).bits.resp.poke(IssueRespType.block.U)
      dut.io.fromDataPath.data0Cancel(p).poke(false.B)
      dut.io.fromDataPath.data1Cancel(p).poke(false.B)
      dut.io.fromDataPath.data0CancelToDispatch(p).intWen.poke(false.B)
      dut.io.fromDataPath.data0CancelToDispatch(p).fpWen.poke(false.B)
      dut.io.fromDataPath.data0CancelToDispatch(p).pdest.poke(0.U)
      dut.io.fromDataPath.wbConflict(p).intConflict.poke(false.B)
      dut.io.fromDataPath.wbConflict(p).fpConflict.poke(false.B)
      dut.io.fromDataPath.rcDest(p).poke(0.U)
      dut.io.wbBusyTableRead(p).intWbBusyTable.poke(0.U)
      dut.io.wbBusyTableRead(p).fpWbBusyTable.poke(0.U)
    }
    for (l <- 0 until params.loadPipelineWidth) {
      dut.io.ldCancel(l).first.poke(false.B)
      dut.io.ldCancel(l).second.poke(false.B)
    }
    for (p <- 0 until schd.numDeqPorts) {
      dut.io.deq(p).ready.poke(true.B)
    }
  }

  /** store 指令：slot0 = 地址 rs1，slot1 = store data rs2。 */
  private def storeUop(uop: DynInst, addrReady: Boolean, dataReady: Boolean): Unit = {
    clearDynInst(uop)
    uop.fuType.poke(FuType.stu.U)
    uop.psrc(0).poke(addrPreg.U)
    uop.srcType(0).poke(SrcType.integer.U)
    uop.srcState(0).poke((if (addrReady) SourceState.ready else SourceState.waiting).U)
    uop.psrc(1).poke(dataPreg.U)
    uop.srcType(1).poke(SrcType.integer.U)
    uop.srcState(1).poke((if (dataReady) SourceState.ready else SourceState.waiting).U)
  }

  private def enqueueStore(
    dut:       AnsSchedulerBase,
    port:      Int,
    addrReady: Boolean,
    dataReady: Boolean,
  ): Unit = {
    dut.io.fromDispatch(port).valid.poke(true.B)
    storeUop(dut.io.fromDispatch(port).bits, addrReady, dataReady)
    dut.clock.step()
    dut.io.fromDispatch(port).valid.poke(false.B)
  }

  /** 模拟 DataPath：data0 通过时静默；把上一拍观察到的 deq 在 data1 回 success。 */
  private def answerDeq(dut: AnsSchedulerBase, pending: Array[Boolean]): Unit = {
    val ports = Seq(staDeq -> "STA0", stdDeq -> "STD0")
    ports.zipWithIndex.foreach { case ((_, exuName), idx) =>
      val resp = dut.io.fromDataPath.data1Resp(AnsIssueParams.exuIdxOf(exuName))
      resp.valid.poke(pending(idx).B)
      resp.bits.robIdx.poke(0.U)
      resp.bits.fuType.poke(0.U)
      resp.bits.resp.poke(IssueRespType.success.U)
    }
    ports.zipWithIndex.foreach { case ((port, _), idx) =>
      pending(idx) = dut.io.deq(port).valid.peek().litToBoolean
    }
  }

  /** 在 cycles 拍内等待某个出队口发射，返回 (psrc(0), srcType(0))。 */
  private def firstIssue(
    dut:    AnsSchedulerBase,
    port:   Int,
    cycles: Int = 10,
  ): Option[(Int, Int)] = {
    var result: Option[(Int, Int)] = None
    var i                          = 0
    while (i < cycles && result.isEmpty) {
      dut.clock.step()
      if (dut.io.deq(port).valid.peek().litToBoolean) {
        result = Some(
          (dut.io.deq(port).bits.psrc(0).peek().litValue.toInt, dut.io.deq(port).bits.srcType(0).peek().litValue.toInt)
        )
      }
      i += 1
    }
    result
  }

  behavior of "AnsScheduler"

  it should "issue STA without waiting for store data and feed store data to STD" in {
    test(AnsScheduler(params, schd)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      idle(dut)

      enqueueStore(dut, staEnq, addrReady = true, dataReady = false)

      // STA 只跟踪地址源：store data 还未就绪也可以发射。
      firstIssue(dut, staDeq) shouldBe Some((addrPreg, SrcType.integer))
      firstIssue(dut, stdDeq, cycles = 4) shouldBe None

      // 唤醒 store data 后，STD 的 slot0（由 slot1 复制而来）变为就绪。
      dut.io.intWriteBack(0).wen.poke(true.B)
      dut.io.intWriteBack(0).addr.poke(dataPreg.U)
      dut.clock.step()
      dut.io.intWriteBack(0).wen.poke(false.B)

      firstIssue(dut, stdDeq) shouldBe Some((dataPreg, SrcType.integer))
    }
  }

  it should "backpressure Dispatch until both STA and STD have room" in {
    test(AnsScheduler(params, schd)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      idle(dut)

      // 两个源都 waiting，队列会填满而不会发射。
      dut.io.fromDispatch(staEnq).valid.poke(true.B)
      storeUop(dut.io.fromDispatch(staEnq).bits, addrReady = false, dataReady = false)

      var enqueued = 0
      while (enqueued < 40 && dut.io.fromDispatch(staEnq).ready.peek().litToBoolean) {
        dut.clock.step()
        enqueued += 1
      }
      dut.io.fromDispatch(staEnq).valid.poke(false.B)

      enqueued should be < 40
      enqueued should be > 0
      dut.io.fromDispatch(staEnq).ready.expect(false.B, "both queues are full")

      // 只唤醒地址：STA 排空，STD 仍满，Dispatch 必须继续被反压。
      dut.io.intWriteBack(0).wen.poke(true.B)
      dut.io.intWriteBack(0).addr.poke(addrPreg.U)
      val staPending = Array(false, false)
      for (_ <- 0 until 40) {
        answerDeq(dut, staPending)
        dut.clock.step()
      }
      dut.io.intWriteBack(0).wen.poke(false.B)
      dut.io.fromDispatch(staEnq).ready.expect(false.B, "STD still full")

      // 再唤醒 store data：STD 也排空，反压解除。
      dut.io.intWriteBack(0).wen.poke(true.B)
      dut.io.intWriteBack(0).addr.poke(dataPreg.U)
      val stdPending = Array(false, false)
      for (_ <- 0 until 40) {
        answerDeq(dut, stdPending)
        dut.clock.step()
      }
      dut.io.intWriteBack(0).wen.poke(false.B)
      dut.io.fromDispatch(staEnq).ready.expect(true.B, "both queues have room")
    }
  }
}
