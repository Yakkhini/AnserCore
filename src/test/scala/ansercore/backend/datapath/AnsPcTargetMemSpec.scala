package ansercore.backend.datapath

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import ansercore.backend.Bundles._
import ansercore.backend.issue.IssueTestUtils.zero

class AnsPcTargetMemSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AnsPcTargetMem"

  it should "read each FTQ entry's own target with one-cycle latency and hold invalid reads" in {
    val p = BundleParams()
    test(new AnsPcTargetMem(p, 2)) { d =>
      zero(d.io.write)
      d.io.read.foreach { r => zero(r.valid); zero(r.ftqPtr); zero(r.ftqOffset) }
      val entries = Seq((0, 0x1000, 0x2000), (1, 0x2000, 0x3000), (p.ftqEntries - 1, 0x4000, 0x5000))
      for ((idx, pc, target) <- entries) {
        d.io.write.wen.poke(true.B); d.io.write.ftqPtr.value.poke(idx.U)
        d.io.write.pc.poke(pc.U); d.io.write.target.poke(target.U); d.clock.step()
      }
      d.io.write.wen.poke(false.B)
      for ((idx, pc, target) <- entries) {
        for ((r, port) <- d.io.read.zipWithIndex) {
          r.valid.poke(true.B); r.ftqPtr.value.poke(idx.U); r.ftqOffset.poke((port + 1).U)
        }
        d.clock.step()
        for ((r, port) <- d.io.read.zipWithIndex) {
          r.pc.expect((pc + 2 * (port + 1)).U); r.target.expect(target.U)
          r.valid.poke(false.B); r.ftqPtr.value.poke(((idx + 1) % p.ftqEntries).U)
        }
        d.clock.step()
        for ((r, port) <- d.io.read.zipWithIndex) {
          r.pc.expect((pc + 2 * (port + 1)).U); r.target.expect(target.U)
        }
      }
    }
  }
}
