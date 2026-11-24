package ansercore.backend.dispatch

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._

class AnsRegCacheTagTableSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsRegCacheTagTable"

  it should "filter a wakeup write when the same tag is cancelled in that cycle" in {
    test(new AnsRegCacheTagTable(BundleParams(), numReadPorts = 1, numWritePorts = 1, nAlloc = 1, nCancel = 1)) { dut =>
      ansercore.backend.issue.IssueTestUtils.zero(dut.io.ldCancel)
      ansercore.backend.issue.IssueTestUtils.zero(dut.io.wbPregs)
      ansercore.backend.issue.IssueTestUtils.zero(dut.io.write)
      dut.io.allocPregs.foreach { a =>
        a.valid.poke(false.B)
        a.bits.poke(0.U)
      }
      dut.io.cancel.foreach { c =>
        c.intWen.poke(false.B)
        c.fpWen.poke(false.B)
        c.pdest.poke(0.U)
      }
      dut.io.write.foreach { w =>
        w.wen.poke(false.B)
        w.addr.poke(0.U)
        w.tag.poke(0.U)
      }
      dut.io.read(0).ren.poke(false.B)
      dut.io.read(0).tag.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(2)

      // 先写入 tag=5。
      dut.io.write(0).wen.poke(true.B)
      dut.io.write(0).addr.poke(0.U)
      dut.io.write(0).tag.poke(5.U)
      dut.clock.step()
      dut.io.write(0).wen.poke(false.B)
      dut.io.read(0).ren.poke(true.B)
      dut.io.read(0).tag.poke(5.U)
      dut.io.read(0).valid.expect(true.B)

      // 取消只过滤当拍 0-lat 写入，不撤销此前已完成的 tag。
      dut.io.write(0).is0Lat.poke(true.B)
      dut.io.write(0).wen.poke(true.B)
      dut.io.write(0).addr.poke(0.U)
      dut.io.write(0).tag.poke(5.U)
      dut.io.cancel(0).intWen.poke(true.B)
      dut.io.cancel(0).pdest.poke(5.U)
      dut.clock.step()
      dut.io.write(0).wen.poke(false.B)
      dut.io.cancel(0).intWen.poke(false.B)
      dut.io.read(0).valid.expect(true.B)

      // 目标槽原本没有该 tag 时，也要按 cancel pdest 过滤新的 wakeup write。
      dut.io.read(0).tag.poke(6.U)
      dut.io.write(0).wen.poke(true.B)
      dut.io.write(0).addr.poke(1.U)
      dut.io.write(0).tag.poke(6.U)
      dut.io.cancel(0).intWen.poke(true.B)
      dut.io.cancel(0).pdest.poke(6.U)
      dut.clock.step()
      dut.io.write(0).wen.poke(false.B)
      dut.io.cancel(0).intWen.poke(false.B)
      dut.io.read(0).valid.expect(false.B)
    }
  }
}
