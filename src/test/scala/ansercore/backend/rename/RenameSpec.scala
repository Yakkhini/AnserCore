package ansercore.backend.rename

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._

class RenameSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams()

  private def zeroDecodedBits(bits: DecodedInst): Unit = {
    bits.instr.poke(0.U)
    bits.pc.poke(0.U)
    bits.exceptionBits.poke(0.U)
    bits.predictedTaken.poke(false.B)
    bits.ftqPtr.flag.poke(false.B)
    bits.ftqPtr.value.poke(0.U)
    bits.ftqOffset.poke(0.U)
    bits.isLastInFtqEntry.poke(false.B)
    for (i <- 0 until params.numSrc) {
      bits.srcType(i).poke(0.U)
      bits.logicalSrc(i).poke(0.U)
    }
    bits.logicalDest.poke(0.U)
    bits.fuType.poke(0.U)
    bits.fuOpType.poke(0.U)
    bits.intWen.poke(false.B)
    bits.fpWen.poke(false.B)
    bits.waitForward.poke(false.B)
    bits.blockBackward.poke(false.B)
    bits.commitSelfFlush.poke(false.B)
    bits.canRobCompress.poke(false.B)
    bits.immType.poke(0.U)
    bits.imm.poke(0.U)
    bits.isMove.poke(false.B)
    bits.commitType.poke(0.U)
    bits.needsRoundingMode.poke(false.B)
    bits.rm.poke(0.U)
  }

  private def zeroDecoded(dut: AnsRename, lane: Int): Unit = zeroDecodedBits(dut.io.in(lane).bits)

  private def zeroStaticBits(bits: StaticInst): Unit = {
    bits.instr.poke(0.U)
    bits.pc.poke(0.U)
    bits.exceptionBits.poke(0.U)
    bits.predictedTaken.poke(false.B)
    bits.ftqPtr.flag.poke(false.B)
    bits.ftqPtr.value.poke(0.U)
    bits.ftqOffset.poke(0.U)
    bits.isLastInFtqEntry.poke(false.B)
  }

  private def deassertIn(dut: AnsRename): Unit =
    for (lane <- 0 until params.renameWidth) dut.io.in(lane).valid.poke(false.B)

  private def idle(dut: AnsRename): Unit = {
    dut.io.redirect.valid.poke(false.B)
    dut.io.redirect.bits.robIdx.poke(0.U)
    dut.io.redirect.bits.flushItself.poke(false.B)
    dut.io.redirect.bits.isInterrupt.poke(false.B)

    dut.io.rabCommits.isCommit.poke(false.B)
    dut.io.rabCommits.isWalk.poke(false.B)
    for (i <- 0 until AnsRenameParams.rabCommitWidth) {
      dut.io.rabCommits.commitValid(i).poke(false.B)
      dut.io.rabCommits.walkValid(i).poke(false.B)
      dut.io.rabCommits.info(i).ldest.poke(0.U)
      dut.io.rabCommits.info(i).pdest.poke(0.U)
      dut.io.rabCommits.info(i).rfWen.poke(false.B)
      dut.io.rabCommits.info(i).fpWen.poke(false.B)
      dut.io.rabCommits.info(i).isMove.poke(false.B)
      dut.io.snpt.flushVec(i).poke(false.B)
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
      dut.io.out(lane).ready.poke(true.B)
      zeroDecoded(dut, lane)
      for (port <- 0 until AnsRenameParams.intReadPorts) {
        dut.io.intReadPorts(lane)(port).hold.poke(false.B)
        dut.io.intReadPorts(lane)(port).addr.poke(0.U)
      }
      for (port <- 0 until AnsRenameParams.fpReadPorts) {
        dut.io.fpReadPorts(lane)(port).hold.poke(false.B)
        dut.io.fpReadPorts(lane)(port).addr.poke(0.U)
      }
    }
    for (lane <- 0 until params.renameWidth - 1) {
      dut.io.fusionInfo(lane).rs2FromRs1.poke(false.B)
      dut.io.fusionInfo(lane).rs2FromRs2.poke(false.B)
      dut.io.fusionInfo(lane).rs2FromZero.poke(false.B)
    }
  }

  private def intInstruction(
    dut:    AnsRename,
    lane:   Int,
    dest:   Int,
    src0:   Int     = 0,
    move:   Boolean = false,
    source: Boolean = true,
  ): Unit = {
    dut.io.in(lane).valid.poke(true.B)
    dut.io.in(lane).bits.intWen.poke(true.B)
    dut.io.in(lane).bits.logicalDest.poke(dest.U)
    if (source) {
      dut.io.in(lane).bits.srcType(0).poke(SrcType.integer.U)
      dut.io.in(lane).bits.logicalSrc(0).poke(src0.U)
    }
    dut.io.in(lane).bits.isMove.poke(move.B)
    dut.io.in(lane).bits.fuType.poke(FuType.alu.U)
  }

  private def fpInstruction(dut: AnsRename, lane: Int, dest: Int, src0: Int): Unit = {
    dut.io.in(lane).valid.poke(true.B)
    dut.io.in(lane).bits.fpWen.poke(true.B)
    dut.io.in(lane).bits.logicalDest.poke(dest.U)
    dut.io.in(lane).bits.srcType(0).poke(SrcType.floating.U)
    dut.io.in(lane).bits.logicalSrc(0).poke(src0.U)
    dut.io.in(lane).bits.fuType.poke(FuType.falu.U)
  }

  private def branchInstruction(dut: AnsRename, lane: Int): Unit = {
    dut.io.in(lane).valid.poke(true.B)
    dut.io.in(lane).bits.fuType.poke(FuType.brh.U)
    dut.io.in(lane).bits.canRobCompress.poke(false.B)
  }

  private def intCommit(
    dut:   AnsRename,
    lane:  Int,
    dest:  Int,
    pdest: Int,
    move:  Boolean = false,
  ): Unit = {
    dut.io.rabCommits.commitValid(lane).poke(true.B)
    dut.io.rabCommits.info(lane).ldest.poke(dest.U)
    dut.io.rabCommits.info(lane).pdest.poke(pdest.U)
    dut.io.rabCommits.info(lane).rfWen.poke(true.B)
    dut.io.rabCommits.info(lane).isMove.poke(move.B)
  }

  behavior of "AnsRename"

  it should "bypass the youngest same-cycle writer" in {
    test(new AnsRename(params)) { dut =>
      idle(dut)
      dut.clock.step(4)

      intInstruction(dut, lane = 0, dest = 1)
      intInstruction(dut, lane = 1, dest = 1)
      intInstruction(dut, lane = 2, dest = 3, src0 = 1)

      dut.io.out(0).bits.pdest.expect(1.U)
      dut.io.out(1).bits.pdest.expect(2.U)
      dut.io.out(2).bits.psrc(0).expect(2.U) // lane1 is younger than lane0
      dut.io.out(2).bits.pdest.expect(3.U)
      dut.io.out(2).valid.expect(true.B)

      dut.clock.step()
      deassertIn(dut)
      dut.clock.step()
      dut.io.debugIntSpec(1).expect(2.U)
      dut.io.debugIntSpec(3).expect(3.U)
      dut.io.debugIntFreeCount.expect(176.U) // 179 - 3
    }
  }

  it should "eliminate a move chain and free a shared physical register only once" in {
    test(new AnsRename(params)) { dut =>
      idle(dut)
      dut.clock.step(4)

      intInstruction(dut, lane = 0, dest = 1)
      intInstruction(dut, lane = 1, dest = 2, src0 = 1, move = true)
      intInstruction(dut, lane = 2, dest = 3, src0 = 2)

      dut.io.out(0).bits.pdest.expect(1.U)
      dut.io.out(1).bits.psrc(0).expect(1.U)
      dut.io.out(1).bits.pdest.expect(1.U)
      dut.io.out(1).bits.eliminatedMove.expect(true.B)
      dut.io.out(2).bits.psrc(0).expect(1.U) // bypass through the eliminated move
      dut.io.out(2).bits.pdest.expect(2.U)
      dut.clock.step()
      deassertIn(dut)
      dut.clock.step()

      dut.io.debugIntSpec(1).expect(1.U)
      dut.io.debugIntSpec(2).expect(1.U)
      dut.io.debugIntSpec(3).expect(2.U)
      dut.io.debugIntFreeCount.expect(177.U) // only two allocations

      idle(dut)
      dut.io.rabCommits.isCommit.poke(true.B)
      intCommit(dut, 0, dest = 1, pdest = 1)
      intCommit(dut, 1, dest = 2, pdest = 1, move = true)
      intCommit(dut, 2, dest = 3, pdest = 2)
      dut.clock.step()

      idle(dut)
      intInstruction(dut, lane = 0, dest = 1)
      intInstruction(dut, lane = 1, dest = 2)
      dut.io.out(0).bits.pdest.expect(3.U)
      dut.io.out(1).bits.pdest.expect(4.U)
      dut.clock.step()
      deassertIn(dut)
      dut.clock.step()
      dut.io.debugIntFreeCount.expect(175.U)

      idle(dut)
      dut.io.rabCommits.isCommit.poke(true.B)
      intCommit(dut, 0, dest = 1, pdest = 3)
      intCommit(dut, 1, dest = 2, pdest = 4)
      dut.clock.step()
      idle(dut)
      dut.clock.step(3)

      dut.io.debugIntRat(1).expect(3.U)
      dut.io.debugIntRat(2).expect(4.U)
      // p1 was shared by x1 and x2; the duplicate lane must not free it twice.
      dut.io.debugIntFreeCount.expect(176.U)
    }
  }

  it should "rename floating-point sources and bypass the same-cycle producer" in {
    test(new AnsRename(params)) { dut =>
      idle(dut)
      dut.clock.step(4)

      // Decode drives RAT addresses one cycle before the instruction group.
      dut.io.fpReadPorts(0)(0).addr.poke(2.U)
      dut.clock.step()

      fpInstruction(dut, lane = 0, dest = 1, src0 = 2)
      fpInstruction(dut, lane = 1, dest = 2, src0 = 1)

      dut.io.out(0).bits.psrc(0).expect(2.U)
      dut.io.out(0).bits.pdest.expect(32.U)
      dut.io.out(1).bits.psrc(0).expect(32.U)
      dut.io.out(1).bits.pdest.expect(33.U)
      dut.clock.step()
      deassertIn(dut)
      dut.clock.step()

      dut.io.debugFpSpec(1).expect(32.U)
      dut.io.debugFpSpec(2).expect(33.U)
      dut.io.debugFpFreeCount.expect(126.U)
    }
  }

  it should "generate a CFI snapshot, restore it on redirect, and replay with walk" in {
    test(new AnsRename(params)) { dut =>
      idle(dut)
      dut.clock.step(4)

      intInstruction(dut, lane    = 0, dest = 1)
      branchInstruction(dut, lane = 1)
      dut.io.out(0).bits.pdest.expect(1.U)
      dut.io.out(1).bits.robIdx.expect(1.U)
      dut.clock.step()
      deassertIn(dut)
      dut.clock.step()

      dut.io.debugIntSpec(1).expect(1.U)
      dut.io.debugIntFreeCount.expect(178.U)

      // Redirect to the CFI and restore the pre-group snapshot (slot 0).
      dut.io.redirect.valid.poke(true.B)
      dut.io.redirect.bits.robIdx.poke(1.U)
      dut.io.redirect.bits.flushItself.poke(true.B)
      dut.io.snpt.useSnpt.poke(true.B)
      dut.io.snpt.snptSelect.poke(0.U)
      dut.clock.step()
      idle(dut)
      dut.clock.step(2)

      dut.io.debugIntSpec(1).expect(0.U)
      dut.io.debugIntFreeCount.expect(179.U)

      // Walk replays the committed x1 -> p1 mapping.
      dut.io.rabCommits.isWalk.poke(true.B)
      dut.io.rabCommits.walkValid(0).poke(true.B)
      dut.io.rabCommits.info(0).ldest.poke(1.U)
      dut.io.rabCommits.info(0).pdest.poke(1.U)
      dut.io.rabCommits.info(0).rfWen.poke(true.B)
      dut.io.stallReason.walk.expect(true.B)
      dut.clock.step()
      idle(dut)
      dut.clock.step()

      dut.io.debugIntSpec(1).expect(1.U)
      dut.io.debugIntFreeCount.expect(178.U)
    }
  }

  it should "restore the architectural state on a redirect without a snapshot" in {
    test(new AnsRename(params)) { dut =>
      idle(dut)
      dut.clock.step(4)

      intInstruction(dut, lane = 0, dest = 1)
      dut.io.out(0).bits.pdest.expect(1.U)
      dut.clock.step()
      deassertIn(dut)
      dut.clock.step()

      idle(dut)
      dut.io.rabCommits.isCommit.poke(true.B)
      intCommit(dut, 0, dest = 1, pdest = 1)
      dut.clock.step()
      idle(dut)
      dut.clock.step()

      intInstruction(dut, lane = 0, dest = 1)
      dut.io.out(0).bits.pdest.expect(2.U)
      dut.clock.step()
      deassertIn(dut)
      dut.clock.step()
      dut.io.debugIntSpec(1).expect(2.U)
      dut.io.debugIntFreeCount.expect(177.U)

      dut.io.redirect.valid.poke(true.B)
      dut.io.redirect.bits.robIdx.poke(0.U)
      dut.io.redirect.bits.flushItself.poke(true.B)
      dut.clock.step()
      idle(dut)
      dut.clock.step(2)

      dut.io.debugIntSpec(1).expect(1.U)
      dut.io.debugIntRat(1).expect(1.U)
      dut.io.debugIntFreeCount.expect(178.U)
    }
  }

  it should "hold a group under downstream backpressure without allocating" in {
    test(new AnsRename(params)) { dut =>
      idle(dut)
      dut.clock.step(4)

      intInstruction(dut, lane = 0, dest = 1)
      dut.io.out(0).ready.poke(false.B)
      dut.io.in(0).ready.expect(false.B)
      dut.io.out(0).valid.expect(true.B)
      dut.io.stallReason.downstream.expect(true.B)
      dut.clock.step()
      dut.io.debugIntFreeCount.expect(179.U)

      dut.io.out(0).ready.poke(true.B)
      dut.io.in(0).ready.expect(true.B)
      dut.io.stallReason.downstream.expect(false.B)
      dut.clock.step()
      dut.io.debugIntFreeCount.expect(178.U)
    }
  }

  behavior of "AnsRenameTable"

  it should "read at T1, bypass the current write, and honor hold" in {
    test(new AnsRenameTable(isInteger = true, params)) { dut =>
      dut.io.redirect.poke(false.B)
      dut.io.snpt.snptEnq.poke(false.B)
      dut.io.snpt.snptDeq.poke(false.B)
      dut.io.snpt.useSnpt.poke(false.B)
      dut.io.snpt.snptSelect.poke(0.U)
      for (i <- 0 until AnsRenameParams.renameSnapshotNum) { dut.io.snpt.flushVec(i).poke(false.B) }
      for (p <- dut.io.readPorts) { p.hold.poke(false.B); p.addr.poke(0.U) }
      for (p <- dut.io.specWritePorts) { p.wen.poke(false.B); p.addr.poke(0.U); p.data.poke(0.U) }
      for (p <- dut.io.archWritePorts) { p.wen.poke(false.B); p.addr.poke(0.U); p.data.poke(0.U) }

      dut.io.readPorts(0).addr.poke(1.U)
      dut.clock.step()
      dut.io.readPorts(0).data.expect(0.U)

      dut.io.specWritePorts(0).wen.poke(true.B)
      dut.io.specWritePorts(0).addr.poke(1.U)
      dut.io.specWritePorts(0).data.poke(5.U)
      dut.clock.step()
      dut.io.specWritePorts(0).wen.poke(false.B)
      dut.io.readPorts(0).data.expect(5.U) // T1 write bypass

      dut.clock.step()
      dut.io.readPorts(0).data.expect(5.U) // table updated at the same edge

      dut.io.readPorts(0).hold.poke(true.B)
      dut.io.readPorts(0).addr.poke(2.U)
      dut.clock.step()
      dut.io.readPorts(0).data.expect(5.U) // held address still reads x1
    }
  }

  behavior of "AnsCompressUnit"

  it should "compress contiguous lanes and stop at a fetch-block boundary" in {
    test(new AnsCompressUnit(params)) { dut =>
      for (lane <- 0 until params.renameWidth) {
        dut.io.in(lane).valid.poke(true.B)
        zeroDecodedBits(dut.io.in(lane).bits)
        dut.io.in(lane).bits.canRobCompress.poke(true.B)
        dut.io.in(lane).bits.fuType.poke(FuType.alu.U)
      }
      dut.io.in(1).bits.isLastInFtqEntry.poke(true.B)

      dut.io.out.masks(0).expect("b0011".U)
      dut.io.out.masks(1).expect("b0011".U)
      dut.io.out.masks(2).expect("b1100".U)
      dut.io.out.masks(3).expect("b1100".U)
      dut.io.out.instrSizes.foreach(_.expect(2.U))
      dut.io.out.needRobFlags(0).expect(false.B)
      dut.io.out.needRobFlags(1).expect(true.B)
      dut.io.out.needRobFlags(2).expect(false.B)
      dut.io.out.needRobFlags(3).expect(true.B)
    }
  }

  behavior of "AnsSnapshotGenerator"

  it should "report fullness and refuse to overwrite a full queue" in {
    test(new AnsSnapshotGenerator(UInt(8.W))) { dut =>
      dut.io.enq.poke(false.B)
      dut.io.enqData.poke(0.U)
      dut.io.deq.poke(false.B)
      dut.io.redirect.poke(false.B)
      dut.io.flushVec.foreach(_.poke(false.B))

      for (value <- 10 until 14) {
        dut.io.enq.poke(true.B)
        dut.io.enqData.poke(value.U)
        dut.clock.step()
      }
      dut.io.enq.poke(false.B)
      dut.io.full.expect(true.B)
      dut.io.valids.foreach(_.expect(true.B))

      dut.io.enq.poke(true.B)
      dut.io.enqData.poke(99.U)
      dut.clock.step()
      dut.io.enq.poke(false.B)
      dut.io.full.expect(true.B)

      dut.io.deq.poke(true.B)
      dut.clock.step()
      dut.io.deq.poke(false.B)
      dut.io.full.expect(false.B)
      dut.io.enqIndex.expect(0.U)
      dut.io.valids(0).expect(false.B)

      dut.io.enq.poke(true.B)
      dut.io.enqData.poke(20.U)
      dut.clock.step()
      dut.io.enq.poke(false.B)
      dut.io.valids(0).expect(true.B)
    }
  }

  it should "rewind enqPtr after a flushed suffix" in {
    test(new AnsSnapshotGenerator(UInt(8.W))) { dut =>
      dut.io.enq.poke(false.B)
      dut.io.enqData.poke(0.U)
      dut.io.deq.poke(false.B)
      dut.io.redirect.poke(false.B)
      dut.io.flushVec.foreach(_.poke(false.B))

      for (value <- 10 until 14) {
        dut.io.enq.poke(true.B)
        dut.io.enqData.poke(value.U)
        dut.clock.step()
      }

      dut.io.enq.poke(true.B)
      dut.io.enqData.poke(99.U)
      dut.io.redirect.poke(true.B)
      dut.io.flushVec(2).poke(true.B)
      dut.io.flushVec(3).poke(true.B)
      dut.clock.step()

      dut.io.enq.poke(false.B)
      dut.io.redirect.poke(false.B)
      dut.io.flushVec.foreach(_.poke(false.B))
      dut.io.enqIndex.expect(2.U)
      dut.io.valids(0).expect(true.B)
      dut.io.valids(1).expect(true.B)
      dut.io.valids(2).expect(false.B)
      dut.io.valids(3).expect(false.B)
    }
  }
}
