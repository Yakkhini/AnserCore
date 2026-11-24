package ansercore.backend.dispatch

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ansercore.backend.Bundles._
import ansercore.backend.issue.IssueTestUtils.zero

class AnsLoadDependencySpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "Dispatch dependency tracking"
  for (isInt <- Seq(true, false)) {
    it should s"filter cancelled wakes, age ready dependencies and clear them on actual WB (int=$isInt)" in {
      test(new AnsBusyTable(BundleParams(physicalRegWidth = 4), 1, 16, 1, 2, 1, 2, isInt)) { dut =>
        Seq(dut.io.allocPregs, dut.io.wbPregs, dut.io.wakeUp, dut.io.cancel, dut.io.ldCancel).foreach(zero)
        dut.io.read(0).req.poke(5.U)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.allocPregs(0).valid.poke(true.B); dut.io.allocPregs(0).bits.poke(5.U)
        dut.io.read(0).busy.expect(true.B); dut.clock.step(); zero(dut.io.allocPregs)
        val w = dut.io.wakeUp(0)
        w.valid.poke(true.B); w.bits.pdest.poke(5.U); w.bits.is0Lat.poke(true.B)
        w.bits.rfWen.poke(isInt.B); w.bits.fpWen.poke((!isInt).B)
        dut.io.cancel(0).intWen.poke(isInt.B); dut.io.cancel(0).fpWen.poke((!isInt).B)
        dut.io.cancel(0).pdest.poke(5.U)
        dut.clock.step(); dut.io.read(0).busy.expect(true.B)
        zero(dut.io.cancel); w.bits.loadDependency(0).poke(1.U)
        dut.io.ldCancel(0).first.poke(true.B)
        dut.clock.step(); dut.io.read(0).busy.expect(true.B)
        zero(dut.io.ldCancel); dut.clock.step(); zero(dut.io.wakeUp)
        dut.io.read(0).busy.expect(false.B); dut.io.read(0).loadDependency(0).expect(2.U)
        dut.io.ldCancel(0).second.poke(true.B)
        dut.io.read(0).busy.expect(true.B)
        dut.clock.step(); zero(dut.io.ldCancel); dut.io.read(0).busy.expect(true.B)
        dut.io.wbPregs(0).valid.poke(true.B); dut.io.wbPregs(0).bits.poke(5.U)
        dut.clock.step(); zero(dut.io.wbPregs)
        dut.io.read(0).busy.expect(false.B); dut.io.read(0).loadDependency(0).expect(0.U)
        // Later activity on the same producer must not revoke a completed destination.
        dut.io.cancel(0).intWen.poke(isInt.B); dut.io.cancel(0).fpWen.poke((!isInt).B)
        dut.io.cancel(0).pdest.poke(5.U); dut.clock.step(); dut.io.read(0).busy.expect(false.B)
        zero(dut.io.cancel)
        // Allocation is authoritative even if an old WB or wakeup has the same tag.
        dut.io.allocPregs(0).valid.poke(true.B); dut.io.allocPregs(0).bits.poke(5.U)
        dut.io.wbPregs(0).valid.poke(true.B); dut.io.wbPregs(0).bits.poke(5.U)
        dut.clock.step(); zero(dut.io.allocPregs); zero(dut.io.wbPregs)
        dut.io.read(0).busy.expect(true.B)
        w.valid.poke(true.B); w.bits.pdest.poke(5.U)
        w.bits.rfWen.poke((!isInt).B); w.bits.fpWen.poke(isInt.B)
        dut.clock.step(); dut.io.read(0).busy.expect(true.B)
        zero(dut.io.wakeUp)
        dut.io.read(0).req.poke(0.U); dut.io.allocPregs(0).valid.poke(true.B)
        dut.io.allocPregs(0).bits.poke(0.U); dut.clock.step(); zero(dut.io.allocPregs)
        dut.io.read(0).busy.expect((!isInt).B)
        dut.io.read(0).loadDependency(0).expect(0.U)
      }
    }
  }

  it should "invalidate dependent RegCache tags on load cancellation and clear dependencies on WB" in {
    test(new AnsRegCacheTagTable(BundleParams(), 1, 1, 1, 1)) { dut =>
      Seq(dut.io.allocPregs, dut.io.write, dut.io.cancel, dut.io.ldCancel, dut.io.wbPregs).foreach(zero)
      dut.io.read(0).ren.poke(true.B); dut.io.read(0).tag.poke(5.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      val w = dut.io.write(0)
      w.wen.poke(true.B); w.tag.poke(5.U); w.addr.poke(0.U); w.loadDependency(0).poke(1.U)
      dut.io.ldCancel(0).first.poke(true.B)
      dut.clock.step(); dut.io.read(0).valid.expect(false.B)
      zero(dut.io.ldCancel); dut.clock.step(); w.wen.poke(false.B)
      dut.io.read(0).valid.expect(true.B)
      dut.io.ldCancel(0).second.poke(true.B); dut.io.read(0).valid.expect(false.B)
      dut.clock.step(); zero(dut.io.ldCancel); dut.io.read(0).valid.expect(false.B)
      w.wen.poke(true.B); dut.clock.step(); w.wen.poke(false.B)
      dut.io.wbPregs(0).valid.poke(true.B); dut.io.wbPregs(0).bits.poke(5.U)
      dut.clock.step(); zero(dut.io.wbPregs)
      dut.io.ldCancel(0).second.poke(true.B); dut.io.read(0).valid.expect(true.B)
      zero(dut.io.ldCancel)
      w.wen.poke(true.B); w.tag.poke(6.U)
      dut.io.wbPregs(0).valid.poke(true.B); dut.io.wbPregs(0).bits.poke(6.U)
      dut.clock.step(); w.wen.poke(false.B); zero(dut.io.wbPregs)
      dut.io.read(0).tag.poke(6.U); dut.io.ldCancel(0).second.poke(true.B)
      dut.io.read(0).valid.expect(true.B) // simultaneous WB clears the new tag's dependency
      dut.io.allocPregs(0).valid.poke(true.B); dut.io.allocPregs(0).bits.poke(6.U)
      dut.io.read(0).valid.expect(false.B)
    }
  }
}
