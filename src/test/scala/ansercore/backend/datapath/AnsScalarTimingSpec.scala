package ansercore.backend.datapath

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer

import ansercore.backend.Bundles._
import ansercore.backend.issue._
import ansercore.backend.rename.AnsRenameRedirect
import IssueTestUtils.zero

/** Real scalar path with production IQ capacities, arbitration, reservations, migration and actual PRF writes. No
  * modeled wakeup/data delay.
  */
class AnsScalarTimingHarness extends Module {
  val p       = BundleParams(loadDependencyWidth = 8)
  val configs = AnsIssueParams.allIQs
  val n       = AnsIssueParams.nExu
  val io      = IO(new Bundle {
    val enq                  = Vec(configs.size, Vec(2, Flipped(Decoupled(new DynInst(p)))))
    val flush                = Input(Valid(new AnsRenameRedirect(p)))
    val ldCancel             = Input(Vec(p.loadPipelineWidth, new LoadCancel))
    val holdSelection        = Input(Vec(n, Bool()))
    val refuseUncertainData1 = Input(Vec(n, Bool()))
    // Test-only dependency injection on a real registered wakeup; no predicted LSU wakeup is enabled.
    val wakeDependency       = Input(Vec(n, Vec(p.loadPipelineWidth, UInt(p.loadDependencyWidth.W))))
    // Seed a fresh dependency at data0 to test the future LSU late-cancel boundary.
    val data0Dependency      = Input(Vec(n, Vec(p.loadPipelineWidth, UInt(p.loadDependencyWidth.W))))
    val holdUncertainInput   = Input(Vec(n, Bool()))
    val holdUncertainResult  = Input(Vec(n, Bool()))
    val seedInt              = Input(new RegisterWriteBundle(p.physicalRegWidth, p.xlen))
    val seedFp               = Input(new RegisterWriteBundle(p.physicalRegWidth, p.xlen))
    val wakeup               = Output(Vec(n, Valid(new AnsIqWakeUp(p))))
    val data0                = Output(Vec(n, Valid(new ExuInput(p, ExecutionUnitParams(numRegSrc = p.numSrc)))))
    val data1                = Output(Vec(n, Valid(new ExuInput(p, ExecutionUnitParams(numRegSrc = p.numSrc)))))
    val execute              = Output(Vec(n, Valid(new ExuInput(p, ExecutionUnitParams(numRegSrc = p.numSrc)))))
    val uncertainExecute     = Output(Vec(2, Valid(new ExuInput(p, ExecutionUnitParams(numRegSrc = p.numSrc)))))
    val response             = Output(new AnsDataPathToIqIO(p))
    val intWb                = Output(Vec(AnsIssueParams.nIntWb, new RegisterWriteBundle(p.physicalRegWidth, p.xlen)))
    val fpWb                 = Output(Vec(AnsIssueParams.nFpWb, new RegisterWriteBundle(p.physicalRegWidth, p.xlen)))
    val robWb                = Output(Vec(AnsIssueParams.nCompletion, Valid(UInt(p.robIndexWidth.W))))
    val counts               = Output(Vec(configs.size, UInt(AnsIssueParams.iqValidNumWidth.W)))
  })
  val queues  = configs.map(c => Module(new AnsIssueQueue(p, c)))
  val dp      = Module(new AnsDataPath(p))
  val bn      = Module(new AnsBypassNetwork(p))
  val exu     = Module(new AnsExuBlockStub(p))
  val wb      = Module(new AnsWbDataPath(p))
  val pipes   = AnsIssueParams.allExus.map(e => Module(new AnsExuInputPipe(p, e)))
  val wakeups = Wire(Vec(n, Valid(new AnsIqWakeUp(p))))
  dp.io.flush        := io.flush
  dp.io.ldCancel     := io.ldCancel
  dp.io.pcMemWrite   := 0.U.asTypeOf(dp.io.pcMemWrite)
  exu.io.flush       := io.flush
  wb.io.flush        := io.flush
  wb.io.fromLsu      := 0.U.asTypeOf(wb.io.fromLsu)
  bn.io.rcData       := dp.io.rcData
  bn.io.fromExu      := exu.io.out
  dp.io.intWriteBack := wb.io.toIntRf
  dp.io.fpWriteBack  := wb.io.toFpRf
  when(io.seedInt.wen)(dp.io.intWriteBack(0) := io.seedInt)
  when(io.seedFp.wen)(dp.io.fpWriteBack(0)   := io.seedFp)
  io.intWb           := wb.io.toIntRf
  io.fpWb            := wb.io.toFpRf
  io.wakeup          := wakeups
  io.response        := dp.io.toIq

