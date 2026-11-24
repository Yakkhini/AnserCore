package ansercore.backend.datapath

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.issue.{AnsIssueParams, IssueTestUtils}
import IssueTestUtils.zero

class AnsExuInputPipeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsExuInputPipe"

  it should "not accept a second uncertain operation while the pipe is draining" in {
    // MUL0 同时有固定 MUL 和 uncertain DIV。
    test(new AnsExuInputPipe(BundleParams(), AnsIssueParams.exuByIndex(AnsIssueParams.exuIdxOf("MUL0")))) { dut =>
      dut.io.uncertainOut.foreach(_.ready.poke(false.B)); zero(dut.io.flush)
      dut.io.in.valid.poke(false.B)
      zero(dut.io.in.bits)
      dut.io.out.ready.poke(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(2)

      dut.io.uncertainOut(0).ready.poke(true.B)
      // 第一条 DIV 进入 pipe。
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.fuType.poke(FuType.div.U)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.uncertainOut(0).valid.expect(true.B)
      dut.io.uncertainOut(0).bits.fuType.expect(FuType.div.U)

      // pipe 正在排空 DIV 时，第二条 DIV 不能被接收。
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.fuType.poke(FuType.div.U)
      dut.io.in.ready.expect(false.B)

      // 固定 MUL 仍可通过正在排空的 pipe。
      dut.io.in.bits.fuType.poke(FuType.mul.U)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fuType.expect(FuType.mul.U)
    }
  }

  it should "hold a complete uncertain request while fixed requests pass on consecutive cycles" in {
    test(new AnsExuInputPipe(BundleParams(), AnsIssueParams.exuByIndex(1))) { dut =>
      dut.io.uncertainOut.foreach(_.ready.poke(false.B)); zero(dut.io.flush); zero(dut.io.in.bits);
      dut.io.in.valid.poke(false.B); dut.io.out.ready.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(true.B); dut.io.in.bits.fuType.poke(FuType.div.U)
      dut.io.in.bits.robIdx.poke(3.U); dut.io.in.bits.src(0).poke(123.U)
      dut.io.in.bits.src(1).poke(456.U); dut.clock.step()
      dut.io.in.valid.poke(false.B); dut.io.uncertainBusy.expect(FuType.div.U)
      dut.clock.step(3)
      for (r <- 4 to 6) {
        dut.io.in.valid.poke(true.B); dut.io.in.bits.fuType.poke(FuType.mul.U)
        dut.io.in.bits.robIdx.poke(r.U); dut.io.in.bits.src(0).poke(r.U)
        dut.io.in.ready.expect(true.B)
        if (r > 4) dut.io.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.out.bits.robIdx.expect(r.U); dut.io.uncertainBusy.expect(FuType.div.U)
      }
      dut.io.in.valid.poke(false.B); dut.clock.step()
      dut.io.uncertainOut(0).bits.fuType.expect(FuType.div.U); dut.io.uncertainOut(0).bits.robIdx.expect(3.U)
      dut.io.uncertainOut(0).bits.src(0).expect(123.U); dut.io.uncertainOut(0).bits.src(1).expect(456.U)
      dut.io.uncertainOut(0).ready.poke(true.B)
      dut.clock.step(); dut.io.uncertainBusy.expect(0.U)
    }
  }

  it should "flush each input slot by its own ROB identity" in {
    test(new AnsExuInputPipe(BundleParams(), AnsIssueParams.exuByIndex(1))) { dut =>
      dut.io.uncertainOut.foreach(_.ready.poke(false.B)); zero(dut.io.flush); zero(dut.io.in.bits);
      dut.io.in.valid.poke(false.B); dut.io.out.ready.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(true.B); dut.io.in.bits.fuType.poke(FuType.div.U)
      dut.io.in.bits.robIdx.poke(3.U); dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.io.flush.valid.poke(true.B); dut.io.flush.bits.robIdx.poke(4.U)
      dut.io.uncertainBusy.expect(FuType.div.U)
      dut.clock.step(); dut.io.flush.bits.robIdx.poke(3.U); dut.io.flush.bits.flushItself.poke(true.B)
      dut.io.uncertainOut(0).valid.expect(false.B); dut.clock.step(); zero(dut.io.flush)
      dut.io.uncertainOut(0).valid.expect(false.B); dut.io.uncertainBusy.expect(0.U)
    }
  }

  it should "hold DIV and CSR requests independently with complete operands and conservative refill" in {
    test(new AnsExuInputPipe(BundleParams(), AnsIssueParams.exuByIndex(1))) { d =>
      zero(d.io.flush); zero(d.io.in.bits); d.io.in.valid.poke(false.B)
      d.io.out.ready.poke(true.B); d.io.uncertainOut.foreach(_.ready.poke(false.B))
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for ((fu, i) <- Seq(FuType.div, FuType.csr).zipWithIndex) {
        d.io.in.valid.poke(true.B); d.io.in.bits.fuType.poke(fu.U)
        d.io.in.bits.robIdx.poke((i + 1).U); d.io.in.bits.src(0).poke((100 + i).U)
        d.io.in.bits.pdest.poke((20 + i).U); d.io.in.bits.commitSelfFlush.poke((i == 1).B)
        d.io.in.ready.expect(true.B); d.clock.step()
      }
      d.io.in.bits.fuType.poke(FuType.fence.U); d.io.in.ready.expect(false.B)
      d.io.uncertainBusy.expect((FuType.div | FuType.csr | FuType.fence).U)
      for ((out, i) <- d.io.uncertainOut.zipWithIndex) {
        out.valid.expect(true.B); out.bits.robIdx.expect((i + 1).U)
        out.bits.src(0).expect((100 + i).U); out.bits.pdest.expect((20 + i).U)
        out.bits.commitSelfFlush.expect((i == 1).B)
      }
      d.io.uncertainOut(1).ready.poke(true.B); d.io.in.ready.expect(false.B)
      d.clock.step(); d.io.in.ready.expect(true.B)
      d.io.in.valid.poke(false.B)
      d.io.uncertainBusy.expect(FuType.div.U)
    }
  }

  it should "assert if a fixed EXU input is refused" in {
    assertThrows[chiseltest.ChiselAssertionError] {
      test(new AnsExuInputPipe(BundleParams(), AnsIssueParams.exuByIndex(0))) { dut =>
        dut.io.uncertainOut.foreach(_.ready.poke(false.B)); zero(dut.io.flush); zero(dut.io.in.bits);
        dut.io.in.valid.poke(false.B); dut.io.out.ready.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.in.valid.poke(true.B); dut.io.in.bits.fuType.poke(FuType.alu.U)
        dut.clock.step(); dut.io.in.valid.poke(false.B); dut.clock.step()
      }
    }
  }
}
