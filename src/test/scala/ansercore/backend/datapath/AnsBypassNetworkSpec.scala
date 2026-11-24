package ansercore.backend.datapath

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.issue.IssueTestUtils.zero

class AnsBypassNetworkSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsBypassNetwork"

  it should "select every data source and align bypass valid with its data" in {
    test(new AnsBypassNetwork()) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val in  = dut.io.fromDataPath(0)
      val out = dut.io.toExu(0)

      zero(in.bits)
      in.valid.poke(true.B)
      out.ready.poke(true.B)
      in.bits.src(0).poke(11.U)
      in.bits.imm.poke(5.U)
      in.bits.immType.poke(ImmType.z.U)
      in.bits.exuSources(0).poke(2.U) // 生产者 exuIdx = 1
      dut.io.rcData(0)(0).poke(22.U)

      dut.io.fromExu.foreach { exu =>
        exu.valid.poke(false.B)
        exu.bits.data(0).poke(0.U)
      }

      out.valid.expect(true.B)
      in.ready.expect(true.B)

      in.bits.dataSources(0).poke(DataSource.registerFile.U)
      out.bits.src(0).expect(11.U)

      in.bits.dataSources(0).poke(DataSource.regCache.U)
      out.bits.src(0).expect(22.U)

      in.bits.dataSources(0).poke(DataSource.immediate.U)
      out.bits.src(0).expect(5.U)

      in.bits.dataSources(0).poke(DataSource.zero.U)
      out.bits.src(0).expect(0.U)

      // forward：同拍读取生产者组合输出。
      dut.io.fromExu(1).valid.poke(true.B)
      dut.io.fromExu(1).bits.data(0).poke(33.U)
      in.bits.dataSources(0).poke(DataSource.forward.U)
      out.bits.src(0).expect(33.U)

      // bypass：把 33 打进一拍寄存器。
      in.bits.dataSources(0).poke(DataSource.registerFile.U)
      dut.clock.step()
      dut.io.fromExu(1).valid.poke(false.B)
      in.bits.dataSources(0).poke(DataSource.bypass.U)
      out.bits.src(0).expect(33.U)
    }
  }
}
