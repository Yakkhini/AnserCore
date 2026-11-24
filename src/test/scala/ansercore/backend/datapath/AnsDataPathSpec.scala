package ansercore.backend.datapath

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.issue.IssueTestUtils.zero

class AnsDataPathSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsDataPath"

  it should "read the granted address and retry losing integer and FP read requests" in {
    test(new AnsDataPath()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      zero(dut.io.flush)
      zero(dut.io.ldCancel)
      zero(dut.io.pcMemWrite)
      zero(dut.io.intWriteBack)
      zero(dut.io.fpWriteBack)
      zero(dut.io.wbBusyTableWrite)
      dut.io.fromIQ.foreach { in =>
        in.valid.poke(false.B)
        zero(in.bits)
      }
      // BypassNetwork 只在 data1 之后接收；本测试只检查 data0/data1 与 PRF 数据。
      dut.io.toBypassNetwork.foreach(_.ready.poke(true.B))
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

      // 8R：ALU0(idx0) 与 MUL1(idx3) 共享 IntRD0/1；FEX0(idx6) 与 FEX2(idx8) 共享 FpRD0/1/2。
      for ((first, second, floating) <- Seq((4, 1, false), (6, 8, true))) {
        for ((exu, address) <- Seq(first -> 1, second -> 2)) {
          val in = dut.io.fromIQ(exu)
          zero(in.bits)
          in.valid.poke(true.B)
          in.bits.srcType(0).poke((if (floating) SrcType.floating else SrcType.integer).U)
          in.bits.psrc(0).poke(address.U)
          in.bits.common.dataSources(0).poke(DataSource.registerFile.U)
          in.bits.common.fuType.poke((if (floating) FuType.falu else if (exu == 1) FuType.csr else FuType.alu).U)
          in.bits.common.pdest.poke((10 + address).U)
          in.bits.common.intWen.poke((!floating).B)
          in.bits.common.fpWen.poke(floating.B)
        }
        dut.io.fromIQ(first).ready.expect(true.B)
        dut.io.fromIQ(second).ready.expect(true.B)
        // data0 通过时静默（valid=0），失败时才发 block。
        dut.io.toIq.data0Resp(first).valid.expect(false.B)
        dut.io.toIq.data0Resp(second).valid.expect(true.B)
        dut.io.toIq.data0Resp(second).bits.resp.expect(IssueRespType.block.U)
        dut.io.toIq.data0Cancel(first).expect(false.B)
        dut.io.toIq.data0Cancel(second).expect(true.B)
        dut.io.toIq.data0CancelToDispatch(second).pdest.expect(12.U)
        dut.io.toIq.data0CancelToDispatch(second).intWen.expect((!floating).B)
        dut.io.toIq.data0CancelToDispatch(second).fpWen.expect(floating.B)

        dut.clock.step()
        dut.io.fromIQ(first).valid.poke(false.B)
        dut.io.fromIQ(second).valid.poke(false.B)
        dut.io.toBypassNetwork(first).valid.expect(true.B)
        dut.io.toBypassNetwork(first).bits.src(0).expect(11.U)
        dut.io.toBypassNetwork(second).valid.expect(false.B)
        dut.clock.step()

        // 失去读口的一方重新尝试，此时必须读到自己的地址。
        dut.io.fromIQ(second).valid.poke(true.B)
        dut.io.toIq.data0Resp(second).valid.expect(false.B)
        dut.io.toIq.data0Cancel(second).expect(false.B)
        dut.clock.step()
        dut.io.fromIQ(second).valid.poke(false.B)
        dut.io.toBypassNetwork(second).valid.expect(true.B)
        dut.io.toBypassNetwork(second).bits.src(0).expect(22.U)

        // 只有不确定操作允许普通 data1 反压；固定操作必须按拍接收。
        if (!floating) {
          dut.io.toBypassNetwork(second).ready.poke(false.B)
          dut.io.toIq.data1Resp(second).valid.expect(true.B)
          dut.io.toIq.data1Resp(second).bits.resp.expect(IssueRespType.block.U)
          dut.io.toIq.data1Cancel(second).expect(true.B)
        }
        dut.clock.step()
        dut.io.toBypassNetwork(second).valid.expect(false.B)
        dut.io.toBypassNetwork(second).ready.poke(true.B)
      }
    }
  }

  it should "cancel load dependencies at both boundaries and propagate only the preceding zero latency failure" in {
    test(new AnsDataPath()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      Seq(
        dut.io.flush,
        dut.io.ldCancel,
        dut.io.pcMemWrite,
        dut.io.intWriteBack,
        dut.io.fpWriteBack,
        dut.io.wbBusyTableWrite,
      ).foreach(zero)
      dut.io.fromIQ.foreach { in => in.valid.poke(false.B); zero(in.bits) }
      dut.io.toBypassNetwork.foreach(_.ready.poke(true.B))
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      val in = dut.io.fromIQ(0)
      in.valid.poke(true.B); in.bits.common.fuType.poke(FuType.alu.U)
      in.bits.common.loadDependency(0).poke(1.U)
      dut.io.ldCancel(0).first.poke(true.B)
      dut.io.toIq.data0Cancel(0).expect(true.B)
      dut.clock.step(); zero(dut.io.ldCancel)
      dut.io.toBypassNetwork(0).valid.expect(false.B)
      dut.clock.step(); in.valid.poke(false.B)
      dut.io.ldCancel(0).second.poke(true.B)
      dut.io.toBypassNetwork(0).valid.expect(false.B)
      dut.io.toIq.data1Resp(0).valid.expect(true.B)
      dut.io.toIq.data1Resp(0).bits.resp.expect(IssueRespType.block.U)
      dut.clock.step(); zero(dut.io.ldCancel)
      in.valid.poke(true.B); in.bits.common.loadDependency(0).poke(0.U)
      dut.clock.step(); in.valid.poke(false.B)
      dut.io.toBypassNetwork(0).valid.expect(true.B)
      dut.io.toIq.data1Resp(0).bits.resp.expect(IssueRespType.success.U)
      dut.clock.step()

      // BJU0 loses RD2 to ALU1. Then ALU0 and ALU2 forward attempts are cancelled.
      for ((e, fu) <- Seq(2 -> FuType.alu, 5 -> FuType.jmp)) {
        val x = dut.io.fromIQ(e); zero(x.bits); x.valid.poke(true.B)
        x.bits.common.fuType.poke(fu.U); x.bits.srcType(0).poke(SrcType.integer.U)
        x.bits.common.dataSources(0).poke(DataSource.registerFile.U)
      }
      dut.io.toIq.data0Cancel(5).expect(true.B)
      dut.clock.step(); dut.io.fromIQ.foreach(_.valid.poke(false.B))
      for ((consumer, producer) <- Seq(0 -> 5, 4 -> 0)) {
        val x = dut.io.fromIQ(consumer); zero(x.bits); x.valid.poke(true.B)
        x.bits.common.fuType.poke(FuType.alu.U)
        x.bits.common.dataSources(0).poke(DataSource.forward.U)
        x.bits.common.exuSources(0).poke((producer + 1).U)
        dut.io.toIq.data0Cancel(consumer).expect(true.B)
        dut.clock.step(); x.valid.poke(false.B)
      }
      dut.clock.step(2)
      // A MUL failure on that EXU is not a zero latency cancellation.
      for ((e, fu) <- Seq(4 -> FuType.alu, 1 -> FuType.mul)) {
        val x = dut.io.fromIQ(e); zero(x.bits); x.valid.poke(true.B)
        x.bits.common.fuType.poke(fu.U); x.bits.srcType(0).poke(SrcType.integer.U)
        x.bits.common.dataSources(0).poke(DataSource.registerFile.U)
      }
      dut.io.toIq.data0Cancel(1).expect(true.B)
      dut.clock.step(); dut.io.fromIQ.foreach(_.valid.poke(false.B))
      zero(in.bits); in.valid.poke(true.B); in.bits.common.fuType.poke(FuType.alu.U)
      in.bits.common.dataSources(0).poke(DataSource.forward.U); in.bits.common.exuSources(0).poke(2.U)
      dut.io.toIq.data0Cancel(0).expect(false.B)
    }
  }

  it should "assert on ordinary fixed data1 backpressure" in {
    assertThrows[chiseltest.ChiselAssertionError] {
      test(new AnsDataPath()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        Seq(
          dut.io.flush,
          dut.io.ldCancel,
          dut.io.pcMemWrite,
          dut.io.intWriteBack,
          dut.io.fpWriteBack,
          dut.io.wbBusyTableWrite,
        ).foreach(zero)
        dut.io.fromIQ.foreach { in => in.valid.poke(false.B); zero(in.bits) }
        dut.io.toBypassNetwork.foreach(_.ready.poke(true.B))
        dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        dut.io.fromIQ(0).valid.poke(true.B); dut.io.fromIQ(0).bits.common.fuType.poke(FuType.alu.U)
        dut.clock.step(); dut.io.fromIQ(0).valid.poke(false.B)
        dut.io.toBypassNetwork(0).ready.poke(false.B); dut.clock.step()
      }
    }
  }
}
