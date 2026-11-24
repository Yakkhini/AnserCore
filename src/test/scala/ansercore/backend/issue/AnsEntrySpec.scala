package ansercore.backend.issue

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import IssueTestUtils.zero

class AnsEntrySpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams(loadDependencyWidth = 8)
  private val iq     = AnsIssueParams.intScheduler.iqs.head.copy(
    exus       = Seq(AnsIssueParams.intExus.head),
    numEntries = 4,
  )

  private def init(dut: AnsEntry, ready: Boolean = false): Unit = {
    Seq(
      dut.io.enq,
      dut.io.flush,
      dut.io.wakeUpFromWB,
      dut.io.wakeUpFromIQ,
      dut.io.enqWakeUpWB1,
      dut.io.enqWakeUpIQ1,
      dut.io.data0Cancel,
      dut.io.data1Cancel,
      dut.io.enqData0Cancel1,
      dut.io.enqLdCancel1,
      dut.io.ldCancel,
      dut.io.data0Resp,
      dut.io.data1Resp,
      dut.io.deqSel,
      dut.io.deqPortIdxWrite,
      dut.io.transSel,
    ).foreach(zero)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    val status = dut.io.enq.bits.status
    status.robIdx.poke(7.U)
    status.fuType.poke(FuType.alu.U)
    status.issueTimer.poke(AnsIssueTimer.saturated.U)
    status.srcStatus.foreach { src =>
      src.srcType.poke(SrcType.none.U)
      src.srcState.poke(SourceState.ready.U)
      src.dataSources.poke(DataSource.registerFile.U)
    }
    val src    = status.srcStatus(0)
    src.psrc.poke(5.U)
    src.srcType.poke(SrcType.integer.U)
    src.srcState.poke((if (ready) SourceState.ready else SourceState.waiting).U)
    src.srcLoadDependency(0).poke(3.U)
    src.exuSources.poke(4.U)
    dut.io.enq.valid.poke(true.B)
    dut.io.enqReady.expect(true.B)
    dut.clock.step()
    dut.io.enq.valid.poke(false.B)
  }

  private def wake(w: Valid[AnsIqWakeUp], dependency: Int, zeroLatency: Boolean = false): Unit = {
    w.valid.poke(true.B)
    w.bits.pdest.poke(5.U)
    w.bits.intWen.poke(true.B)
    w.bits.is0Lat.poke(zeroLatency.B)
    w.bits.loadDependency(0).poke(dependency.U)
  }

  private def writeback(w: Valid[AnsWbWakeUp]): Unit = {
    w.valid.poke(true.B)
    w.bits.pdest.poke(5.U)
    w.bits.intWen.poke(true.B)
  }

  behavior of "AnsEntry"

  for (isEnq <- Seq(true, false)) {
    it should s"issue on the IQ broadcast cycle and age the saved source (enq=$isEnq)" in {
      test(new AnsEntry(params, iq, isEnq)) { dut =>
        init(dut)
        wake(dut.io.wakeUpFromIQ(0), 1)
        wake(dut.io.wakeUpFromIQ(1), 4)
        dut.io.canIssue.expect(true.B)
        dut.io.dataSources(0).expect(DataSource.forward.U)
        dut.io.exuSources(0).expect((iq.wakeupSourceExuIdx.head + 1).U)
        dut.io.loadDependency(0).expect(2.U)
        dut.io.transEntry.bits.status.srcStatus(0).dataSources.expect(DataSource.bypass.U)
        dut.clock.step()
        zero(dut.io.wakeUpFromIQ)
        dut.io.dataSources(0).expect(DataSource.bypass.U)
        dut.io.loadDependency(0).expect(4.U)
        dut.clock.step()
        dut.io.dataSources(0).expect(DataSource.registerFile.U)
        dut.io.loadDependency(0).expect(8.U)
      }
    }
  }

  it should "form D1 current state before current wakeup and age its dependency once per boundary" in {
    test(new AnsEntry(params, iq, isEnq = true)) { dut =>
      init(dut)
      wake(dut.io.enqWakeUpIQ1(1), 1)
      dut.io.canIssue.expect(true.B)
      dut.io.dataSources(0).expect(DataSource.bypass.U)
      dut.io.loadDependency(0).expect(4.U)
      wake(dut.io.wakeUpFromIQ(0), 8)
      dut.io.dataSources(0).expect(DataSource.forward.U)
      dut.io.exuSources(0).expect((iq.wakeupSourceExuIdx.head + 1).U)
      dut.io.loadDependency(0).expect(16.U)
      dut.clock.step()
      zero(dut.io.wakeUpFromIQ)
      dut.io.dataSources(0).expect(DataSource.bypass.U)
      dut.clock.step()
      dut.io.dataSources(0).expect(DataSource.registerFile.U)
      init(dut)
      dut.clock.step()
      wake(dut.io.enqWakeUpIQ1(1), 1)
      writeback(dut.io.enqWakeUpWB1(0))
      dut.io.canIssue.expect(false.B)
    }
  }

  it should "filter D1 data0 and load cancellation in the matching cycle" in {
    test(new AnsEntry(params, iq, isEnq = true)) { dut =>
      for (cancel <- 0 until 3) {
        init(dut)
        wake(dut.io.enqWakeUpIQ1(0), 1, zeroLatency = true)
        if (cancel == 0) dut.io.enqData0Cancel1(iq.wakeupSourceExuIdx.head).poke(true.B)
        if (cancel == 1) dut.io.enqLdCancel1(0).first.poke(true.B)
        if (cancel == 2) dut.io.ldCancel(0).second.poke(true.B)
        dut.io.canIssue.expect(false.B)
        dut.clock.step()
        zero(dut.io.enqWakeUpIQ1)
        dut.io.canIssue.expect(false.B)
      }
      init(dut)
      writeback(dut.io.enqWakeUpWB1(0))
      wake(dut.io.enqWakeUpIQ1(0), 1)
      dut.io.canIssue.expect(true.B)
      dut.io.dataSources(0).expect(DataSource.registerFile.U)
      dut.io.loadDependency(0).expect(0.U)
    }
  }

  it should "allow a raw zero latency attempt without saving cancelled ready" in {
    test(new AnsEntry(params, iq, isEnq = false)) { dut =>
      init(dut)
      wake(dut.io.wakeUpFromIQ(0), 0, zeroLatency = true)
      dut.io.data0Cancel(iq.wakeupSourceExuIdx.head).poke(true.B)
      dut.io.canIssue.expect(true.B)
      dut.io.dataSources(0).expect(DataSource.forward.U)
      dut.io.transEntry.bits.status.srcStatus(0).srcState.expect(SourceState.waiting.U)
      dut.clock.step()
      zero(dut.io.wakeUpFromIQ)
      dut.io.canIssue.expect(false.B)
    }
  }

  it should "keep ready through consumer retries and unrelated later producer cancellations" in {
    test(new AnsEntry(params, iq, isEnq = false)) { dut =>
      init(dut)
      wake(dut.io.wakeUpFromIQ(0), 0)
      dut.io.deqSel.poke(true.B)
      dut.clock.step()
      zero(dut.io.wakeUpFromIQ)
      dut.io.deqSel.poke(false.B)
      dut.io.data0Resp(0).valid.poke(true.B)
      dut.io.data0Resp(0).bits.resp.poke(IssueRespType.block.U)
      dut.clock.step()
      zero(dut.io.data0Resp)
      dut.io.data0Cancel(iq.wakeupSourceExuIdx.head).poke(true.B)
      dut.io.data1Cancel(iq.wakeupSourceExuIdx.head).poke(true.B)
      dut.io.canIssue.expect(true.B)
      dut.io.dataSources(0).expect(DataSource.registerFile.U)
      for (_ <- 0 until 3) {
        dut.io.deqSel.poke(true.B)
        dut.clock.step()
        dut.io.deqSel.poke(false.B)
        dut.clock.step()
        dut.io.data1Resp(0).valid.poke(true.B)
        dut.io.data1Resp(0).bits.resp.poke(IssueRespType.block.U)
        dut.clock.step()
        zero(dut.io.data1Resp)
        dut.io.canIssue.expect(true.B)
        dut.io.dataSources(0).expect(DataSource.registerFile.U)
      }
    }
  }

  it should "register ordinary WB wakeup and clear dependencies without same cycle selection" in {
    test(new AnsEntry(params, iq, isEnq = false)) { dut =>
      init(dut)
      writeback(dut.io.wakeUpFromWB(0))
      dut.io.canIssue.expect(false.B)
      dut.clock.step()
      zero(dut.io.wakeUpFromWB)
      dut.io.canIssue.expect(true.B)
      dut.io.loadDependency(0).expect(0.U)
    }
  }

  it should "cancel current IQ dependencies and revoke saved ready on load cancellation" in {
    test(new AnsEntry(params, iq, isEnq = false)) { dut =>
      init(dut)
      wake(dut.io.wakeUpFromIQ(0), 1)
      dut.io.ldCancel(0).first.poke(true.B)
      dut.io.cancelBypass.expect(true.B)
      dut.io.transEntry.bits.status.srcStatus(0).srcState.expect(SourceState.waiting.U)
      zero(dut.io.ldCancel)
      dut.clock.step()
      zero(dut.io.wakeUpFromIQ)
      dut.io.ldCancel(0).second.poke(true.B)
      dut.io.cancelBypass.expect(true.B)
      dut.clock.step()
      zero(dut.io.ldCancel)
      dut.io.canIssue.expect(false.B)
    }
  }

  it should "select cancelled and valid IQ candidates with coherent producer metadata" in {
    test(new AnsEntry(params, iq, isEnq = false)) { dut =>
      for (loadCancel <- Seq(false, true)) {
        init(dut)
        wake(dut.io.wakeUpFromIQ(0), 1, zeroLatency = true)
        wake(dut.io.wakeUpFromIQ(1), 4)
        if (loadCancel) dut.io.ldCancel(0).first.poke(true.B)
        else dut.io.data0Cancel(iq.wakeupSourceExuIdx.head).poke(true.B)
        dut.io.canIssue.expect(true.B)
        dut.io.cancelBypass.expect(false.B)
        dut.io.exuSources(0).expect((iq.wakeupSourceExuIdx(1) + 1).U)
        dut.io.loadDependency(0).expect(8.U)
        dut.clock.step(); zero(dut.io.wakeUpFromIQ)
        dut.io.dataSources(0).expect(DataSource.bypass.U)
        dut.io.exuSources(0).expect((iq.wakeupSourceExuIdx(1) + 1).U)
        dut.io.loadDependency(0).expect(16.U)
      }
    }
  }

  it should "retain issue history across a block and consume single-port data1 responses" in {
    test(new AnsEntry(params, iq, isEnq = false)) { dut =>
      init(dut, ready = true)
      dut.io.isFirstIssue.expect(true.B)
      dut.io.deqSel.poke(true.B)
      dut.clock.step()
      dut.io.deqSel.poke(false.B)
      dut.io.isFirstIssue.expect(false.B)
      dut.io.issueTimerRead.expect(AnsIssueTimer.data0.U)
      dut.io.data0Resp(0).valid.poke(true.B)
      dut.io.data0Resp(0).bits.resp.poke(IssueRespType.block.U)
      dut.clock.step()
      dut.io.data0Resp(0).valid.poke(false.B)
      dut.io.issued.expect(false.B)
      dut.io.canIssue.expect(true.B)
      dut.io.isFirstIssue.expect(false.B)

      dut.io.deqSel.poke(true.B)
      dut.clock.step()
      dut.io.deqSel.poke(false.B)
      dut.clock.step()
      dut.io.issueTimerRead.expect(AnsIssueTimer.data1.U)
      dut.io.data1Resp(0).valid.poke(true.B)
      dut.io.data1Resp(0).bits.resp.poke(IssueRespType.success.U)
      dut.clock.step()
      dut.io.valid.expect(false.B)
    }
  }

  it should "treat uncertain as 'stage passed but not final' without clearing or re-issuing" in {
    test(new AnsEntry(params, iq, isEnq = false)) { dut =>
      init(dut, ready = true)
      dut.io.deqSel.poke(true.B)
      dut.clock.step()
      dut.io.deqSel.poke(false.B)
      dut.io.issueTimerRead.expect(AnsIssueTimer.data0.U)

      // data0 返回 uncertain：条目保持在飞行中，timer 推进到 data1。
      dut.io.data0Resp(0).valid.poke(true.B)
      dut.io.data0Resp(0).bits.resp.poke(IssueRespType.uncertain.U)
      dut.clock.step()
      dut.io.data0Resp(0).valid.poke(false.B)
      dut.io.issued.expect(true.B)
      dut.io.valid.expect(true.B)
      dut.io.canIssue.expect(false.B)
      dut.io.issueTimerRead.expect(AnsIssueTimer.data1.U)

      // data1 再返回 uncertain：仍然保持，不清除、不重发。
      dut.io.data1Resp(0).valid.poke(true.B)
      dut.io.data1Resp(0).bits.resp.poke(IssueRespType.uncertain.U)
      dut.clock.step()
      dut.io.data1Resp(0).valid.poke(false.B)
      dut.io.issued.expect(true.B)
      dut.io.valid.expect(true.B)
      dut.io.canIssue.expect(false.B)
    }
  }
}
