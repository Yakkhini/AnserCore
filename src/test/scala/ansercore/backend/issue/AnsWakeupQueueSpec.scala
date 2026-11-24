package ansercore.backend.issue

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import IssueTestUtils.zero

class AnsWakeupQueueSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsWakeupPipe"

  private def init(dut: AnsWakeupPipe): Unit = {
    zero(dut.io.flush)
    dut.io.enq.valid.poke(false.B)
    zero(dut.io.enq.bits)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step(2)
  }

  private def enqueue(dut: AnsWakeupPipe): Unit = {
    dut.io.enq.valid.poke(true.B)
    dut.io.enq.bits.robIdx.poke(3.U)
    dut.io.enq.bits.pdest.poke(7.U)
    dut.io.enq.bits.intWen.poke(true.B)
    dut.io.enq.bits.is0Lat.poke(false.B)
    dut.clock.step()
    dut.io.enq.valid.poke(false.B)
  }

  it should "cancel a depth-1 wakeup when data0Fail is asserted" in {
    test(new AnsWakeupPipe(1)) { dut =>
      init(dut)
      enqueue(dut)
      dut.io.deq.valid.expect(true.B)
      dut.io.flush.data0Fail.poke(true.B)
      dut.io.deq.valid.expect(false.B)
    }
  }

  it should "keep a depth-2 wakeup when data0/data1 do not fail" in {
    test(new AnsWakeupPipe(2)) { dut =>
      init(dut)
      enqueue(dut)
      dut.io.deq.valid.expect(false.B)
      dut.clock.step()
      dut.io.deq.valid.expect(true.B)
    }
  }

  it should "cancel a depth-2 wakeup when data1Fail is asserted at the output stage" in {
    test(new AnsWakeupPipe(2)) { dut =>
      init(dut)
      enqueue(dut)
      dut.io.deq.valid.expect(false.B)
      dut.io.flush.data1Fail.poke(true.B)
      dut.clock.step()
      dut.io.deq.valid.expect(false.B)
    }
  }

  it should "cancel a depth-3 wakeup when data1Fail is asserted at stage 2" in {
    test(new AnsWakeupPipe(3)) { dut =>
      init(dut)
      enqueue(dut)
      dut.clock.step()
      dut.io.flush.data1Fail.poke(true.B)
      dut.clock.step()
      dut.io.deq.valid.expect(false.B)
    }
  }
}

class AnsMultiWakeupQueueSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsMultiWakeupQueue"
  private val p = ansercore.backend.Bundles.BundleParams(loadDependencyWidth = 8)

  it should "register all latency outputs exactly once and shift dependencies to the broadcast age" in {
    test(new AnsMultiWakeupQueue(Set(0, 1, 2, 3), p)) { dut =>
      zero(dut.io.flush); zero(dut.io.enq)
      dut.reset.poke(true.B); dut.clock.step(2); dut.io.deq.valid.expect(false.B)
      dut.reset.poke(false.B)
      for (lat <- 0 to 3) {
        dut.io.enq.valid.poke(true.B)
        dut.io.enq.bits.lat.poke(lat.U)
        dut.io.enq.bits.uop.robIdx.poke(7.U)
        dut.io.enq.bits.uop.pdest.poke((lat + 10).U)
        dut.io.enq.bits.uop.loadDependency(0).poke(2.U) // already aged for data0
        dut.io.enq.bits.uop.is0Lat.poke((lat == 0).B)
        dut.io.deq.valid.expect(false.B)
        dut.clock.step(); dut.io.enq.valid.poke(false.B)
        for (_ <- 0 until lat) { dut.io.deq.valid.expect(false.B); dut.clock.step() }
        dut.io.deq.valid.expect(true.B)
        dut.io.deq.bits.pdest.expect((lat + 10).U)
        dut.io.deq.bits.loadDependency(0).expect((2 << lat).U)
        dut.clock.step(); dut.io.deq.valid.expect(false.B)
      }
    }
  }

  it should "filter pipeline failures and redirects without filtering latency zero by data0" in {
    test(new AnsMultiWakeupQueue(Set(0, 1, 2, 3), p)) { dut =>
      for {
        lat  <- 0 to 3
        mode <- 0 until 4
      } {
        zero(dut.io.flush); zero(dut.io.enq)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.enq.valid.poke(true.B); dut.io.enq.bits.lat.poke(lat.U)
        dut.io.enq.bits.uop.robIdx.poke(7.U)
        dut.io.enq.bits.uop.loadDependency(0).poke(2.U)
        dut.clock.step(); dut.io.enq.valid.poke(false.B)
        if (mode == 0) dut.io.flush.data0Fail.poke(true.B)
        if (mode == 1) dut.io.flush.ldCancel(0).second.poke(true.B)
        if (mode == 2) {
          dut.io.flush.redirect.valid.poke(true.B)
          dut.io.flush.redirect.bits.robIdx.poke(7.U)
          dut.io.flush.redirect.bits.flushItself.poke(true.B)
        }
        if (mode == 3 && lat >= 2) {
          dut.clock.step(); dut.io.flush.data1Fail.poke(true.B)
        }
        if (lat == 0) dut.io.deq.valid.expect((mode != 2).B)
        for (_ <- 0 until 5) {
          dut.clock.step()
          if (mode != 3 || lat >= 2) dut.io.deq.valid.expect(false.B)
        }
        zero(dut.io.flush); dut.clock.step(4); dut.io.deq.valid.expect(false.B)
      }
    }
  }
}
