package ansercore.backend.issue

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import IssueTestUtils.zero

class AnsDataPathSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsDataPath"

  it should "read the granted address and retry losing integer and FP read requests" in {
    test(new AnsDataPath()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      zero(dut.io.flush)
      zero(dut.io.pcMemWrite)
      zero(dut.io.intWriteBack)
      zero(dut.io.fpWriteBack)
      zero(dut.io.exuOutput)
      zero(dut.io.wbBusyTableWrite)
      dut.io.fromIQ.foreach { in =>
        in.valid.poke(false.B)
        zero(in.bits)
      }
      dut.io.toExu.foreach(_.ready.poke(true.B))
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(2)

      // 地址 1/2 的按位或为 3；给地址 3 写入不同数据以捕获原始请求 Mux1H 错误。
      for ((address, data) <- Seq(1 -> 11, 2 -> 22, 3 -> 99)) {
        for (wb <- Seq(dut.io.intWriteBack(0), dut.io.fpWriteBack(0))) {
          wb.wen.poke(true.B)
          wb.addr.poke(address.U)
          wb.data.poke(data.U)
        }
        dut.clock.step()
      }
      zero(dut.io.intWriteBack)
      zero(dut.io.fpWriteBack)

      for ((first, second, floating) <- Seq((0, 5, false), (6, 8, true))) {
        for ((exu, address) <- Seq(first -> 1, second -> 2)) {
          val in = dut.io.fromIQ(exu)
          zero(in.bits)
          in.valid.poke(true.B)
          in.bits.srcType(0).poke((if (floating) SrcType.floating else SrcType.integer).U)
          in.bits.psrc(0).poke(address.U)
          in.bits.common.dataSources(0).poke(DataSource.registerFile.U)
          in.bits.common.pdest.poke((10 + address).U)
          in.bits.common.intWen.poke((!floating).B)
          in.bits.common.fpWen.poke(floating.B)
        }
        dut.io.fromIQ(first).ready.expect(true.B)
        dut.io.fromIQ(second).ready.expect(true.B)
        dut.io.toIq.data0Resp(first).bits.ready.expect(true.B)
        dut.io.toIq.data0Resp(second).bits.ready.expect(false.B)
        dut.io.toIq.data0Cancel(first).expect(false.B)
        dut.io.toIq.data0Cancel(second).expect(true.B)
        dut.io.toIq.data0CancelToDispatch(second).pdest.expect(12.U)
        dut.io.toIq.data0CancelToDispatch(second).intWen.expect((!floating).B)
        dut.io.toIq.data0CancelToDispatch(second).fpWen.expect(floating.B)

        dut.clock.step()
        dut.io.fromIQ(first).valid.poke(false.B)
        dut.io.fromIQ(second).valid.poke(false.B)
        dut.io.toExu(first).valid.expect(true.B)
        dut.io.toExu(first).bits.src(0).expect(11.U)
        dut.io.toExu(second).valid.expect(false.B)
        dut.clock.step()

        // 失去读口的一方重新尝试，此时必须读到自己的地址。
        dut.io.fromIQ(second).valid.poke(true.B)
        dut.io.toIq.data0Resp(second).bits.ready.expect(true.B)
        dut.io.toIq.data0Cancel(second).expect(false.B)
        dut.clock.step()
        dut.io.fromIQ(second).valid.poke(false.B)
        dut.io.toExu(second).valid.expect(true.B)
        dut.io.toExu(second).bits.src(0).expect(22.U)

        // EXU 拒绝通过 data1Resp 通知重试；DataPath 下一拍不保持这次尝试。
        dut.io.toExu(second).ready.poke(false.B)
        dut.io.toIq.data1Resp(second).valid.expect(true.B)
        dut.io.toIq.data1Resp(second).bits.ready.expect(false.B)
        dut.io.toIq.data1Cancel(second).expect(true.B)
        dut.clock.step()
        dut.io.toExu(second).valid.expect(false.B)
        dut.io.toExu(second).ready.poke(true.B)
      }
    }
  }
}
