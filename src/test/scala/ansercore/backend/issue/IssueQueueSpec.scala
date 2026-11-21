package ansercore.backend.issue

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
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
      dut.io.data0Resp(d).bits.resp.poke(IssueRespType.block.U)
      dut.io.data1Resp(d).valid.poke(false.B)
      dut.io.data1Resp(d).bits.robIdx.poke(0.U)
      dut.io.data1Resp(d).bits.fuType.poke(0.U)
      dut.io.data1Resp(d).bits.resp.poke(IssueRespType.block.U)
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

  /** 模拟 DataPath：data0 通过时静默，下一拍 data1 success 才清除已发射条目。 */
  private def respond(dut: AnsIssueQueue, robIdx: Int): Unit = {
    val aluPort = 0
    val resp    = dut.io.data1Resp(aluPort)
    // data0 静默（保持 idle 的 valid=0），推进一拍到 data1 阶段。
    dut.clock.step()
    resp.valid.poke(true.B)
    resp.bits.robIdx.poke(robIdx.U)
    resp.bits.fuType.poke(FuType.alu.U)
    resp.bits.resp.poke(IssueRespType.success.U)
    dut.clock.step()
    resp.valid.poke(false.B)
  }

  behavior of "AnsIssueQueue"

  it should "select operand metadata from enqueue entry 1 when entry 0 is waiting" in {
    test(new AnsIssueQueue(params, iq)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      reset(dut)
      for (port <- 0 until 2) {
        val uop = dut.io.enq(port).bits
        clearDynInst(uop)
        uop.fuType.poke(FuType.alu.U)
        uop.robIdx.poke((port + 1).U)
        uop.psrc(0).poke((port + 5).U)
        uop.srcType(0).poke((if (port == 0) SrcType.integer else SrcType.immediate).U)
        uop.srcState(0).poke((if (port == 0) SourceState.waiting else SourceState.ready).U)
        dut.io.enq(port).valid.poke(true.B)
        dut.io.enq(port).ready.expect(true.B)
      }
      dut.clock.step()
      dut.io.enq.foreach(_.valid.poke(false.B))
      dut.clock.step()
      dut.io.deq(0).valid.expect(true.B)
      dut.io.deq(0).bits.common.robIdx.expect(2.U)
      dut.io.deq(0).bits.addrOH.expect(2.U)
      dut.io.deq(0).bits.common.dataSources(0).expect(DataSource.immediate.U)
    }
  }

  it should "keep a migrated entry's bypass producer and dependency with its payload" in {
    test(new AnsIssueQueue(params, iq)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      reset(dut)
      for (port <- 0 until 2) {
        val uop = dut.io.enq(port).bits
        clearDynInst(uop)
        uop.fuType.poke(FuType.alu.U)
        uop.robIdx.poke((port + 1).U)
        uop.psrc(0).poke((port + 5).U)
        uop.srcType(0).poke(SrcType.integer.U)
        uop.srcState(0).poke(SourceState.waiting.U)
        dut.io.enq(port).valid.poke(true.B)
      }
      dut.clock.step()
      dut.io.enq.foreach(_.valid.poke(false.B))
      dut.clock.step(2)
      dut.io.deq(0).valid.expect(false.B)

      val wakeup = dut.io.wakeUpFromIQ(1)
      wakeup.valid.poke(true.B)
      wakeup.bits.pdest.poke(6.U)
      wakeup.bits.intWen.poke(true.B)
      wakeup.bits.loadDependency(0).poke(1.U)
      dut.clock.step()
      wakeup.valid.poke(false.B)
      dut.clock.step()
      dut.io.deq(0).valid.expect(true.B)
      dut.io.deq(0).bits.addrOH.expect((1 << (iq.numEnq + 1)).U)
      dut.io.deq(0).bits.common.robIdx.expect(2.U)
      dut.io.deq(0).bits.psrc(0).expect(6.U)
      dut.io.deq(0).bits.common.dataSources(0).expect(DataSource.bypass2.U)
      dut.io.deq(0).bits.common.exuSources(0).expect((iq.wakeupSourceExuIdx(1) + 1).U)
      dut.io.deq(0).bits.common.loadDependency(0).expect(1.U)
      dut.io.deq(0).bits.common.isFirstIssue.expect(true.B)
    }
  }

  it should "enqueue an ALU uop, issue it and clear after data1 success" in {
    test(new AnsIssueQueue(params, iq)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
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
    test(new AnsIssueQueue(params, iq)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
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
    test(new AnsIssueQueue(params, iq)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
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

  /** 0-lat 唤醒 -> 发射延迟骨架。
    *
    * 生产者（ALU，pdest = 7）与消费者（ALU，等 pdest = 7）同时入队。测试台模拟 AnsBackend 的
    * 唤醒广播：本拍观察 `wakeupToIQ(0)`，下一拍送回 `wakeUpFromIQ(0)`
    * （对应 `registerWakeupBroadcast = true`）。
    *
    * 当前期望：生产者与消费者的 deq 相差 2 拍，且消费者读到 bypass2。
    * 若将来开启香山式组合广播（`registerWakeupBroadcast = false`）并把
    * `AnsEntry.iqWakeupHitDataSource` 改成 bypass，差值应变成 1，
    * 届时同步修改测试台的打拍方式和这里的期望值。详见 IssueQueue 设计文档 §5.5。
    */
  it should "measure 0-lat IQ wakeup to issue latency" in {
    test(new AnsIssueQueue(params, iq)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      reset(dut)

      val prod = dut.io.enq(0).bits
      clearDynInst(prod)
      prod.fuType.poke(FuType.alu.U)
      prod.robIdx.poke(1.U)
      prod.pdest.poke(7.U)
      prod.intWen.poke(true.B)
      for (j <- 0 until params.numSrc) {
        prod.srcType(j).poke(SrcType.immediate.U)
      }
      dut.io.enq(0).valid.poke(true.B)

      val cons = dut.io.enq(1).bits
      clearDynInst(cons)
      cons.fuType.poke(FuType.alu.U)
      cons.robIdx.poke(2.U)
      cons.pdest.poke(8.U)
      cons.intWen.poke(true.B)
      cons.psrc(0).poke(7.U)
      cons.srcType(0).poke(SrcType.integer.U)
      cons.srcState(0).poke(SourceState.waiting.U)
      dut.io.enq(1).valid.poke(true.B)

      dut.io.enq(0).ready.expect(true.B)
      dut.io.enq(1).ready.expect(true.B)
      dut.clock.step()
      dut.io.enq(0).valid.poke(false.B)
      dut.io.enq(1).valid.poke(false.B)

      var lastWakeupValid = false
      var lastPdest       = 0
      var lastIs0Lat      = false
      var producerDeq     = -1
      var consumerDeq     = -1
      var cycle           = 0
      while (cycle < 20 && (producerDeq < 0 || consumerDeq < 0)) {
        // 打入上一拍捕获的唤醒（对应 AnsBackend 的 registerWakeupBroadcast = true）。
        dut.io.wakeUpFromIQ(0).valid.poke(lastWakeupValid.B)
        dut.io.wakeUpFromIQ(0).bits.pdest.poke(lastPdest.U)
        dut.io.wakeUpFromIQ(0).bits.intWen.poke(true.B)
        dut.io.wakeUpFromIQ(0).bits.fpWen.poke(false.B)
        dut.io.wakeUpFromIQ(0).bits.is0Lat.poke(lastIs0Lat.B)
        dut.io.wakeUpFromIQ(0).bits.robIdx.poke(0.U)
        dut.io.wakeUpFromIQ(0).bits.loadDependency.foreach(_.poke(0.U))
        dut.io.wakeUpFromIQ(0).bits.rcDest.poke(0.U)

        if (dut.io.deq(0).valid.peek().litToBoolean) {
          val pdest = dut.io.deq(0).bits.common.pdest.peek().litValue.toInt
          if (pdest == 7 && producerDeq < 0) producerDeq = cycle
          if (pdest == 8 && consumerDeq < 0) {
            consumerDeq = cycle
            // 消费者被 IQ 唤醒，当前应读 bypass2（与广播打一拍绑定）。
            dut.io.deq(0).bits.common.dataSources(0).expect(DataSource.bypass2.U)
          }
        }

        lastWakeupValid = dut.io.wakeupToIQ(0).valid.peek().litToBoolean
        lastPdest       = dut.io.wakeupToIQ(0).bits.pdest.peek().litValue.toInt
        lastIs0Lat      = dut.io.wakeupToIQ(0).bits.is0Lat.peek().litToBoolean
        dut.clock.step()
        cycle += 1
      }

      producerDeq should be >= 0
      consumerDeq should be >= 0
      (consumerDeq - producerDeq) shouldBe 2
    }
  }
}
