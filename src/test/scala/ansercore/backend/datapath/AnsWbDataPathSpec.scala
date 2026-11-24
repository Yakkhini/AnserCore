package ansercore.backend.datapath

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ansercore.backend.Bundles._
import ansercore.backend.issue.{AnsIssueParams, IssueTestUtils}
import IssueTestUtils.zero

class AnsWbDataPathSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsWbDataPath"
  it should "write fixed and uncertain results together and preserve completion metadata without requiring PRF writes" in {
    test(new AnsWbDataPath()) { d =>
      zero(d.io.flush); zero(d.io.fromExu); zero(d.io.fromLsu)
      d.io.fromUncertain.valid.poke(false.B); zero(d.io.fromUncertain.bits)
      val fixed = d.io.fromExu(1)
      fixed.valid.poke(true.B); fixed.bits.fuType.poke(FuType.mul.U)
      fixed.bits.intWen.poke(true.B); fixed.bits.pdest.poke(10.U)
      fixed.bits.robIdx.poke(3.U); fixed.bits.data(0).poke(99.U)
      val u     = d.io.fromUncertain
      u.valid.poke(true.B); u.bits.fuType.poke(FuType.csr.U); u.bits.intWen.poke(true.B)
      u.bits.pdest.poke(11.U); u.bits.robIdx.poke(4.U); u.bits.data(0).poke(123.U)
      u.bits.exceptionBits.poke(8.U); u.bits.commitSelfFlush.poke(true.B); u.bits.replayInst.poke(true.B)
      u.ready.expect(true.B)
      d.io.toIntRf(1).wen.expect(true.B); d.io.toIntRf(1).data.expect(99.U)
      d.io.toIntRf(3).wen.expect(true.B); d.io.toIntRf(3).data.expect(123.U)
      d.io.toRob(1).valid.expect(true.B); d.io.toRob(1).bits.robIdx.expect(3.U)
      val rob   = d.io.toRob(AnsIssueParams.nExu)
      rob.valid.expect(true.B); rob.bits.robIdx.expect(4.U)
      rob.bits.exceptionBits.expect(8.U); rob.bits.flushPipe.expect(true.B); rob.bits.replayInst.expect(true.B)
      for (fu <- Seq(FuType.fence, FuType.csr, FuType.div)) {
        u.bits.fuType.poke(fu.U); u.bits.intWen.poke(false.B)
        d.io.toIntRf(3).wen.expect(false.B); d.io.rcWrite(3).wen.expect(false.B)
        rob.valid.expect(true.B); d.io.toRob(1).valid.expect(true.B)
      }
      d.io.flush.valid.poke(true.B); d.io.flush.bits.robIdx.poke(4.U); d.io.flush.bits.flushItself.poke(true.B)
      rob.valid.expect(false.B); d.io.toRob(1).valid.expect(true.B)
      d.io.toIntRf(4).wen.expect(false.B); d.io.toIntRf(5).wen.expect(false.B)
    }
  }
  it should "assert if two fixed results collide at writeback" in {
    assertThrows[chiseltest.ChiselAssertionError] {
      test(new AnsWbDataPath()) { d =>
        zero(d.io.flush); zero(d.io.fromExu); zero(d.io.fromLsu)
        d.io.fromUncertain.valid.poke(false.B); zero(d.io.fromUncertain.bits)
        for ((e, fu) <- Seq(1 -> FuType.mul, 2 -> FuType.alu)) {
          d.io.fromExu(e).valid.poke(true.B); d.io.fromExu(e).bits.fuType.poke(fu.U)
          d.io.fromExu(e).bits.intWen.poke(true.B)
        }
        d.clock.step()
      }
    }
  }

  it should "reject a live FP write on the integer-only uncertain completion channel" in {
    assertThrows[chiseltest.ChiselAssertionError] {
      test(new AnsWbDataPath()) { d =>
        zero(d.io.flush); zero(d.io.fromExu); zero(d.io.fromLsu)
        zero(d.io.fromUncertain.bits)
        d.io.fromUncertain.valid.poke(true.B)
        d.io.fromUncertain.bits.fpWen.poke(true.B)
        d.clock.step()
      }
    }
  }
}
