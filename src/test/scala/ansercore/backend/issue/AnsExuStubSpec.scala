package ansercore.backend.issue

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import IssueTestUtils.zero

class AnsExuStubSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsExuBlockStub"

  it should "flush a held uncertain result by its own robIdx" in {
    test(new AnsExuBlockStub()) { dut =>
      dut.io.uncertainIn.foreach { in => in.valid.poke(false.B); zero(in.bits) };
      dut.io.uncertainOut.ready.poke(false.B); zero(dut.io.flush)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(2)

      dut.io.in.foreach { in =>
        in.valid.poke(false.B)
        zero(in.bits)
      }
      dut.io.outReady.foreach(_.poke(true.B))

      val mul0 = AnsIssueParams.exuIdxOf("MUL0")
      // MUL0 的 DIV 进入 uncertain 槽并保持。
      dut.io.outReady(mul0).poke(false.B)
      dut.io.uncertainIn(0).valid.poke(true.B)
      dut.io.uncertainIn(0).bits.fuType.poke(FuType.div.U)
      dut.io.uncertainIn(0).bits.intWen.poke(true.B)
      dut.io.uncertainIn(0).bits.pdest.poke(5.U)
      dut.io.uncertainIn(0).bits.robIdx.poke(2.U)
      dut.io.uncertainIn(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.uncertainIn(0).valid.poke(false.B)
      dut.io.uncertainBusy(mul0).expect(FuType.div.U)
      dut.io.uncertainOut.valid.expect(true.B)

      // redirect 命中该结果自身；即使输出被占用，也必须清槽，不能复活。
      dut.io.flush.valid.poke(true.B)
      dut.io.flush.bits.robIdx.poke(2.U)
      dut.io.flush.bits.flushItself.poke(true.B)
      dut.io.uncertainBusy(mul0).expect(0.U)
      dut.io.uncertainOut.valid.expect(false.B)
      dut.clock.step()
      dut.io.flush.valid.poke(false.B)
      dut.io.uncertainOut.valid.expect(false.B)
      dut.io.uncertainBusy(mul0).expect(0.U)
    }
  }

  it should "replace a flushed uncertain result with a surviving older request" in {
    test(new AnsExuBlockStub()) { dut =>
      dut.io.uncertainIn.foreach { in => in.valid.poke(false.B); zero(in.bits) };
      dut.io.uncertainOut.ready.poke(false.B); zero(dut.io.flush)
      dut.io.in.foreach { in => in.valid.poke(false.B); zero(in.bits) }
      dut.io.outReady.foreach(_.poke(true.B))
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val in = dut.io.uncertainIn(0)
      dut.io.outReady(1).poke(false.B)
      in.valid.poke(true.B); in.bits.fuType.poke(FuType.div.U); in.bits.robIdx.poke(5.U)
      dut.clock.step()
      dut.io.flush.valid.poke(true.B); dut.io.flush.bits.robIdx.poke(5.U)
      dut.io.flush.bits.flushItself.poke(true.B)
      in.bits.robIdx.poke(3.U); in.bits.src(0).poke(9.U); in.bits.src(1).poke(4.U)
      in.ready.expect(true.B); dut.clock.step(); in.valid.poke(false.B); zero(dut.io.flush)
      dut.io.uncertainOut.valid.expect(true.B); dut.io.uncertainOut.bits.robIdx.expect(3.U)
      dut.io.uncertainOut.bits.data(0).expect(13.U)
    }
  }

  it should "assert when a fixed result cannot obtain its write port" in {
    assertThrows[chiseltest.ChiselAssertionError] {
      test(new AnsExuBlockStub()) { dut =>
        dut.io.uncertainIn.foreach { in => in.valid.poke(false.B); zero(in.bits) };
        dut.io.uncertainOut.ready.poke(false.B); zero(dut.io.flush);
        dut.io.in.foreach { in => in.valid.poke(false.B); zero(in.bits) }
        dut.io.outReady.foreach(_.poke(true.B))
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.in(0).valid.poke(true.B); dut.io.in(0).bits.fuType.poke(FuType.alu.U)
        dut.io.outReady(0).poke(false.B); dut.clock.step()
      }
    }
  }
  it should "complete a held DIV alongside consecutive fixed results" in {
    test(new AnsExuBlockStub()) { dut =>
      zero(dut.io.flush)
      (dut.io.in ++ dut.io.uncertainIn).foreach { in => in.valid.poke(false.B); zero(in.bits) }
      dut.io.outReady.foreach(_.poke(true.B)); dut.io.uncertainOut.ready.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val div = dut.io.uncertainIn(0)
      div.valid.poke(true.B); div.bits.fuType.poke(FuType.div.U); div.bits.robIdx.poke(1.U)
      div.bits.intWen.poke(true.B); div.bits.src(0).poke(9.U); div.bits.src(1).poke(4.U)
      div.bits.commitSelfFlush.poke(true.B); div.bits.pdest.poke(17.U)
      dut.clock.step(); div.valid.poke(false.B)
      val in  = dut.io.in(1)
      in.valid.poke(true.B); in.bits.fuType.poke(FuType.mul.U); in.bits.robIdx.poke(2.U); dut.clock.step()
      in.bits.robIdx.poke(3.U); dut.clock.step(); in.valid.poke(false.B)
      dut.io.out(1).bits.robIdx.expect(2.U)
      dut.io.uncertainOut.valid.expect(true.B); dut.io.uncertainOut.bits.robIdx.expect(1.U)
      dut.io.uncertainOut.bits.commitSelfFlush.expect(true.B); dut.io.uncertainOut.bits.pdest.expect(17.U)
      dut.io.uncertainOut.bits.data(0).expect(13.U)
      dut.io.uncertainOut.ready.poke(true.B); dut.clock.step()
      dut.io.out(1).bits.robIdx.expect(3.U); dut.io.uncertainOut.valid.expect(false.B)
    }
  }
}
