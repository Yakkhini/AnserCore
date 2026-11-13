package ansercore.backend.issue

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._

class IssueQueueSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams(renameWidth = 4)
  private val iq     = AnsIssueParams.intScheduler.iqs(0) // Int-IQ0: ALU0 + BJU0

  private def clearDynInst(uop: DynInst): Unit = {
    uop.fuType.poke(0.U)
    uop.fuOpType.poke(0.U)
    uop.pdest.poke(0.U)
    uop.robIdx.poke(0.U)
    for (j <- 0 until params.numSrc) {
      uop.psrc(j).poke(0.U)
      uop.srcType(j).poke(SrcType.none.U)
      uop.srcState(j).poke(SourceState.ready.U)
      uop.srcLoadDependency(j).foreach(_.poke(0.U))
      uop.useRegCache(j).poke(false.B)
      uop.regCacheIdx(j).poke(0.U)
    }
    uop.intWen.poke(false.B)
    uop.fpWen.poke(false.B)
    uop.exceptionBits.poke(0.U)
  }

  private def idle(dut: AnsIssueQueue): Unit = {
    dut.io.flush.valid.poke(false.B)
    dut.io.flush.bits.robIdx.poke(0.U)
    dut.io.flush.bits.flushItself.poke(false.B)
    dut.io.flush.bits.isInterrupt.poke(false.B)

    for (i <- 0 until iq.numEnq) {
      dut.io.enq(i).valid.poke(false.B)
      clearDynInst(dut.io.enq(i).bits)
    }
    for (i <- 0 until iq.wbWakeUpCount) {
      dut.io.wakeUpFromWB(i).valid.poke(false.B)
      dut.io.wakeUpFromWB(i).bits.pdest.poke(0.U)
      dut.io.wakeUpFromWB(i).bits.intWen.poke(false.B)
      dut.io.wakeUpFromWB(i).bits.fpWen.poke(false.B)
    }
    for (i <- 0 until iq.wakeupSourceExuIdx.size) {
      dut.io.wakeUpFromIQ(i).valid.poke(false.B)
      dut.io.wakeUpFromIQ(i).bits.pdest.poke(0.U)
      dut.io.wakeUpFromIQ(i).bits.intWen.poke(false.B)
      dut.io.wakeUpFromIQ(i).bits.fpWen.poke(false.B)
      dut.io.wakeUpFromIQ(i).bits.loadDependency.foreach(_.poke(0.U))
      dut.io.wakeUpFromIQ(i).bits.is0Lat.poke(false.B)
      dut.io.wakeUpFromIQ(i).bits.robIdx.poke(0.U)
      dut.io.wakeUpFromIQ(i).bits.rcDest.poke(0.U)
    }
    for (i <- 0 until AnsIssueParams.nExu) {
      dut.io.data0Cancel(i).poke(false.B)
    }
    for (i <- 0 until params.loadPipelineWidth) {
      dut.io.ldCancel(i).first.poke(false.B)
      dut.io.ldCancel(i).second.poke(false.B)
    }
    for (d <- 0 until iq.numDeq) {
      dut.io.data0Resp(d).valid.poke(false.B)
      dut.io.data0Resp(d).bits.robIdx.poke(0.U)
      dut.io.data0Resp(d).bits.fuType.poke(0.U)
      dut.io.data0Resp(d).bits.ready.poke(false.B)
      dut.io.data1Resp(d).valid.poke(false.B)
      dut.io.data1Resp(d).bits.robIdx.poke(0.U)
      dut.io.data1Resp(d).bits.fuType.poke(0.U)
      dut.io.data1Resp(d).bits.ready.poke(false.B)
      dut.io.wbBusyTableRead(d).intWbBusyTable.poke(0.U)
      dut.io.wbBusyTableRead(d).fpWbBusyTable.poke(0.U)
      dut.io.deq(d).ready.poke(true.B)
    }
  }

  private def reset(dut: AnsIssueQueue): Unit = {
    dut.reset.poke(true.B)
    idle(dut)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  private def pushAlu(dut: AnsIssueQueue, pdest: Int, robIdx: Int): Unit = {
    val uop = dut.io.enq(0).bits
    clearDynInst(uop)
    uop.fuType.poke(FuType.alu.U)
    uop.pdest.poke(pdest.U)
    uop.robIdx.poke(robIdx.U)
    uop.intWen.poke(true.B)
    for (j <- 0 until params.numSrc) {
      uop.srcType(j).poke(SrcType.immediate.U)
    }
    dut.io.enq(0).valid.poke(true.B)
    dut.io.enq(0).ready.expect(true.B)
    dut.clock.step()
    dut.io.enq(0).valid.poke(false.B)
  }

  /** 用同一个 robIdx/端口把 S0/S1 响应依次送给对应条目。 */
  private def respond(dut: AnsIssueQueue, robIdx: Int): Unit = {
    val aluPort = 0
    for (stage <- 0 until 2) {
      val resp = if (stage == 0) dut.io.data0Resp(aluPort) else dut.io.data1Resp(aluPort)
      resp.valid.poke(true.B)
      resp.bits.robIdx.poke(robIdx.U)
      resp.bits.fuType.poke(FuType.alu.U)
      resp.bits.ready.poke(true.B)
      dut.clock.step()
      resp.valid.poke(false.B)
    }
  }

  behavior of "AnsIssueQueue"

  it should "enqueue an ALU uop, issue it and clear after S1 success" in {
    test(new AnsIssueQueue(params, iq)) { dut =>
      reset(dut)
      pushAlu(dut, pdest = 3, robIdx = 1)
      dut.io.status.validCnt.expect(1.U)

      var seen   = false
      var cycles = 0
      while (!seen && cycles < 6) {
        if (dut.io.deq(0).valid.peek().litToBoolean) {
          dut.io.deq(0).bits.common.pdest.expect(3.U)
          dut.io.deq(0).bits.common.fuType.expect(FuType.alu.U)
          dut.io.deq(0).bits.common.robIdx.expect(1.U)
          seen = true
        } else {
          dut.clock.step()
          cycles += 1
        }
      }
      seen shouldBe true

      respond(dut, robIdx = 1)
      dut.io.status.validCnt.expect(0.U)
    }
  }

  it should "enqueue two ALU uops in the same cycle and issue the older one first" in {
    test(new AnsIssueQueue(params, iq)) { dut =>
      reset(dut)

      for ((port, pdest, robIdx) <- Seq((0, 3, 1), (1, 4, 2))) {
        val uop = dut.io.enq(port).bits
        clearDynInst(uop)
        uop.fuType.poke(FuType.alu.U)
        uop.pdest.poke(pdest.U)
        uop.robIdx.poke(robIdx.U)
        uop.intWen.poke(true.B)
        for (j <- 0 until params.numSrc) {
          uop.srcType(j).poke(SrcType.immediate.U)
        }
        dut.io.enq(port).valid.poke(true.B)
      }
      dut.io.enq(0).ready.expect(true.B)
      dut.io.enq(1).ready.expect(true.B)
      dut.clock.step()
      for (p <- 0 until iq.numEnq) {
        dut.io.enq(p).valid.poke(false.B)
      }
      dut.io.status.validCnt.expect(2.U)

      var seen   = false
      var cycles = 0
      while (!seen && cycles < 6) {
        if (dut.io.deq(0).valid.peek().litToBoolean) {
          // 端口 0 的 uop 更老，必须先发射。
          dut.io.deq(0).bits.common.pdest.expect(3.U)
          seen = true
        } else {
          dut.clock.step()
          cycles += 1
        }
      }
      seen shouldBe true

      respond(dut, robIdx = 1)
      dut.io.status.validCnt.expect(1.U)
    }
  }

  it should "wake a source by IQ wakeup before issuing" in {
    test(new AnsIssueQueue(params, iq)) { dut =>
      reset(dut)

      val uop = dut.io.enq(0).bits
      clearDynInst(uop)
      uop.fuType.poke(FuType.alu.U)
      uop.pdest.poke(4.U)
      uop.robIdx.poke(2.U)
      uop.intWen.poke(true.B)
      uop.psrc(0).poke(5.U)
      uop.srcType(0).poke(SrcType.integer.U)
      uop.srcState(0).poke(SourceState.waiting.U)
      uop.srcType(1).poke(SrcType.immediate.U)
      uop.srcType(2).poke(SrcType.none.U)
      dut.io.enq(0).valid.poke(true.B)
      dut.clock.step()
      dut.io.enq(0).valid.poke(false.B)
      dut.clock.step(2)
      dut.io.deq(0).valid.expect(false.B)

      // intWakeAll(0) 对应 ALU0。
      dut.io.wakeUpFromIQ(0).valid.poke(true.B)
      dut.io.wakeUpFromIQ(0).bits.pdest.poke(5.U)
      dut.io.wakeUpFromIQ(0).bits.intWen.poke(true.B)
      dut.io.wakeUpFromIQ(0).bits.fpWen.poke(false.B)
      dut.io.wakeUpFromIQ(0).bits.robIdx.poke(0.U)
      dut.clock.step()
      dut.io.wakeUpFromIQ(0).valid.poke(false.B)

      var seen   = false
      var cycles = 0
      while (!seen && cycles < 6) {
        dut.clock.step()
        if (dut.io.deq(0).valid.peek().litToBoolean) {
          dut.io.deq(0).bits.common.pdest.expect(4.U)
          seen = true
        }
        cycles += 1
      }
      seen shouldBe true
    }
  }
}