  for (e <- 0 until n) {
    val ep = AnsIssueParams.exuByIndex(e)
    io.robWb(e).valid     := wb.io.toRob(e).valid
    io.robWb(e).bits      := wb.io.toRob(e).bits.robIdx
    bn.io.fromDataPath(e) <> dp.io.toBypassNetwork(e)
    pipes(e).io.flush     := io.flush
    val refuse = io.refuseUncertainData1(e) && ep.isUncertainFu(bn.io.toExu(e).bits.fuType)
    pipes(e).io.in.valid := bn.io.toExu(e).valid && !refuse
    pipes(e).io.in.bits  := bn.io.toExu(e).bits
    bn.io.toExu(e).ready := pipes(e).io.in.ready && !refuse
    val holdIn = io.holdUncertainInput(e) && ep.isUncertainFu(pipes(e).io.out.bits.fuType)
    exu.io.in(e).valid    := pipes(e).io.out.valid && !holdIn
    exu.io.in(e).bits     := pipes(e).io.out.bits
    pipes(e).io.out.ready := exu.io.in(e).ready && !holdIn
    val holdOut = io.holdUncertainResult(e) && ep.isUncertainFu(exu.io.out(e).bits.fuType)
    wb.io.fromExu(e)       := exu.io.out(e)
    wb.io.fromExu(e).valid := exu.io.out(e).valid && !holdOut
    exu.io.outReady(e)     := wb.io.fromExuReady(e) && !holdOut
    io.data0(e).valid      := dp.io.fromIQ(e).valid
    io.data0(e).bits       := dp.io.fromIQ(e).bits.common
    io.data1(e).valid      := bn.io.toExu(e).fire
    io.data1(e).bits       := bn.io.toExu(e).bits
    io.execute(e).valid    := exu.io.in(e).fire
    io.execute(e).bits     := exu.io.in(e).bits
  }
  val owner = AnsIssueParams.uncertainOwner.exuIdx
  for (i <- 0 until 2) {
    val in  = exu.io.uncertainIn(i)
    val out = pipes(owner).io.uncertainOut(i)
    in.valid                     := out.valid && !io.holdUncertainInput(owner)
    in.bits                      := out.bits
    out.ready                    := in.ready && !io.holdUncertainInput(owner)
    io.uncertainExecute(i).valid := in.fire
    io.uncertainExecute(i).bits  := in.bits
  }
  wb.io.fromUncertain.valid := exu.io.uncertainOut.valid && !io.holdUncertainResult(owner)
  wb.io.fromUncertain.bits  := exu.io.uncertainOut.bits
  exu.io.uncertainOut.ready := wb.io.fromUncertain.ready && !io.holdUncertainResult(owner)
  io.robWb(n).valid         := wb.io.toRob(n).valid
  io.robWb(n).bits          := wb.io.toRob(n).bits.robIdx
  val wbWake = Wire(Vec(AnsIssueParams.nIntWb + AnsIssueParams.nFpWb, Valid(new AnsWbWakeUp(p))))
  for ((w, k) <- wbWake.zipWithIndex) {
    val int  = k < AnsIssueParams.nIntWb
    val port = if (int) wb.io.toIntRf(k) else wb.io.toFpRf(k - AnsIssueParams.nIntWb)
    w.valid       := port.wen
    w.bits.intWen := int.B
    w.bits.fpWen  := (!int).B
    w.bits.pdest  := port.addr
  }
  for (((q, config), k) <- queues.zip(configs).zipWithIndex) {
    q.io.enq          <> io.enq(k)
    q.io.flush        := io.flush
    q.io.ldCancel     := io.ldCancel
    q.io.data0Cancel  := dp.io.toIq.data0Cancel
    q.io.data1Cancel  := dp.io.toIq.data1Cancel
    q.io.wakeUpFromWB := wbWake
    io.counts(k)      := q.io.status.validCnt
    config.wakeupSourceExuIdx.zipWithIndex.foreach { case (e, w) => q.io.wakeUpFromIQ(w) := wakeups(e) }
    config.exus.zipWithIndex.foreach { case (ep, d) =>
      val e = ep.exuIdx
      q.io.data0Resp(d)                               := dp.io.toIq.data0Resp(e)
      q.io.data1Resp(d)                               := dp.io.toIq.data1Resp(e)
      q.io.exuBusy(d)                                 := Mux(
        io.holdSelection(e),
        Fill(FuType.width, 1.U),
        exu.io.uncertainBusy(e) | pipes(e).io.uncertainBusy,
      )
      q.io.wbBusyTableRead(d)                         := dp.io.wbBusyTableRead(e)
      when(io.holdSelection(e)) {
        q.io.wbBusyTableRead(d).intWbBusyTable := Fill(p.maxLatency + 1, 1.U)
        q.io.wbBusyTableRead(d).fpWbBusyTable  := Fill(p.maxLatency + 1, 1.U)
      }
      dp.io.wbBusyTableWrite(e)                       := q.io.wbBusyTableWrite(d)
      dp.io.fromIQ(e)                                 <> q.io.deq(d)
      for (l <- 0 until p.loadPipelineWidth)
        dp.io.fromIQ(e).bits.common.loadDependency(l) := q.io.deq(d).bits.common.loadDependency(l) | io.data0Dependency(
          e
        )(l)
      wakeups(e)                                      := q.io.wakeupToIQ(d)
      for (l <- 0 until p.loadPipelineWidth)
        wakeups(e).bits.loadDependency(l)             := q.io.wakeupToIQ(d).bits.loadDependency(l) | io.wakeDependency(e)(l)
    }
  }
}

class AnsScalarTimingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "scalar issue to writeback timing"
  case class Event(stage: String, cycle: Int, exu: Int, rob: Int, dest: Int, src: BigInt, data: BigInt, source: Int)

  it should "deliver correct operands across latency windows, chains, retries and redirects" in {
    test(new AnsScalarTimingHarness).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val events                                              = ArrayBuffer.empty[Event]
      var cycle                                               = 0
      def sample(): Unit                                      = {
        dut.io.intWb(4).wen.expect(false.B)
        dut.io.intWb(5).wen.expect(false.B)
        for ((ports, stage) <- Seq(dut.io.data0 -> "data0", dut.io.data1 -> "data1", dut.io.execute -> "execute")) {
          for ((port, e) <- ports.zipWithIndex if port.valid.peek().litToBoolean) {
            val b = port.bits
            events += Event(
              stage,
              cycle,
              e,
              b.robIdx.peek().litValue.toInt,
              b.pdest.peek().litValue.toInt,
              b.src(0).peek().litValue,
              b.src(1).peek().litValue,
              b.dataSources(0).peek().litValue.toInt,
            )
          }
        }
        for ((w, e) <- dut.io.wakeup.zipWithIndex if w.valid.peek().litToBoolean)
          events += Event(
            "wake",
            cycle,
            e,
            w.bits.robIdx.peek().litValue.toInt,
            w.bits.pdest.peek().litValue.toInt,
            0,
            0,
            0,
          )
        for ((ports, stage) <- Seq(dut.io.intWb -> "intWb", dut.io.fpWb -> "fpWb")) {
          for ((w, e) <- ports.zipWithIndex if w.wen.peek().litToBoolean)
            events += Event(stage, cycle, e, 0, w.addr.peek().litValue.toInt, 0, w.data.peek().litValue, 0)
        }
        for (in <- dut.io.uncertainExecute if in.valid.peek().litToBoolean) {
          val b = in.bits
          events += Event(
            "execute",
            cycle,
            1,
            b.robIdx.peek().litValue.toInt,
            b.pdest.peek().litValue.toInt,
            b.src(0).peek().litValue,
            b.src(1).peek().litValue,
            b.dataSources(0).peek().litValue.toInt,
          )
        }
        if (dut.io.robWb(AnsIssueParams.nExu).valid.peek().litToBoolean)
          events += Event(
            "robWb",
            cycle,
            AnsIssueParams.nExu,
            dut.io.robWb(AnsIssueParams.nExu).bits.peek().litValue.toInt,
            0,
            0,
            0,
            0,
          )
        for (e <- 0 until AnsIssueParams.nExu) {
          if (dut.io.robWb(e).valid.peek().litToBoolean)
            events += Event("robWb", cycle, e, dut.io.robWb(e).bits.peek().litValue.toInt, 0, 0, 0, 0)
          if (dut.io.response.data0Cancel(e).peek().litToBoolean)
            events += Event("block0", cycle, e, dut.io.data0(e).bits.robIdx.peek().litValue.toInt, 0, 0, 0, 0)
          if (dut.io.response.data1Cancel(e).peek().litToBoolean)
            events += Event(
              "block1",
              cycle,
              e,
              dut.io.response.data1Resp(e).bits.robIdx.peek().litValue.toInt,
              0,
              0,
              0,
              0,
            )
        }
      }
      def tick(n: Int = 1): Unit                              = for (_ <- 0 until n) { sample(); dut.clock.step(); cycle += 1 }
      def init(): Unit                                        = {
        Seq(
          dut.io.flush,
          dut.io.ldCancel,
          dut.io.refuseUncertainData1,
          dut.io.wakeDependency,
          dut.io.data0Dependency,
          dut.io.holdSelection,
          dut.io.holdUncertainInput,
          dut.io.holdUncertainResult,
          dut.io.seedInt,
          dut.io.seedFp,
        ).foreach(zero)
        dut.io.enq.foreach(_.foreach { in => in.valid.poke(false.B); zero(in.bits) })
        dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
        for ((addr, value) <- Seq(1 -> 11, 2 -> 7)) {
          for (w <- Seq(dut.io.seedInt, dut.io.seedFp)) {
            w.wen.poke(true.B); w.addr.poke(addr.U); w.data.poke(value.U)
          }
          dut.clock.step()
        }
        zero(dut.io.seedInt); zero(dut.io.seedFp)
        events.clear(); cycle = 0
      }
      def drive(
        q:        Int,
        slot:     Int,
        fu:       Int,
        rob:      Int,
        dest:     Int,
        floating: Boolean = false,
        src0:     Int     = 1,
        waiting:  Boolean = false,
        src1:     Int     = 2,
        writes:   Boolean = true,
      ): Unit                                                 = {
        val in = dut.io.enq(q)(slot)
        zero(in.bits)
        val u  = in.bits
        u.fuType.poke(fu.U); u.robIdx.poke(rob.U); u.pdest.poke(dest.U)
        u.intWen.poke((writes && !floating).B); u.fpWen.poke((writes && floating).B)
        for (s <- 0 until 3) {
          u.srcType(s)
            .poke((if (s < 2) {
                     if (floating) SrcType.floating else SrcType.integer
                   } else SrcType.none).U)
          u.srcState(s).poke((if (s == 0 && waiting) SourceState.waiting else SourceState.ready).U)
        }
        u.psrc(0).poke(src0.U); u.psrc(1).poke(src1.U)
        in.valid.poke(true.B)
        withClue(s"q=$q rob=$rob cycle=$cycle recent=" + events.takeRight(16)) {
          in.ready.peek().litToBoolean shouldBe true
        }
      }
      def send(): Unit                                        = { tick(); dut.io.enq.foreach(_.foreach(_.valid.poke(false.B))) }
      def one(stage: String, rob: Int): Event                 = {
        val found = events.filter(e => e.stage == stage && e.rob == rob)
        withClue(s"$stage rob=$rob: $events")(found.size shouldBe 1)
        found.head
      }
      def result(dest: Int, floating: Boolean = false): Event = {
        val found = events.filter(e => e.stage == (if (floating) "fpWb" else "intWb") && e.dest == dest)
        withClue(s"WB dest=$dest: $events")(found.size shouldBe 1)
        found.head
      }

      // Same-cycle wake/selection, then bypass and PRF. All values come from real EXU/PRF.
      for {
        (lat, fu, pe, pq, floating) <- Seq(
                                         (0, FuType.alu, 0, 0, false),
                                         (1, FuType.falu, 6, 3, true),
                                         (2, FuType.mul, 1, 0, false),
                                         (3, FuType.fmac, 6, 3, true),
                                       )
        offset                      <- 0 to 3
      } {
        init()
        val ce        = if (floating) 8 else 2
        val cq        = if (floating) 4 else 1
        dut.io.holdSelection(ce).poke(true.B)
        drive(pq, 0, fu, 1, 10, floating)
        drive(cq, 0, if (floating) FuType.falu else FuType.alu, 2, 11, floating, src0 = 10, waiting = true)
        send()
        val wakeCycle = lat + 2
        while (cycle < wakeCycle + offset) tick()
        dut.io.holdSelection(ce).poke(false.B)
        tick(12)
        val d0        = one("data0", 1)
        one("wake", 1).cycle shouldBe d0.cycle + lat
        one("data1", 1).cycle shouldBe d0.cycle + 1
        one("execute", 1).cycle shouldBe d0.cycle + 2
        result(10, floating).cycle shouldBe d0.cycle + lat + 2
        result(10, floating).data shouldBe 18
        one("data0", 2).cycle shouldBe wakeCycle + offset + 1
        one("data0", 2).source shouldBe (if (offset == 0) DataSource.forward
                                         else if (offset == 1) DataSource.bypass
                                         else DataSource.registerFile)
        one("data1", 2).src shouldBe 18
        one("execute", 2).src shouldBe 18
        result(11, floating).data shouldBe 25
      }

      // Three 0-lat operations selected on consecutive cycles, including same-EXU reuse.
      init()
      drive(0, 0, FuType.alu, 1, 10)
      drive(0, 1, FuType.alu, 2, 11, src0 = 10, waiting = true)
      drive(1, 0, FuType.alu, 3, 12, src0 = 11, waiting = true)
      send(); tick(14)
      one("data0", 2).cycle shouldBe one("data0", 1).cycle + 1
      one("data0", 3).cycle shouldBe one("data0", 2).cycle + 1
      result(10).data shouldBe 18; result(11).data shouldBe 25; result(12).data shouldBe 32

      // A zero-lat BJU loses a real PRF read to ALU1. Its consumers and successor retry.
      init()
      drive(2, 0, FuType.jmp, 1, 10)
      drive(1, 0, FuType.alu, 4, 20)
      drive(0, 0, FuType.alu, 2, 11, src0 = 10, waiting = true)
      drive(2, 1, FuType.alu, 3, 12, src0 = 11, waiting = true)
      send(); tick(20)
      for (rob <- 1 to 3) events.exists(e => e.stage == "block0" && e.rob == rob) shouldBe true
      one("execute", 1).src shouldBe 11
      one("execute", 2).src shouldBe 18
      one("execute", 3).src shouldBe 25
      result(12).data shouldBe 32

      // Consumer repeatedly loses RD5 after a successful producer; retry reads the committed value.
      init()
      drive(0, 0, FuType.alu, 1, 10)
      drive(0, 1, FuType.mul, 2, 11, src0 = 10, waiting = true)
      send()
      for (r <- 20 until 26) { drive(2, 0, FuType.alu, r, r + 20); send() }
      tick(20)
      events.count(e => e.stage == "block0" && e.rob == 2) should be >= 2
      one("execute", 2).src shouldBe 18; result(11).data shouldBe 25

      // An uncertain consumer may retry data1 repeatedly as forward/bypass ages into PRF.
      init(); dut.io.refuseUncertainData1(1).poke(true.B)
      drive(0, 0, FuType.alu, 1, 10)
      drive(0, 1, FuType.div, 2, 11, src0 = 10, waiting = true)
      send(); tick(13)
      events.count(e => e.stage == "block1" && e.rob == 2) should be >= 3
      dut.io.refuseUncertainData1(1).poke(false.B); tick(12)
      one("execute", 2).src shouldBe 18; result(11).data shouldBe 25

      // Two adjacent DIVs reach data1 before the occupancy feedback can prevent
      // selection: the second must retry the actual full request slot.
      init(); dut.io.holdUncertainInput(1).poke(true.B)
      drive(0, 0, FuType.div, 1, 10); send()
      drive(0, 0, FuType.div, 2, 11); send(); tick(8)
      events.exists(e => e.stage == "block1" && e.rob == 2) shouldBe true
      drive(0, 0, FuType.csr, 3, 12); send(); tick(6)
      one("data1", 3) // CSR request slot is available while DIV is waiting.
      dut.io.holdUncertainInput(1).poke(false.B); tick(20)
      for (dest <- 10 to 12) result(dest).data shouldBe 18
      for (rob <- 1 to 3) one("robWb", rob)
      dut.io.counts.foreach(_.expect(0.U))

      // Current wakeup, D1 history and data0 cancellation all retain the IQ entry until a valid retry.
      for {
        history       <- Seq(false, true)
        cancelAtData0 <- Seq(false, true)
      } {
        init()
        drive(0, 0, FuType.alu, 1, 10)
        if (!history) drive(1, 0, FuType.alu, 2, 11, src0 = 10, waiting = true)
        send(); tick() // producer data0 / registered wakeup at cycle 2
        dut.io.wakeDependency(0)(0).poke(1.U)
        if (history) drive(1, 0, FuType.alu, 2, 11, src0 = 10, waiting = true)
        if (!cancelAtData0) dut.io.ldCancel(0).first.poke(true.B)
        tick(); dut.io.enq.foreach(_.foreach(_.valid.poke(false.B))); zero(dut.io.wakeDependency)
        zero(dut.io.ldCancel)
        if (cancelAtData0) dut.io.ldCancel(0).second.poke(true.B)
        tick(); zero(dut.io.ldCancel); tick(12)
        if (!history && cancelAtData0)
          events.exists(e => e.stage == "block0" && e.rob == 2) shouldBe true
        one("execute", 2).src shouldBe 18; result(11).data shouldBe 25
      }

      // A data1 load cancellation returns block without releasing the IQ entry or sending to EXU.
      // Inject only the dependency at data0; selection, responses, retry, operands and WB stay real.
      init()
      drive(0, 0, FuType.alu, 1, 10)
      drive(1, 0, FuType.alu, 2, 11, src0 = 10, waiting = true)
      send(); while (cycle < 3) tick() // consumer data0
      dut.io.data0Dependency(2)(0).poke(1.U)
      tick(); zero(dut.io.data0Dependency)
      dut.io.ldCancel(0).second.poke(true.B)
      tick(); zero(dut.io.ldCancel)
      dut.io.counts(1).expect(1.U)
      tick(12)
      events.count(e => e.stage == "block1" && e.rob == 2) shouldBe 1
      one("execute", 2).src shouldBe 18; result(11).data shouldBe 25
      dut.io.counts.foreach(_.expect(0.U))

      // Enqueue on the broadcast cycle uses D1 bypass; enqueue on WB uses D1 PRF.
      for (enqCycle <- Seq(2, 4)) {
        init(); drive(0, 0, FuType.alu, 1, 10); send()
        while (cycle < enqCycle) tick()
        drive(1, 0, FuType.alu, 2, 11, src0 = 10, waiting = true); send(); tick(12)
        one("data0", 2).source shouldBe (if (enqCycle == 2) DataSource.bypass else DataSource.registerFile)
        one("execute", 2).src shouldBe 18; result(11).data shouldBe 25
      }

      // F2I deliberately has no IQ wakeup. Consumer waits for actual integer WB.
      init()
      drive(3, 0, FuType.fcvt, 1, 10, floating = true)
      dut.io.enq(3)(0).bits.fpWen.poke(false.B); dut.io.enq(3)(0).bits.intWen.poke(true.B)
      drive(1, 0, FuType.alu, 2, 11, src0      = 10, waiting = true)
      send(); tick(16)
      events.exists(e => e.stage == "wake" && e.rob == 1) shouldBe false
      one("data0", 2).cycle shouldBe result(10).cycle + 2
      one("data1", 2).src shouldBe 18
      result(11).data shouldBe 25

      // Memory consumers use all three windows for integer addresses and floating store data.
      for {
        floating <- Seq(false, true)
        offset   <- 0 to 2
      } {
        init()
        val ce    = if (floating) 12 else 9
        dut.io.holdSelection(ce).poke(true.B)
        drive(if (floating) 3 else 0, 0, if (floating) FuType.falu else FuType.alu, 1, 10, floating)
        drive(
          if (floating) 8 else 5,
          0,
          if (floating) FuType.stu else FuType.ldu,
          2,
          11,
          floating,
          src0    = 10,
          waiting = true,
          writes  = false,
        )
        dut.io.enq(if (floating) 8 else 5)(0).bits.srcType(1).poke(SrcType.none.U)
        send()
        while (cycle < (if (floating) 3 else 2) + offset) tick()
        dut.io.holdSelection(ce).poke(false.B); tick(12)
        one("execute", 2).src shouldBe 18
        one("data0", 2).source shouldBe (if (offset == 0) DataSource.forward
                                         else if (offset == 1) DataSource.bypass
                                         else DataSource.registerFile)
        events.exists(e => e.stage == "wake" && e.rob == 2) shouldBe false
      }

      // A later refused DIV on MUL0 must not cancel consumers of an older MUL0 result.
      init(); dut.io.refuseUncertainData1(1).poke(true.B)
      drive(0, 0, FuType.mul, 1, 10)
      drive(0, 1, FuType.div, 3, 12)
      drive(1, 0, FuType.alu, 2, 11, src0 = 10, waiting = true)
      send(); tick(12)
      events.exists(e => e.stage == "block1" && e.rob == 3) shouldBe true
      events.exists(e => e.stage == "block0" && e.rob == 2) shouldBe false
      one("execute", 2).src shouldBe 18; result(11).data shouldBe 25
      dut.io.refuseUncertainData1(1).poke(false.B); tick(12); result(12).data shouldBe 18

      // Shared IntWB2 reservations retain/clear the actual future slot after data0 acceptance/failure.
      for (cancelMul <- Seq(false, true)) {
        init(); dut.io.holdSelection(4).poke(true.B)
        drive(1, 0, FuType.mul, 1, 10)
        drive(2, 0, FuType.alu, 2, 11)
        if (cancelMul) drive(0, 0, FuType.alu, 3, 12) // ALU0 wins MUL1's RD0/1
        send(); while (cycle < 3) tick()
        dut.io.holdSelection(4).poke(false.B); tick(14)
        result(11).cycle shouldBe (if (cancelMul) 6 else 7)
        result(10).data shouldBe 18; result(11).data shouldBe 18
      }

      // IntWB1: all three fixed owners compete in data0, retry, and write real values.
      init()
      drive(1, 0, FuType.alu, 1, 10)
      drive(0, 0, FuType.mul, 2, 11)
      drive(3, 0, FuType.fcvt, 3, 12, floating = true)
      dut.io.enq(3)(0).bits.intWen.poke(true.B); dut.io.enq(3)(0).bits.fpWen.poke(false.B)
      send(); tick(22)
      for (rob <- 2 to 3) events.exists(e => e.stage == "block0" && e.rob == rob) shouldBe true
      for (dest <- 10 to 12) { result(dest).data shouldBe 18; result(dest).exu shouldBe 1 }

      // Mixed-latency reservations on both fixed shared ports survive acceptance
      // and release after either data0 read conflict or a data1 load cancellation.
      for {
        (mq, me, aq, ae, blockerQ) <- Seq((0, 1, 1, 2, 2), (1, 3, 2, 4, 0))
        cancel                     <- 0 to 2
      } {
        init(); dut.io.holdSelection(ae).poke(true.B)
        drive(mq, 0, FuType.mul, 1, 10)
        drive(aq, 0, FuType.alu, 2, 11)
        if (cancel == 1) drive(blockerQ, 0, FuType.alu, 3, 12)
        send()
        while (cycle < 2) tick()
        if (cancel == 2) dut.io.data0Dependency(me)(0).poke(1.U)
        tick(); zero(dut.io.data0Dependency)
        dut.io.holdSelection(ae).poke(false.B)
        if (cancel == 2) dut.io.ldCancel(0).second.poke(true.B)
        tick(); zero(dut.io.ldCancel); tick(20)
        if (cancel > 0)
          events.exists(e => e.stage == (if (cancel == 1) "block0" else "block1") && e.rob == 1) shouldBe true
        result(10).data shouldBe 18; result(11).data shouldBe 18
        result(10).cycle should not be result(11).cycle
        // data1 cancellation is observed one cycle later than data0 failure.
        result(11).cycle shouldBe (if (cancel == 1) 6 else 7)
        dut.io.counts.foreach(_.expect(0.U))
      }

      // FALU comparison/classification writes int at latency 1 on BOTH FEX0/FEX2.
      // Test real PRF values and ROB events; the arithmetic remains the sum stub.
      for ((fq, fe, aq, ae, mq, port) <- Seq((3, 6, 1, 2, 0, 1), (4, 8, 2, 4, 1, 2))) {
        def compare(): Unit      = {
          drive(fq, 0, FuType.falu, 1, 10, floating = true)
          val u = dut.io.enq(fq)(0).bits
          u.fuOpType.poke(FALUOpType.feqS)
          u.fpWen.poke(false.B); u.intWen.poke(true.B)
        }
        def checkCompare(): Unit = {
          result(10).data shouldBe 18; result(10).exu shouldBe port
          result(10).cycle shouldBe one("execute", 1).cycle + 1
          one("robWb", 1).cycle shouldBe result(10).cycle
          events.exists(e => e.stage == "wake" && e.rob == 1) shouldBe false
          events.exists(e => e.stage == "fpWb" && e.dest == 10) shouldBe false
        }

        // Former lost-write counterexample: FALU then ALU one cycle later.
        init(); compare(); send()
        drive(aq, 0, FuType.alu, 2, 11, src0 = 2); send(); tick(14)
        checkCompare(); result(11).data shouldBe 14
        one("data0", 2).cycle should be > (one("data0", 1).cycle + 1)
        one("robWb", 2).cycle shouldBe result(11).cycle
        result(10).cycle should not be result(11).cycle
        // Read the comparison destination back from the actual integer PRF.
        drive(aq, 0, FuType.alu, 4, 13, src0 = 10); send(); tick(10)
        one("execute", 4).src shouldBe 18; result(13).data shouldBe 25

        // Reverse direction: an older MUL reservation must mask a later FALU.
        init(); drive(mq, 0, FuType.mul, 2, 11, src1 = 1); send()
        compare(); send(); tick(16)
        checkCompare(); result(11).data shouldBe 22
        one("data0", 1).cycle should be > (one("data0", 2).cycle + 1)
        result(10).cycle should not be result(11).cycle

        for (cancelAtData1 <- Seq(false, true)) {
          init(); compare()
          if (!cancelAtData1) drive(aq, 0, FuType.alu, 2, 11, src0 = 2)
          send()
          if (cancelAtData1) {
            while (cycle < 2) tick() // comparison data0
            dut.io.data0Dependency(fe)(0).poke(1.U)
            tick(); zero(dut.io.data0Dependency)
            dut.io.ldCancel(0).second.poke(true.B)
            tick(); zero(dut.io.ldCancel)
            drive(aq, 0, FuType.alu, 2, 11, src0 = 2); send()
          }
          tick(18)
          events.count(e => e.stage == (if (cancelAtData1) "block1" else "block0") && e.rob == 1) shouldBe 1
          events.count(e => e.stage == "data0" && e.rob == 1) shouldBe 2
          checkCompare(); result(11).data shouldBe 14
          result(10).cycle should not be result(11).cycle
          one("robWb", 2)
          dut.io.counts.foreach(_.expect(0.U))
        }

        init(); compare(); send(); tick(2) // redirect at data1
        dut.io.flush.valid.poke(true.B); dut.io.flush.bits.robIdx.poke(1.U)
        dut.io.flush.bits.flushItself.poke(true.B)
        tick(); zero(dut.io.flush); tick(8)
        events.exists(e => e.stage == "intWb" || e.stage == "robWb") shouldBe false
        drive(fq, 0, FuType.falu, 3, 12, floating = true)
        dut.io.enq(fq)(0).bits.fpWen.poke(false.B); dut.io.enq(fq)(0).bits.intWen.poke(true.B)
        send(); tick(10); result(12).data shouldBe 18; one("robWb", 3)
        dut.io.counts.foreach(_.expect(0.U))
      }

      // Multi-latency FEX result collisions are excluded by the local FU table.
      init()
      drive(3, 0, FuType.fmac, 1, 10, floating         = true)
      drive(3, 1, FuType.falu, 2, 11, floating         = true)
      send(); drive(3, 0, FuType.falu, 3, 12, floating = true); send(); tick(14)
      result(10, true).cycle shouldBe 7; result(11, true).cycle shouldBe 6
      result(12, true).cycle shouldBe 8
      for (dest <- 10 to 12) result(dest, true).data shouldBe 18

      // Waiting DIV input and held DIV result cannot move a later MUL from its fixed slot.
      for (holdResult <- Seq(false, true)) {
        init()
        if (holdResult) dut.io.holdUncertainResult(1).poke(true.B)
        else dut.io.holdUncertainInput(1).poke(true.B)
        drive(0, 0, FuType.div, 1, 10); send(); tick(5)
        drive(0, 0, FuType.mul, 2, 11); send(); tick(10)
        result(11).cycle shouldBe one("data0", 2).cycle + 4
        result(11).data shouldBe 18
        dut.io.holdUncertainInput(1).poke(false.B); dut.io.holdUncertainResult(1).poke(false.B)
        tick(5); result(10).data shouldBe 18
      }

      // Both groups accept independently while completion is held. Fixed MUL keeps
      // its cadence and completes alongside the first uncertain result on another port.
      for (firstFu <- Seq(FuType.div, FuType.csr, FuType.fence)) {
        init(); dut.io.holdUncertainResult(1).poke(true.B)
        val secondFu  = if (firstFu == FuType.div) FuType.csr else FuType.div
        drive(0, 0, firstFu, 1, 10, writes  = firstFu != FuType.fence); send(); tick(5)
        drive(0, 0, secondFu, 2, 11); send(); tick(5)
        one("execute", 1); one("execute", 2)
        drive(1, 0, FuType.alu, 3, 12, src0 = 11, waiting = true); send()
        for (k <- 0 until 12) {
          drive(0, 0, if (k % 2 == 0) FuType.mul else FuType.bku, 20 + k, 40 + k); send()
          if (k == 6) dut.io.holdUncertainResult(1).poke(false.B)
        }
        tick(16)
        val c1        = one("robWb", 1); val c2 = one("robWb", 2)
        c2.cycle shouldBe c1.cycle + 1
        c1.exu shouldBe AnsIssueParams.nExu; c2.exu shouldBe AnsIssueParams.nExu
        events.count(e => e.stage == "robWb" && e.cycle == c1.cycle) shouldBe 2
        if (firstFu == FuType.fence) events.exists(e => e.stage == "intWb" && e.dest == 10) shouldBe false
        else { result(10).exu shouldBe 3; result(10).data shouldBe 18 }
        result(11).exu shouldBe 3; result(11).data shouldBe 18
        events.exists(e => e.stage == "wake" && (e.rob == 1 || e.rob == 2)) shouldBe false
        one("execute", 3).src shouldBe 18; result(12).data shouldBe 25
        for (k <- 0 until 12) {
          result(40 + k).cycle shouldBe one("data0", 20 + k).cycle + 4
          result(40 + k).exu shouldBe 1; result(40 + k).data shouldBe 18
          if (k > 0) one("data0", 20 + k).cycle shouldBe one("data0", 19 + k).cycle + 1
        }
        dut.io.counts.foreach(_.expect(0.U))
      }

      // Redirect cancels both a waiting request and an arbitrated result permanently.
      for (holdInput <- Seq(false, true)) {
        init()
        dut.io.holdUncertainInput(1).poke(holdInput.B)
        dut.io.holdUncertainResult(1).poke(true.B)
        drive(0, 0, FuType.div, 80, 10); send(); tick(8)
        drive(0, 0, FuType.csr, 81, 11); send(); tick(8)
        dut.io.flush.valid.poke(true.B); dut.io.flush.bits.robIdx.poke(80.U)
        dut.io.flush.bits.flushItself.poke(true.B)
        tick(); zero(dut.io.flush)
        zero(dut.io.holdUncertainInput); zero(dut.io.holdUncertainResult); tick(12)
        events.exists(e => e.stage == "robWb" && e.rob >= 80) shouldBe false
        events.exists(e => e.stage == "intWb") shouldBe false
        dut.io.counts.foreach(_.expect(0.U))
      }

      // Flush at selection/deqDly/data0/data1/EXU and every fixed pipeline stage.
      for (flushCycle <- 1 to 7) {
        init(); drive(3, 0, FuType.fmac, 5, 10, floating = true); send()
        while (cycle < flushCycle) tick()
        dut.io.flush.valid.poke(true.B); dut.io.flush.bits.robIdx.poke(5.U)
        dut.io.flush.bits.flushItself.poke(true.B)
        tick(); zero(dut.io.flush); tick(10)
        withClue(s"flush at $flushCycle: $events") {
          events.exists(e => e.stage == "fpWb" && e.dest == 10) shouldBe false
        }
        dut.io.counts.foreach(_.expect(0.U))
      }
    }
  }
}
