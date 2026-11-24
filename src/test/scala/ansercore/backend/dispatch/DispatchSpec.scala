package ansercore.backend.dispatch

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.issue.AnsIssueParams

class DispatchSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams(renameWidth = 4)
  private val iqSum  = AnsDispatchParams.IQEnqSum
  private val exuNum = AnsIssueParams.nExu

  private def enqPort(iq: Int, slot: Int = 0): Int = AnsDispatchParams.iqEnqPort(iq, slot)

  private def clearUop(bits: DynInst): Unit = {
    bits.fuType.poke(0.U)
    bits.pdest.poke(0.U)
    for (j <- 0 until params.numSrc) {
      bits.psrc(j).poke(0.U)
      bits.srcType(j).poke(SrcType.none.U)
    }
    bits.intWen.poke(false.B)
    bits.fpWen.poke(false.B)
    bits.waitForward.poke(false.B)
    bits.blockBackward.poke(false.B)
    bits.eliminatedMove.poke(false.B)
    bits.exceptionBits.poke(0.U)
  }

  private def idle(dut: AnsDispatch): Unit = {
    dut.io.redirect.valid.poke(false.B)
    dut.io.redirect.bits.robIdx.poke(0.U)
    dut.io.redirect.bits.flushItself.poke(false.B)
    dut.io.redirect.bits.isInterrupt.poke(false.B)

    for (lane <- 0 until params.renameWidth) {
      dut.io.renameIn(lane).valid.poke(false.B)
      dut.io.renameIn(lane).bits.fuType.poke(0.U)
      dut.io.fromRename(lane).valid.poke(false.B)
      clearUop(dut.io.fromRename(lane).bits)
      dut.io.enqRob.resp(lane).poke(0.U)
      dut.io.lsqEnq.resp(lane).lqIdx.poke(0.U)
      dut.io.lsqEnq.resp(lane).sqIdx.poke(0.U)
    }

    for (port <- 0 until iqSum) {
      dut.io.toIssueQueues(port).ready.poke(true.B)
    }
    for (exu <- 0 until exuNum) {
      dut.io.IQValidNumVec(exu).poke(0.U)
    }

    dut.io.enqRob.canAccept.poke(true.B)
    dut.io.enqRob.canAcceptForDispatch.poke(true.B)
    dut.io.enqRob.isEmpty.poke(true.B)
    dut.io.lsqEnq.canAccept.poke(true.B)

    for (port <- 0 until AnsDispatchParams.nIntWb) {
      dut.io.wbPregsInt(port).valid.poke(false.B)
      dut.io.wbPregsInt(port).bits.poke(0.U)
    }
    for (port <- 0 until AnsDispatchParams.nFpWb) {
      dut.io.wbPregsFp(port).valid.poke(false.B)
      dut.io.wbPregsFp(port).bits.poke(0.U)
    }
    for (port <- 0 until AnsDispatchParams.nIntWakeUp) {
      dut.io.wakeUpAll.wakeUpInt(port).valid.poke(false.B)
      clearWakeUp(dut.io.wakeUpAll.wakeUpInt(port).bits)
    }
    for (port <- 0 until AnsDispatchParams.nFpWakeUp) {
      dut.io.wakeUpAll.wakeUpFp(port).valid.poke(false.B)
      clearWakeUp(dut.io.wakeUpAll.wakeUpFp(port).bits)
    }
    for (port <- 0 until AnsDispatchParams.nMemWakeUp) {
      dut.io.wakeUpAll.wakeUpMem(port).valid.poke(false.B)
      clearWakeUp(dut.io.wakeUpAll.wakeUpMem(port).bits)
    }
    for (port <- 0 until AnsDispatchParams.nExu) {
      dut.io.data0CancelToDispatch(port).intWen.poke(false.B)
      dut.io.data0CancelToDispatch(port).fpWen.poke(false.B)
      dut.io.data0CancelToDispatch(port).pdest.poke(0.U)
    }
    for (port <- 0 until params.loadPipelineWidth) {
      dut.io.ldCancel(port).first.poke(false.B)
      dut.io.ldCancel(port).second.poke(false.B)
    }
    dut.io.fromMem.lcommit.poke(0.U)
    dut.io.fromMem.scommit.poke(0.U)
    dut.io.fromMem.lqDeqPtr.poke(0.U)
    dut.io.fromMem.sqDeqPtr.poke(0.U)
    dut.io.fromMem.lqCancelCnt.poke(0.U)
    dut.io.fromMem.sqCancelCnt.poke(0.U)
    dut.io.singleStep.poke(false.B)
  }

  private def clearWakeUp(w: AnsIQWakeUpBundle): Unit = {
    w.rfWen.poke(false.B)
    w.fpWen.poke(false.B)
    w.pdest.poke(0.U)
    w.rcDest.poke(0.U)
    w.is0Lat.poke(false.B)
    w.loadDependency.foreach(_.poke(0.U))
  }

  private def resetDispatch(dut: AnsDispatch): Unit = {
    dut.reset.poke(true.B)
    idle(dut)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step(4)
  }

  /** 驱动 Dispatch 看到的 T1 组（只用到 fuType 做 IQ 预选）。 */
  private def renameIn(dut: AnsDispatch, lane: Int, fuType: Int): Unit = {
    dut.io.renameIn(lane).valid.poke(true.B)
    dut.io.renameIn(lane).bits.fuType.poke(fuType.U)
  }

  private def clearRenameIn(dut: AnsDispatch): Unit =
    for (lane <- 0 until params.renameWidth) {
      dut.io.renameIn(lane).valid.poke(false.B)
    }

  private def driveUop(
    dut:            AnsDispatch,
    lane:           Int,
    fuType:         Int,
    pdest:          Int      = 0,
    psrc:           Seq[Int] = Seq(0, 0, 0),
    srcType:        Seq[Int] = Seq(SrcType.immediate, SrcType.immediate, SrcType.none),
    intWen:         Boolean  = true,
    fpWen:          Boolean  = false,
    waitForward:    Boolean  = false,
    blockBackward:  Boolean  = false,
    eliminatedMove: Boolean  = false,
    exception:      Boolean  = false,
  ): Unit = {
    val bits = dut.io.fromRename(lane).bits
    bits.fuType.poke(fuType.U)
    bits.pdest.poke(pdest.U)
    for (j <- 0 until params.numSrc) {
      bits.psrc(j).poke(psrc(j).U)
      bits.srcType(j).poke(srcType(j).U)
    }
    bits.intWen.poke(intWen.B)
    bits.fpWen.poke(fpWen.B)
    bits.waitForward.poke(waitForward.B)
    bits.blockBackward.poke(blockBackward.B)
    bits.eliminatedMove.poke(eliminatedMove.B)
    bits.exceptionBits.poke((if (exception) 1 else 0).U)
    dut.io.fromRename(lane).valid.poke(true.B)
  }

  /** uopSelIQ 在 toRenameAllFire 时采样 T1 组：先单独打一拍再给 fromRename。 */
  private def selectIQs(dut: AnsDispatch, fuTypes: Seq[Int]): Unit = {
    clearRenameIn(dut)
    fuTypes.zipWithIndex.foreach { case (fu, lane) => renameIn(dut, lane, fu) }
    dut.clock.step()
    clearRenameIn(dut)
  }

  behavior of "Dispatch"

  it should "route multi-IQ groups to the least loaded queue and single-IQ groups to their fixed queue" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)

      // ALU 出队口按负载排序：ALU1(0) < ALU2(2) < ALU0(4)。
      dut.io.IQValidNumVec(AnsDispatchParams.exuALU0).poke(4.U)
      dut.io.IQValidNumVec(AnsDispatchParams.exuALU1).poke(0.U)
      dut.io.IQValidNumVec(AnsDispatchParams.exuALU2).poke(2.U)
      selectIQs(dut, Seq.fill(3)(FuType.alu))
      driveUop(dut, 0, FuType.alu, pdest = 1)
      driveUop(dut, 1, FuType.alu, pdest = 2)
      driveUop(dut, 2, FuType.alu, pdest = 3)

      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ1)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ1)).bits.pdest.expect(1.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ2)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ2)).bits.pdest.expect(2.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.pdest.expect(3.U)
    }
  }

  it should "enqueue two uops into the same IQ in one cycle using both enqueue ports" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)

      // 负载全 0：rank0/1/2 = ALU0/ALU1/ALU2；4 条 ALU 的组内序号为 0,1,2,3，
      // 因此 lane0 和 lane3 都选中 Int-IQ0。
      selectIQs(dut, Seq.fill(4)(FuType.alu))
      for (i <- 0 until 4) {
        driveUop(dut, i, FuType.alu, pdest = i + 1)
      }

      // lane0 -> IQ0 port0，lane3 -> IQ0 port1，同拍都可入队。
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0, 0)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0, 0)).bits.pdest.expect(1.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0, 1)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0, 1)).bits.pdest.expect(4.U)
      for (i <- 0 until 4) {
        dut.io.fromRename(i).ready.expect(true.B)
      }
    }
  }

  it should "enqueue only the older uop when the second enqueue port is not ready" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0, 1)).ready.poke(false.B)

      selectIQs(dut, Seq.fill(4)(FuType.alu))
      for (i <- 0 until 4) {
        driveUop(dut, i, FuType.alu, pdest = i + 1)
      }

      // port0 可进 lane0；lane3 因为 port1 不 ready 被挡，保持选择等下一拍。
      dut.io.fromRename(0).ready.expect(true.B)
      dut.io.fromRename(3).ready.expect(false.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0, 0)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0, 0)).bits.pdest.expect(1.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0, 1)).valid.expect(false.B)
    }
  }

  it should "route CSR and FENCE through the shared MUL0 issue owner" in {
    test(new AnsDispatch(params)) { dut =>
      for (fu <- Seq(FuType.csr, FuType.fence)) {
        resetDispatch(dut)
        selectIQs(dut, Seq(fu))
        driveUop(dut, 0, fu, pdest = 11, intWen = fu == FuType.csr)
        val port = dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0))
        port.valid.expect(true.B); port.bits.fuType.expect(fu.U)
        dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ1)).valid.expect(false.B)
      }
    }
  }

  it should "send div/fcvt/ldu/stu to their fixed or balanced queues" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)
      dut.io.IQValidNumVec(AnsDispatchParams.exuLDU1).poke(3.U)
      selectIQs(
        dut,
        Seq(FuType.div, FuType.fcvt, FuType.ldu, FuType.stu),
      )
      driveUop(dut, 0, FuType.div, pdest  = 11)
      driveUop(dut, 1, FuType.fcvt, pdest = 12, intWen = false, fpWen = true)
      driveUop(dut, 2, FuType.ldu, pdest  = 13)
      driveUop(dut, 3, FuType.stu, pdest  = 14, intWen = false)

      // DIV 现在路由到 MUL0（Int-IQ0）。
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.pdest.expect(11.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.fpIQ0)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.fpIQ0)).bits.pdest.expect(12.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.ldu0)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.ldu0)).bits.pdest.expect(13.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.sta0)).valid.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.sta0)).bits.pdest.expect(14.U)
    }
  }

  it should "stall the whole group when ROB cannot accept and release it" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)
      selectIQs(dut, Seq(FuType.alu))

      dut.io.enqRob.canAcceptForDispatch.poke(false.B)
      driveUop(dut, 0, FuType.alu, pdest = 1)
      dut.io.fromRename(0).ready.expect(false.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).valid.expect(false.B)

      dut.io.enqRob.canAcceptForDispatch.poke(true.B)
      dut.io.fromRename(0).ready.expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).valid.expect(true.B)
    }
  }

  it should "block waitForward lanes until ROB drains and block younger lanes behind blockBackward" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)
      selectIQs(dut, Seq.fill(2)(FuType.alu))

      dut.io.enqRob.isEmpty.poke(false.B)
      driveUop(dut, 0, FuType.alu, pdest = 1, waitForward = true)
      dut.io.fromRename(0).ready.expect(false.B)

      dut.io.enqRob.isEmpty.poke(true.B)
      dut.io.fromRename(0).ready.expect(true.B)

      // blockBackward 只放行本 lane。
      driveUop(dut, 0, FuType.alu, pdest = 1, blockBackward = true)
      driveUop(dut, 1, FuType.alu, pdest = 2)
      dut.io.fromRename(0).ready.expect(true.B)
      dut.io.fromRename(1).ready.expect(false.B)
    }
  }

  it should "allocate LQ/SQ indices for loads, stores and AMOs and drain free counts" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)
      dut.io.debug.lqFreeCount.expect(AnsDispatchParams.lqEntries.U)
      dut.io.debug.sqFreeCount.expect(AnsDispatchParams.sqEntries.U)

      // 第一组：ldu + stu。
      selectIQs(dut, Seq(FuType.ldu, FuType.stu))
      driveUop(dut, 0, FuType.ldu, pdest = 1)
      driveUop(dut, 1, FuType.stu, pdest = 2, intWen = false)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.ldu0)).bits.lqIdx.expect(0.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.sta0)).bits.sqIdx.expect(0.U)
      dut.clock.step()
      dut.io.lsqEnq.needAlloc(0).expect(1.U)
      dut.io.lsqEnq.needAlloc(1).expect(1.U)
      dut.io.lsqEnq.req(0).valid.expect(true.B)
      dut.io.debug.lqFreeCount.expect((AnsDispatchParams.lqEntries - 1).U)
      dut.io.debug.sqFreeCount.expect((AnsDispatchParams.sqEntries - 1).U)
      dut.io.fromRename(0).valid.poke(false.B)
      dut.io.fromRename(1).valid.poke(false.B)

      // 第二组：AMO 同时占用 LQ 和 SQ。
      selectIQs(dut, Seq(FuType.mou))
      driveUop(dut, 0, FuType.mou, pdest = 3)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.sta0)).bits.lqIdx.expect(1.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.sta0)).bits.sqIdx.expect(1.U)
      dut.clock.step()
      dut.io.lsqEnq.needAlloc(0).expect(2.U)
      dut.io.debug.lqFreeCount.expect((AnsDispatchParams.lqEntries - 2).U)
      dut.io.debug.sqFreeCount.expect((AnsDispatchParams.sqEntries - 2).U)
    }
  }

  it should "mark sources busy on allocation, wake them on wakeup and query RegCache tags" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)

      // 同拍分配旁路：lane0 写 pdest=9，lane1 读 9 应为 waiting。
      selectIQs(dut, Seq.fill(2)(FuType.alu))
      driveUop(dut, 0, FuType.alu, pdest = 9)
      driveUop(
        dut,
        1,
        FuType.alu,
        pdest                            = 10,
        psrc                             = Seq(9, 0, 0),
        srcType                          = Seq(SrcType.integer, SrcType.immediate, SrcType.none),
      )
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.srcState(0).expect(SourceState.ready.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ1)).bits.srcState(0).expect(SourceState.waiting.U)
      dut.clock.step()
      dut.io.fromRename(0).valid.poke(false.B)
      dut.io.fromRename(1).valid.poke(false.B)

      // 分配后的 preg 保持 busy；同拍唤醒下一拍生效，并把 tag=9 写入 RegCache slot5。
      selectIQs(dut, Seq(FuType.alu))
      driveUop(
        dut,
        0,
        FuType.alu,
        pdest   = 11,
        psrc    = Seq(9, 0, 0),
        srcType = Seq(SrcType.integer, SrcType.immediate, SrcType.none),
      )
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.srcState(0).expect(SourceState.waiting.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.srcState(1).expect(SourceState.ready.U)

      dut.io.wakeUpAll.wakeUpInt(0).valid.poke(true.B)
      dut.io.wakeUpAll.wakeUpInt(0).bits.rfWen.poke(true.B)
      dut.io.wakeUpAll.wakeUpInt(0).bits.pdest.poke(9.U)
      dut.io.wakeUpAll.wakeUpInt(0).bits.rcDest.poke(5.U)
      dut.clock.step()
      dut.io.fromRename(0).valid.poke(false.B)
      dut.io.wakeUpAll.wakeUpInt(0).valid.poke(false.B)

      // preg 9 已唤醒并命中 RegCache slot5；preg 20 尚未缓存。
      selectIQs(dut, Seq(FuType.alu))
      driveUop(
        dut,
        0,
        FuType.alu,
        pdest   = 12,
        psrc    = Seq(9, 20, 0),
        srcType = Seq(SrcType.integer, SrcType.integer, SrcType.none),
      )
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.srcState(0).expect(SourceState.ready.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.useRegCache(0).expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.regCacheIdx(0).expect(5.U)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.useRegCache(1).expect(false.B)

      dut.io.wakeUpAll.wakeUpInt(0).valid.poke(true.B)
      dut.io.wakeUpAll.wakeUpInt(0).bits.rfWen.poke(true.B)
      dut.io.wakeUpAll.wakeUpInt(0).bits.pdest.poke(20.U)
      dut.io.wakeUpAll.wakeUpInt(0).bits.rcDest.poke(6.U)
      dut.clock.step()
      dut.io.fromRename(0).valid.poke(false.B)
      dut.io.wakeUpAll.wakeUpInt(0).valid.poke(false.B)

      // tag=20 已写入 slot6，再查询应命中。
      selectIQs(dut, Seq(FuType.alu))
      driveUop(
        dut,
        0,
        FuType.alu,
        pdest   = 13,
        psrc    = Seq(20, 0, 0),
        srcType = Seq(SrcType.integer, SrcType.immediate, SrcType.none),
      )
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.useRegCache(0).expect(true.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).bits.regCacheIdx(0).expect(6.U)
    }
  }

  it should "enqueue eliminated and exceptional uops into ROB but not into IQ" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)
      selectIQs(dut, Seq.fill(2)(FuType.alu))
      driveUop(dut, 0, FuType.alu, pdest = 5, eliminatedMove = true)
      driveUop(dut, 1, FuType.alu, pdest = 6, exception      = true)

      dut.io.enqRob.needAlloc(0).expect(true.B)
      dut.io.enqRob.req(0).valid.expect(true.B)
      dut.io.enqRob.req(1).valid.expect(true.B)
      for (port <- 0 until iqSum) {
        dut.io.toIssueQueues(port).valid.expect(false.B)
      }
    }
  }

  it should "block on redirect, clear uopSelIQ and recover LSQ acceptance" in {
    test(new AnsDispatch(params)) { dut =>
      resetDispatch(dut)
      selectIQs(dut, Seq(FuType.alu))
      dut.io.debug.uopSelIQ(0)(AnsDispatchParams.intIQ0).expect(true.B)

      driveUop(dut, 0, FuType.alu, pdest = 1)
      dut.io.redirect.valid.poke(true.B)
      dut.io.redirect.bits.flushItself.poke(true.B)
      dut.io.fromRename(0).ready.expect(false.B)
      dut.io.toIssueQueues(enqPort(AnsDispatchParams.intIQ0)).valid.expect(false.B)
      dut.clock.step()

      dut.io.redirect.valid.poke(false.B)
      dut.io.fromRename(0).valid.poke(false.B)
      dut.clock.step()
      dut.io.debug.uopSelIQ(0).foreach(_.expect(false.B))

      // LsqEnqCtrl 在 redirect 后一拍关闭，然后恢复（t1/t2/t3 取消链）。
      dut.clock.step()
      dut.io.debug.lsqCanAccept.expect(false.B)
      dut.clock.step()
      dut.io.debug.lsqCanAccept.expect(true.B)
    }
  }

  it should "carry integer and floating dependency ages into dispatched uops and revoke cancelled ready" in {
    test(new AnsDispatch(params)) { dut =>
      for (floating <- Seq(false, true)) {
        resetDispatch(dut)
        val fu  = if (floating) FuType.falu else FuType.alu
        val st  = if (floating) SrcType.floating else SrcType.integer
        selectIQs(dut, Seq(fu))
        driveUop(dut, 0, fu, pdest = 9, intWen = !floating, fpWen = floating)
        dut.clock.step(); dut.io.fromRename(0).valid.poke(false.B)
        val w   = if (floating) dut.io.wakeUpAll.wakeUpFp(0) else dut.io.wakeUpAll.wakeUpInt(0)
        w.valid.poke(true.B); w.bits.rfWen.poke((!floating).B); w.bits.fpWen.poke(floating.B)
        w.bits.pdest.poke(9.U); w.bits.loadDependency(0).poke(1.U)
        // Selection of the next dispatch group overlaps the wakeup, preserving dependency age.
        selectIQs(dut, Seq(fu)); w.valid.poke(false.B)
        driveUop(
          dut,
          0,
          fu,
          pdest                    = 10,
          psrc                     = Seq(9, 0, 0),
          srcType                  = Seq(st, SrcType.immediate, SrcType.none),
          intWen                   = !floating,
          fpWen                    = floating,
        )
        val out = dut.io.toIssueQueues(enqPort(if (floating) AnsDispatchParams.fpIQ0 else AnsDispatchParams.intIQ0))
        out.valid.expect(true.B)
        out.bits.srcState(0).expect(SourceState.ready.U)
        out.bits.srcLoadDependency(0)(0).expect(2.U)
        out.bits.srcLoadDependency(1)(0).expect(0.U)
        dut.io.ldCancel(0).second.poke(true.B)
        out.bits.srcState(0).expect(SourceState.waiting.U)
        if (!floating) out.bits.useRegCache(0).expect(false.B)
        dut.clock.step(); dut.io.fromRename(0).valid.poke(false.B)
        dut.io.ldCancel(0).second.poke(false.B)
        val wb  = if (floating) dut.io.wbPregsFp(0) else dut.io.wbPregsInt(0)
        wb.valid.poke(true.B); wb.bits.poke(9.U)
        selectIQs(dut, Seq(fu)); wb.valid.poke(false.B)
        driveUop(
          dut,
          0,
          fu,
          pdest                    = 11,
          psrc                     = Seq(9, 0, 0),
          srcType                  = Seq(st, SrcType.immediate, SrcType.none),
          intWen                   = !floating,
          fpWen                    = floating,
        )
        out.bits.srcState(0).expect(SourceState.ready.U)
        out.bits.srcLoadDependency(0)(0).expect(0.U)
      }
    }
  }
}
