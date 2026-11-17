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
      dut.io.enqWakeUpWB2,
      dut.io.enqWakeUpIQ1,
      dut.io.enqWakeUpIQ2,
      dut.io.data0Cancel,
      dut.io.enqData0Cancel1,
      dut.io.enqData0Cancel2,
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

  it should "choose one current IQ producer with matching metadata when wakeups overlap" in {
    test(new AnsEntry(params, iq, isEnq = true)) { dut =>
      init(dut)
      wake(dut.io.wakeUpFromIQ(0), dependency = 1)
      wake(dut.io.wakeUpFromIQ(1), dependency = 2)
      writeback(dut.io.wakeUpFromWB.last)
      wake(dut.io.enqWakeUpIQ1(2), dependency = 8)
      dut.clock.step()
      dut.io.canIssue.expect(true.B)
      dut.io.dataSources(0).expect(DataSource.bypass2.U)
      dut.io.exuSources(0).expect((iq.wakeupSourceExuIdx.head + 1).U)
      dut.io.loadDependency(0).expect(1.U)

      Seq(dut.io.wakeUpFromIQ, dut.io.wakeUpFromWB, dut.io.enqWakeUpIQ1).foreach(zero)
      dut.clock.step()
      dut.io.dataSources(0).expect(DataSource.registerFile.U)
      dut.io.loadDependency(0).expect(2.U)
    }
  }

  it should "prefer confirmed delayed writeback over speculative delayed IQ metadata" in {
    test(new AnsEntry(params, iq, isEnq = true)) { dut =>
      for (delay <- 1 to 2) {
        init(dut)
        val wb = if (delay == 1) dut.io.enqWakeUpWB1 else dut.io.enqWakeUpWB2
        writeback(wb.last)
        wake(dut.io.enqWakeUpIQ1(1), dependency = 8)
        dut.io.canIssue.expect(true.B)
        dut.clock.step()
        dut.io.dataSources(0).expect(DataSource.registerFile.U)
        dut.io.exuSources(0).expect(0.U)
        dut.io.loadDependency(0).expect(0.U)
      }
    }
  }

  it should "prefer the newer delayed IQ candidate and ignore history outside the enqueue window" in {
    test(new AnsEntry(params, iq, isEnq = true)) { dut =>
      init(dut)
      wake(dut.io.enqWakeUpIQ1(0), dependency = 4)
      wake(dut.io.enqWakeUpIQ2(1), dependency = 8)
      dut.clock.step()
      dut.io.dataSources(0).expect(DataSource.registerFile.U)
      dut.io.exuSources(0).expect((iq.wakeupSourceExuIdx.head + 1).U)
      dut.io.loadDependency(0).expect(4.U)

      init(dut)
      dut.clock.step(2)
      wake(dut.io.enqWakeUpIQ1(0), dependency = 4)
      writeback(dut.io.enqWakeUpWB2.last)
      dut.clock.step()
      dut.io.canIssue.expect(false.B)
    }
  }

  it should "exclude cancelled IQ producers before choosing the winner" in {
    test(new AnsEntry(params, iq, isEnq = true)) { dut =>
      for (loadCancel <- Seq(false, true)) {
        init(dut)
        wake(dut.io.wakeUpFromIQ(0), dependency = 1, zeroLatency = true)
        wake(dut.io.wakeUpFromIQ(1), dependency = 4)
        if (loadCancel) dut.io.ldCancel(0).first.poke(true.B)
        else dut.io.data0Cancel(iq.wakeupSourceExuIdx.head).poke(true.B)
        dut.clock.step()
        dut.io.dataSources(0).expect(DataSource.bypass2.U)
        dut.io.exuSources(0).expect((iq.wakeupSourceExuIdx(1) + 1).U)
        dut.io.loadDependency(0).expect(4.U)
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
      dut.io.data0Resp(0).bits.ready.poke(false.B)
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
      dut.io.data1Resp(0).bits.ready.poke(true.B)
      dut.clock.step()
      dut.io.valid.expect(false.B)
    }
  }
}
