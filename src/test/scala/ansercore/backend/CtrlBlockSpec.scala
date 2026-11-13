package ansercore.backend

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.decode.AnsDecodeExceptionBits
import ansercore.backend.dispatch.{AnsDispatchParams, AnsIQWakeUpBundle}
import ansercore.backend.exu.csr.AnsCsrDecodeState
import ansercore.backend.issue.AnsIssueParams
import ansercore.backend.rename._

class CtrlBlockSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams(renameWidth = 4)

  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def fma(rs3: Int, fmt: Int, rs2: Int, rs1: Int, rm: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(rs3) << 27) | (BigInt(fmt) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(rm) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def clearCsr(state: AnsCsrDecodeState): Unit = {
    state.fsOff.poke(false.B)
    state.frmIllegal.poke(false.B)
    state.wfiIllegal.poke(false.B)
    state.wrsNtoIllegal.poke(false.B)
    state.sretIllegal.poke(false.B)
    state.mretIllegal.poke(false.B)
    state.sfenceVmaIllegal.poke(false.B)
    state.cboZeroIllegal.poke(false.B)
    state.cboCleanFlushIllegal.poke(false.B)
    state.cboInvalIllegal.poke(false.B)
  }

  private def clearStatic(inst: StaticInst): Unit = {
    inst.instr.poke(0.U)
    inst.pc.poke(0.U)
    inst.exceptionBits.poke(0.U)
    inst.predictedTaken.poke(false.B)
    inst.isLastInFtqEntry.poke(false.B)
    inst.ftqPtr.value.poke(0.U)
    inst.ftqPtr.flag.poke(false.B)
    inst.ftqOffset.poke(0.U)
  }

  private def clearWakeUp(w: AnsIQWakeUpBundle): Unit = {
    w.rfWen.poke(false.B)
    w.fpWen.poke(false.B)
    w.pdest.poke(0.U)
    w.rcDest.poke(0.U)
    w.is0Lat.poke(false.B)
  }

  private def idle(dut: AnsCtrlBlock): Unit = {
    dut.io.redirect.valid.poke(false.B)
    dut.io.redirect.bits.robIdx.poke(0.U)
    dut.io.redirect.bits.flushItself.poke(false.B)
    dut.io.redirect.bits.isInterrupt.poke(false.B)
    dut.io.disableFusion.poke(false.B)
    clearCsr(dut.io.fromCsr)

    dut.io.rabCommits.isCommit.poke(false.B)
    dut.io.rabCommits.isWalk.poke(false.B)
    for (lane <- 0 until AnsRenameParams.rabCommitWidth) {
      dut.io.rabCommits.commitValid(lane).poke(false.B)
      dut.io.rabCommits.walkValid(lane).poke(false.B)
      dut.io.rabCommits.info(lane).ldest.poke(0.U)
      dut.io.rabCommits.info(lane).pdest.poke(0.U)
      dut.io.rabCommits.info(lane).rfWen.poke(false.B)
      dut.io.rabCommits.info(lane).fpWen.poke(false.B)
      dut.io.rabCommits.info(lane).isMove.poke(false.B)
      dut.io.snpt.flushVec(lane).poke(false.B)
    }
    dut.io.snpt.snptEnq.poke(false.B)
    dut.io.snpt.snptDeq.poke(false.B)
    dut.io.snpt.useSnpt.poke(false.B)
    dut.io.snpt.snptSelect.poke(0.U)
    dut.io.snptLastEnq.valid.poke(false.B)
    dut.io.snptLastEnq.bits.poke(0.U)
    dut.io.snptIsFull.poke(false.B)

    for (lane <- 0 until params.renameWidth) {
      dut.io.in(lane).valid.poke(false.B)
      clearStatic(dut.io.in(lane).bits)
    }

    // Dispatch 下游（IQ / ROB / LSQ / 唤醒）默认空闲。
    for (port <- 0 until AnsDispatchParams.IQEnqSum) {
      dut.io.toIssueQueues(port).ready.poke(true.B)
    }
    for (exu <- 0 until AnsIssueParams.nExu) {
      dut.io.IQValidNumVec(exu).poke(0.U)
    }
    dut.io.enqRob.canAccept.poke(true.B)
    dut.io.enqRob.canAcceptForDispatch.poke(true.B)
    dut.io.enqRob.isEmpty.poke(true.B)
    dut.io.lsqEnq.canAccept.poke(true.B)
    for (lane <- 0 until params.renameWidth) {
      dut.io.lsqEnq.resp(lane).lqIdx.poke(0.U)
      dut.io.lsqEnq.resp(lane).sqIdx.poke(0.U)
    }
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
      dut.io.s0Cancel(port).intWen.poke(false.B)
      dut.io.s0Cancel(port).fpWen.poke(false.B)
      dut.io.s0Cancel(port).pdest.poke(0.U)
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

  private def resetBackend(dut: AnsCtrlBlock): Unit = {
    dut.reset.poke(true.B)
    idle(dut)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step(4)
  }

  private def drive(dut: AnsCtrlBlock, lane: Int, instr: BigInt, pc: Int = 0x1000): Unit = {
    dut.io.in(lane).valid.poke(true.B)
    clearStatic(dut.io.in(lane).bits)
    dut.io.in(lane).bits.instr.poke(instr.U)
    dut.io.in(lane).bits.pc.poke(pc.U)
    dut.io.in(lane).bits.ftqPtr.value.poke(1.U)
    dut.io.in(lane).bits.ftqPtr.flag.poke(false.B)
    dut.io.in(lane).bits.ftqOffset.poke(lane.U)
  }

  behavior of "AnsCtrlBlock"

  it should "accept a group at T0 and expose the T1 result at T1" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      val addi = iType(11, 0, 0, 1, 0x13)
      drive(dut, 0, addi, pc = 0x2000)
      dut.io.in(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)

      dut.io.debug.renameInValid(0).expect(true.B)
      dut.io.debug.renameIn(0).instr.expect(addi.U)
      dut.io.debug.renameIn(0).pc.expect(0x2000.U)
      dut.io.debug.renameIn(0).fuOpType.expect(ALUOpType.add)

      dut.clock.step()
      dut.io.debug.renameInValid(0).expect(false.B)
    }
  }

  it should "drive both RAT banks and the FMA third source from the decoded group" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, fma(13, 0, 12, 11, 0, 10, 0x43))
      dut.io.debug.intRat(0)(0).addr.expect(11.U)
      dut.io.debug.intRat(0)(1).addr.expect(12.U)
      dut.io.debug.fpRat(0)(0).addr.expect(11.U)
      dut.io.debug.fpRat(0)(1).addr.expect(12.U)
      dut.io.debug.fpRat(0)(2).addr.expect(13.U)

      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)
      dut.io.debug.renameIn(0).logicalSrc(2).expect(13.U)
      dut.io.debug.renameIn(0).srcType(2).expect(SrcType.floating.U)
    }
  }

  it should "hold the T1 group under renamePipe backpressure and release it" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      val addi = iType(1, 0, 0, 1, 0x13)

      // 先填满 renamePipe，使 Dispatch 反压传递到 T1。
      dut.io.enqRob.canAcceptForDispatch.poke(false.B)
      drive(dut, 0, addi)
      dut.clock.step()
      drive(dut, 0, addi)
      dut.clock.step()

      dut.io.debug.t1Valid(0).expect(true.B)
      dut.io.debug.renameInValid(0).expect(true.B)
      dut.io.debug.renameInReady(0).expect(false.B)
      dut.io.stallReason.downstream.expect(true.B)
      dut.io.debug.dispatch.toIssueQueuesValid(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ0)).expect(false.B)

      dut.io.enqRob.canAcceptForDispatch.poke(true.B)
      dut.io.in(0).valid.poke(false.B)
      dut.io.debug.renameInReady(0).expect(true.B)
      dut.clock.step()
      dut.io.debug.t1Valid(0).expect(false.B)
      dut.io.debug.renameInValid(0).expect(false.B)
    }
  }

  it should "fuse a safe same-block pair and expose fusion metadata" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, iType(1, 6, 1, 5, 0x13))    // slli x5, x6, 1
      drive(dut, 1, rType(0, 7, 5, 0, 5, 0x33)) // add x5, x5, x7
      dut.clock.step()
      dut.io.in.foreach(_.valid.poke(false.B))

      dut.io.debug.renameInValid(0).expect(true.B)
      dut.io.debug.renameInValid(1).expect(false.B)
      dut.io.debug.renameIn(0).fuType.expect(FuType.bku.U)
      dut.io.debug.renameIn(0).fuOpType.expect(BKUOpType.sh1add)
      dut.io.debug.renameIn(0).logicalSrc(1).expect(7.U)
      dut.io.debug.renameIn(0).srcType(1).expect(SrcType.integer.U)
      dut.io.debug.fusionInfo(0).rs2FromRs2.expect(true.B)
    }
  }

  it should "disable fusion and flush T1 on redirect" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      dut.io.disableFusion.poke(true.B)
      drive(dut, 0, iType(1, 6, 1, 5, 0x13))
      drive(dut, 1, rType(0, 7, 5, 0, 5, 0x33))
      dut.clock.step()
      dut.io.in.foreach(_.valid.poke(false.B))

      dut.io.debug.renameInValid(0).expect(true.B)
      dut.io.debug.renameInValid(1).expect(true.B)
      dut.io.debug.renameIn(0).fuOpType.expect(ALUOpType.slli)
      dut.io.debug.fusionInfo(0).rs2FromRs2.expect(false.B)

      dut.io.redirect.valid.poke(true.B)
      dut.io.redirect.bits.robIdx.poke(0.U)
      dut.io.redirect.bits.flushItself.poke(true.B)
      dut.io.stallReason.redirect.expect(true.B)
      dut.clock.step()
      dut.io.redirect.valid.poke(false.B)

      dut.io.debug.t1Valid.foreach(_.expect(false.B))
      dut.io.debug.renameInValid.foreach(_.expect(false.B))
    }
  }

  it should "hold RAT requests while a group is stalled" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      dut.io.enqRob.canAcceptForDispatch.poke(false.B)

      // 第一组进入 renamePipe 后阻塞第二组的 Rename。
      drive(dut, 0, iType(1, 0, 0, 1, 0x13))
      dut.clock.step()
      drive(dut, 0, iType(3, 4, 0, 2, 0x13))
      dut.clock.step()

      dut.io.debug.intRat(0)(0).hold.expect(true.B)
      dut.io.debug.fpRat(0)(0).hold.expect(true.B)
      dut.io.debug.intRat(0)(0).addr.expect(4.U)

      dut.io.redirect.valid.poke(true.B)
      dut.io.redirect.bits.flushItself.poke(true.B)
      dut.clock.step()
      dut.io.redirect.valid.poke(false.B)
      dut.io.debug.intRat(0)(0).hold.expect(false.B)
    }
  }

  it should "propagate exceptions and report the oldest firing trap" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      dut.io.enqRob.canAcceptForDispatch.poke(false.B)

      drive(dut, 0, iType(1, 0, 0, 1, 0x13))
      dut.clock.step()
      drive(dut, 0, BigInt("10500073", 16)) // WFI
      drive(dut, 1, 0)                      // table miss -> illegal
      dut.io.fromCsr.wfiIllegal.poke(true.B)
      dut.clock.step()
      dut.io.in.foreach(_.valid.poke(false.B))

      val lane0Exception = dut.io.debug.renameIn(0).exceptionBits.peek().litValue
      (lane0Exception & (BigInt(1) << AnsDecodeExceptionBits.illegalInstruction)) shouldBe 1
      dut.io.trapInstInfo.valid.expect(false.B)

      dut.io.enqRob.canAcceptForDispatch.poke(true.B)
      dut.io.trapInstInfo.valid.expect(true.B)
      dut.io.trapInstInfo.bits.instr.expect(BigInt("10500073", 16).U)
    }
  }

  it should "carry a frontend exception through decode into T1" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, iType(1, 0, 0, 1, 0x13))
      dut.io.in(0).bits.exceptionBits.poke((BigInt(1) << FetchExceptionBits.misaligned).U)
      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)

      val bits     = dut.io.debug.renameIn(0).exceptionBits.peek().litValue
      val expected = BigInt(1) << AnsDecodeExceptionBits.fetchMisaligned
      (bits & expected) shouldBe expected
    }
  }

  it should "rename a decode group end to end and bypass a same-group producer" in {
    test(new AnsCtrlBlock(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, iType(1, 0, 0, 1, 0x13), pc = 0x1000) // addi x1, x0, 1
      drive(dut, 1, iType(2, 1, 0, 2, 0x13), pc = 0x1004) // addi x2, x1, 2
      dut.clock.step()
      dut.io.in.foreach(_.valid.poke(false.B))
      dut.clock.step()

      dut.io.toIssueQueues(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ0)).valid.expect(true.B)
      dut.io.toIssueQueues(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ0)).bits.pdest.expect(1.U)
      dut.io.toIssueQueues(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ1)).valid.expect(true.B)
      dut.io.toIssueQueues(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ1)).bits.psrc(0).expect(1.U)
      dut.io.toIssueQueues(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ1)).bits.pdest.expect(2.U)
    }
  }
}
