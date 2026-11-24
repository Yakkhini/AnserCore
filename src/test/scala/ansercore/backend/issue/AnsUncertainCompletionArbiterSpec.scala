package ansercore.backend.issue

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ansercore.backend.Bundles._
import IssueTestUtils.zero

class AnsUncertainCompletionArbiterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "single uncertain completion port"
  it should "rotate only on completion, lock under backpressure and release a cancelled winner" in {
    test(new AnsUncertainCompletionArbiter()) { d =>
      zero(d.io.flush)
      d.io.out.ready.poke(false.B)
      for ((in, i) <- d.io.in.zipWithIndex) {
        in.valid.poke(false.B); zero(in.bits)
        in.bits.fuType.poke((if (i == 0) FuType.div else FuType.fence).U)
        in.bits.robIdx.poke((if (i == 0) 12 else 11).U); in.bits.data(0).poke((100 + i).U)
        in.bits.pdest.poke((20 + i).U); in.bits.commitSelfFlush.poke((i == 1).B)
      }
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      // CSR locks while alone; the preferred DIV arriving later cannot change it.
      d.io.in(1).valid.poke(true.B); d.clock.step()
      d.io.in(0).valid.poke(true.B)
      for (_ <- 0 until 5) {
        d.io.out.bits.robIdx.expect(11.U); d.io.out.bits.data(0).expect(101.U)
        d.io.out.bits.pdest.expect(21.U); d.io.out.bits.commitSelfFlush.expect(true.B)
        d.io.in.foreach(_.ready.expect(false.B)); d.clock.step()
      }
      d.io.out.ready.poke(true.B); d.clock.step()  // CSR accepted: DIV gets priority.
      for (k <- 0 until 20) {
        val i = k % 2
        d.io.out.valid.expect(true.B); d.io.out.bits.robIdx.expect((if (i == 0) 12 else 11).U)
        d.io.in(i).ready.expect(true.B); d.io.in(1 - i).ready.expect(false.B)
        d.clock.step()
      }
      d.io.out.ready.poke(false.B); d.clock.step() // lock DIV
      d.io.flush.valid.poke(true.B); d.io.flush.bits.robIdx.poke(12.U)
      d.io.flush.bits.flushItself.poke(true.B)
      d.io.out.bits.robIdx.expect(11.U)            // surviving CSR may immediately take over
      d.clock.step(); d.io.in(0).valid.poke(false.B); zero(d.io.flush)
      d.io.out.bits.robIdx.expect(11.U)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.in(1).valid.poke(false.B)
      d.io.out.valid.expect(false.B)
      // Reset tie favors DIV, including when neither operation writes a register.
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.in.foreach(_.valid.poke(true.B)); d.io.out.bits.fuType.expect(FuType.div.U)
    }
  }
}
