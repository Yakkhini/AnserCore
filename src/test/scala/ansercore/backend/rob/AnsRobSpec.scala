package ansercore.backend.rob

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._

private class AnsRobPtrProbe(val config: AnsRobParams) extends Module {
  val io     = IO(new Bundle {
    val in          = Input(UInt(config.ptrWidth.W))
    val target      = Input(UInt(config.ptrWidth.W))
    val flushItself = Input(Bool())
    val next        = Output(UInt(config.ptrWidth.W))
    val lineHead    = Output(UInt(config.ptrWidth.W))
    val bank        = Output(UInt(config.bankAddrWidth.W))
    val row         = Output(UInt(config.rowAddrWidth.W))
    val after       = Output(Bool())
    val before      = Output(Bool())
    val flush       = Output(Bool())
  })
  val ptr    = AnsRobPtr.fromUInt(io.in, config)
  val target = AnsRobPtr.fromUInt(io.target, config)
  io.next     := ptr.next.asUInt
  io.lineHead := ptr.lineHeadPtr.asUInt
  io.bank     := ptr.bank
  io.row      := ptr.row
  io.after    := ptr.isAfter(target)
  io.before   := ptr.isBefore(target)
  io.flush    := ptr.needFlush(target, io.flushItself)
}

class AnsRobSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams()
  private val config = AnsRobParams.from(params)

  private def zero(data: Data): Unit = data match {
    case b: Bool   => b.poke(false.B)
    case u: UInt   => u.poke(0.U)
    case v: Vec[_] => v.foreach(zero)
    case b: Bundle => b.elements.values.foreach(zero)
    case other     => fail(s"unsupported test input: $other")
  }

  private def idle(dut: AnsRob): Unit = {
    zero(dut.io.enq.needAlloc)
    zero(dut.io.enq.req)
    zero(dut.io.redirectIn)
    zero(dut.io.writeback)
    dut.io.lsq.mmioBusy.poke(false.B)
    dut.io.csr.intrBitSet.poke(false.B)
    dut.io.csr.trapTarget.poke(0.U)
    dut.io.csr.wfiEvent.poke(false.B)
    dut.io.csr.criticalErrorState.poke(false.B)
  }

  private def resetRob(dut: AnsRob): Unit = {
    idle(dut)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def enqueue(dut: AnsRob, lane: Int, index: Int, size: Int = 1): Unit = {
    dut.io.enq.needAlloc(lane).poke(true.B)
    val req = dut.io.enq.req(lane)
    req.valid.poke(true.B)
    req.bits.robIdx.poke(index.U)
    req.bits.instr.poke((lane + 1).U)
    req.bits.pc.poke((0x1000 + lane * 4).U)
    req.bits.ftqOffset.poke(lane.U)
    req.bits.instrSize.poke(size.U)
    req.bits.intWen.poke(true.B)
    req.bits.fuType.poke(FuType.alu)
  }

  private def expectNoRetirement(dut: AnsRob): Unit = {
    dut.io.commits.isCommit.expect(false.B)
    dut.io.commits.commitValid.foreach(_.expect(false.B))
    dut.io.commits.walkValid.foreach(_.expect(false.B))
    dut.io.rabCommits.commitValid.foreach(_.expect(false.B))
    dut.io.rabCommits.walkValid.foreach(_.expect(false.B))
    dut.io.flushOut.valid.expect(false.B)
    dut.io.exception.valid.expect(false.B)
    dut.io.csr.fflags.valid.expect(false.B)
    dut.io.csr.retiredInstr.expect(0.U)
    dut.io.lsq.commit.expect(false.B)
  }

  behavior of "AnsRobParams"

  it should "reject dimensions unsupported by the pointer and backend interfaces" in {
    intercept[IllegalArgumentException](AnsRobParams(robSize = 96))
    intercept[IllegalArgumentException](AnsRobParams(bankCount = 1))
    intercept[IllegalArgumentException](AnsRobParams(walkWidth = 4))
    intercept[IllegalArgumentException](AnsRobParams.from(params.copy(robIndexWidth = 9)))
    intercept[IllegalArgumentException](AnsRobParams.from(params.copy(renameWidth = 8)))
    config.bankEntries shouldBe 16
    config.ptrWidth shouldBe 8
  }

  behavior of "AnsRobPtr"

  it should "wrap, align bank lines and compare ages across the ring boundary" in {
    test(new AnsRobPtrProbe(config)) { dut =>
      dut.io.target.poke(127.U)
      dut.io.flushItself.poke(false.B)
      dut.io.in.poke(127.U)
      dut.io.next.expect(128.U)
      dut.io.lineHead.expect(120.U)
      dut.io.bank.expect(7.U)
      dut.io.row.expect(15.U)
      dut.io.after.expect(false.B)
      dut.io.before.expect(false.B)
      dut.io.flush.expect(false.B)
      dut.io.flushItself.poke(true.B)
      dut.io.flush.expect(true.B)
      dut.io.in.poke(128.U)
      dut.io.after.expect(true.B)
      dut.io.before.expect(false.B)
      dut.io.flush.expect(true.B)
      dut.io.in.poke(126.U)
      dut.io.after.expect(false.B)
      dut.io.before.expect(true.B)
      dut.io.flush.expect(false.B)
      dut.io.in.poke(255.U)
      dut.io.next.expect(0.U)
      dut.io.lineHead.expect(248.U)
      dut.io.in.poke(0.U)
      dut.io.target.poke(255.U)
      dut.io.after.expect(true.B)
      dut.io.before.expect(false.B)
    }
  }

  behavior of "AnsRobBank"

  it should "store independent rows and give a new allocation priority over a clear" in {
    test(new AnsRobBank(params, config)) { dut =>
      zero(dut.io.clear)
      zero(dut.io.writeData)
      dut.io.writeAddr.poke(0.U)
      dut.io.readAddr.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.readData.valid.expect(false.B)
      for (row <- Seq(0, 7, 15)) {
        dut.io.writeAddr.poke(row.U)
        dut.io.writeData.valid.poke(true.B)
        dut.io.writeData.bits.valid.poke(true.B)
        dut.io.writeData.bits.pc.poke((0x2000 + row * 4).U)
        dut.clock.step()
      }
      dut.io.writeData.valid.poke(false.B)
      for (row <- Seq(0, 7, 15)) {
        dut.io.readAddr.poke(row.U)
        dut.io.readData.valid.expect(true.B)
        dut.io.readData.pc.expect((0x2000 + row * 4).U)
      }
      dut.io.clear(15).poke(true.B)
      dut.clock.step()
      dut.io.readData.valid.expect(false.B)
      dut.io.writeAddr.poke(15.U)
      dut.io.writeData.valid.poke(true.B)
      dut.io.writeData.bits.pc.poke(0x3000.U)
      dut.clock.step()
      dut.io.readData.valid.expect(true.B)
      dut.io.updateData.pc.expect(0x3000.U)
      dut.io.readAddr.poke(7.U)
      dut.io.readData.valid.expect(true.B)
    }
  }

  behavior of "AnsRob"

  it should "accept distinct entries, retain the head and stop at the dispatch reservation" in {
    test(new AnsRob(params)) { dut =>
      resetRob(dut)
      dut.io.enq.isEmpty.expect(true.B)
      dut.io.debug.head.valid.expect(false.B)
      dut.io.snptIsFull.expect(true.B)
      expectNoRetirement(dut)
      for (base <- 0 until 120 by 4) {
        idle(dut)
        for (lane <- 0 until 4) {
          enqueue(dut, lane, base + lane)
          dut.io.enq.resp(lane).expect((base + lane).U)
        }
        dut.io.enq.canAcceptForDispatch.expect(true.B)
        dut.clock.step()
        dut.io.debug.occupancy.expect((base + 4).U)
        dut.io.debug.enqPtr.value.expect((base + 4).U)
      }
      for (lane <- 0 until 4) enqueue(dut, lane, 120 + lane)
      dut.io.enq.canAcceptForDispatch.expect(false.B)
      dut.io.robFull.expect(false.B) // Eight reserved entries remain physically empty.
      dut.clock.step(2)
      dut.io.debug.occupancy.expect(120.U)
      dut.io.debug.head.valid.expect(true.B)
      dut.io.debug.head.robIdx.expect(0.U)
      dut.io.debug.head.pc.expect(0x1000.U)
      dut.io.debug.head.uopNum.expect(1.U)
      expectNoRetirement(dut)
    }
  }

  it should "merge compressed groups within and across dispatch cycles" in {
    test(new AnsRob(params)) { dut =>
      resetRob(dut)
      enqueue(dut, 0, 0, size = 3)
      enqueue(dut, 1, 0, size = 3)
      dut.io.enq.req(1).bits.eliminatedMove.poke(true.B)
      dut.clock.step()
      dut.io.debug.occupancy.expect(1.U)
      dut.io.debug.head.receivedUops.expect(2.U)
      dut.io.debug.head.realDestSize.expect(2.U)
      dut.io.debug.head.uopNum.expect(1.U)
      idle(dut)
      dut.clock.step()
      enqueue(dut, 2, 0, size = 3)
      enqueue(dut, 3, 1)
      dut.clock.step()
      idle(dut)
      dut.io.debug.occupancy.expect(2.U)
      dut.io.debug.enqPtr.value.expect(2.U)
      dut.io.debug.head.receivedUops.expect(3.U)
      dut.io.debug.head.realDestSize.expect(3.U)
      dut.io.debug.head.uopNum.expect(2.U)
      dut.io.debug.head.pc.expect(0x1000.U)
      dut.io.debug.head.instr.expect(1.U)
      dut.io.debug.head.ftqOffset.expect(2.U)
      expectNoRetirement(dut)
    }
  }

  it should "record an enqueue exception without inventing a trap or writeback completion" in {
    test(new AnsRob(params)) { dut =>
      resetRob(dut)
      enqueue(dut, 0, 0)
      dut.io.enq.req(0).bits.exceptionBits.poke(4.U)
      dut.clock.step()
      idle(dut)
      dut.io.debug.head.exceptionBits.expect(4.U)
      dut.io.debug.head.needFlush.expect(true.B)
      dut.io.debug.head.uopNum.expect(0.U)
      dut.io.writeback(0).valid.poke(true.B)
      dut.io.writeback(0).bits.exceptionBits.poke(8.U)
      dut.clock.step()
      dut.io.debug.head.exceptionBits.expect(4.U) // Writeback handling is intentionally deferred.
      expectNoRetirement(dut)
    }
  }

  it should "block enqueue immediately on redirect and wait for real recovery completion" in {
    test(new AnsRob(params)) { dut =>
      resetRob(dut)
      enqueue(dut, 0, 0)
      dut.clock.step()
      idle(dut)
      enqueue(dut, 0, 1)
      dut.io.redirectIn.valid.poke(true.B)
      dut.io.redirectIn.bits.robIdx.poke(0.U)
      dut.io.redirectIn.bits.flushItself.poke(true.B)
      dut.io.enq.canAccept.expect(false.B)
      dut.io.enq.canAcceptForDispatch.expect(false.B)
      dut.io.redirect.valid.expect(true.B)
      dut.clock.step()
      idle(dut)
      dut.clock.step(3)
      dut.io.debug.occupancy.expect(1.U)
      dut.io.commits.isWalk.expect(true.B)
      dut.io.rabCommits.isWalk.expect(true.B)
      dut.io.enq.canAcceptForDispatch.expect(false.B)
      expectNoRetirement(dut)
      resetRob(dut)
      dut.io.enq.isEmpty.expect(true.B)
      dut.io.commits.isWalk.expect(false.B)
    }
  }
}
