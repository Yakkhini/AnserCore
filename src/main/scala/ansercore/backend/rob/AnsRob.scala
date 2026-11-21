package ansercore.backend.rob

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

/** Scalar ROB composition root. See markdown/rob_design.md for the staged implementation contract. */
class AnsRob(val params: BundleParams = BundleParams(), val writebackPorts: Int = 1) extends Module {
  private val config = AnsRobParams.from(params, writebackPorts)
  val io             = IO(new AnsRobIO(params, config))

  val enqPtr       = Module(new AnsRobEnqPtr(config))
  val banks        = Seq.fill(config.bankCount)(Module(new AnsRobBank(params, config)))
  val commit       = Module(new AnsRobCommit(params, config))
  val exceptionGen = Module(new AnsRobExceptionGen(params, config))
  val rab          = Module(new AnsRobRab(params, config))

  val blocked = io.redirectIn.valid || commit.io.walk || !rab.io.canAccept
  enqPtr.io.needAlloc         := io.enq.needAlloc
  enqPtr.io.deqCount          := commit.io.deqCount
  enqPtr.io.blocked           := blocked
  io.enq.canAccept            := enqPtr.io.canAccept
  io.enq.canAcceptForDispatch := enqPtr.io.canAcceptForDispatch
  io.enq.isEmpty              := enqPtr.io.isEmpty
  io.robFull                  := enqPtr.io.isFull

  val accepted = io.enq.req.map(req => req.valid && io.enq.canAcceptForDispatch)
  val pointers = io.enq.req.map(req => AnsRobPtr.fromUInt(req.bits.robIdx, config))
  for (lane <- 0 until config.renameWidth) {
    // Rename is currently the allocator; resp is its index echo, not a second allocation.
    io.enq.resp(lane)         := io.enq.req(lane).bits.robIdx
    enqPtr.io.enq(lane).valid := accepted(lane)
    enqPtr.io.enq(lane).bits  := pointers(lane)
    rab.io.enq(lane).valid    := accepted(lane)
    rab.io.enq(lane).bits     := io.enq.req(lane).bits

    val req       = io.enq.req(lane).bits
    val candidate = exceptionGen.io.enq(lane)
    candidate                    := 0.U.asTypeOf(candidate)
    candidate.valid              := accepted(lane) && (req.hasException || req.replayInst || req.commitSelfFlush)
    candidate.bits.robIdx        := req.robIdx
    candidate.bits.pc            := req.pc
    candidate.bits.instr         := req.instr
    candidate.bits.exceptionBits := req.exceptionBits
    candidate.bits.ftqPtr        := req.ftqPtr
    candidate.bits.ftqOffset     := req.ftqOffset
    candidate.bits.replayInst    := req.replayInst
    candidate.bits.flushPipe     := req.commitSelfFlush
  }

  for ((bank, bankIndex) <- banks.zipWithIndex) {
    val hits       = accepted.zip(pointers).map { case (fire, ptr) => fire && ptr.bank === bankIndex.U }
    val hasWrite   = hits.reduce(_ || _)
    val requests   = hits.zip(io.enq.req.map(_.bits))
    val first      = PriorityMux(requests)
    val last       = PriorityMux(requests.reverse)
    val previous   = bank.io.updateData
    val continuing = previous.valid && previous.robIdx === first.robIdx

    bank.io.readAddr        := commit.io.deqPtr.row
    bank.io.writeAddr       := AnsRobPtr.fromUInt(first.robIdx, config).row
    bank.io.clear           := VecInit(Seq.fill(config.bankEntries)(false.B))
    bank.io.writeData.valid := hasWrite

    // Merge lanes sharing one compressed ROB group before the single bank write.
    // A subsequent dispatch cycle can append the remaining members of that group.
    val entry        = bank.io.writeData.bits
    val receivedUops = Mux(continuing, previous.receivedUops, 0.U) +& PopCount(hits)
    entry                := Mux(continuing, previous, 0.U.asTypeOf(new AnsRobEntry(params)))
    entry.valid          := true.B
    entry.robIdx         := first.robIdx
    entry.instr          := Mux(continuing, previous.instr, first.instr)
    entry.pc             := Mux(continuing, previous.pc, first.pc)
    entry.commitType     := Mux(continuing, previous.commitType, first.commitType)
    entry.instrSize      := first.instrSize
    entry.ftqPtr         := last.ftqPtr
    entry.ftqOffset      := last.ftqOffset
    entry.receivedUops   := receivedUops
    entry.realDestSize   := Mux(continuing, previous.realDestSize, 0.U) +
      PopCount(requests.map { case (hit, req) => hit && req.needWriteRf })
    // TODO(ROB-WB): replace scalar issued-uop initialization with the final completion protocol.
    entry.uopNum         := Mux(continuing, previous.uopNum, 0.U) +
      PopCount(requests.map { case (hit, req) => hit && !req.eliminatedMove && !req.hasException })
    entry.exceptionBits  := Mux(continuing, previous.exceptionBits, 0.U) |
      requests.map { case (hit, req) => Mux(hit, req.exceptionBits, 0.U) }.reduce(_ | _)
    entry.stdWritebacked := (!continuing || previous.stdWritebacked) &&
      !requests.map { case (hit, req) => hit && req.isStore }.reduce(_ || _)
    entry.fpWen          := (continuing && previous.fpWen) ||
      requests.map { case (hit, req) => hit && req.fpWen }.reduce(_ || _)
    entry.needFlush      := (continuing && previous.needFlush) ||
      requests
        .map { case (hit, req) => hit && (req.hasException || req.commitSelfFlush || req.replayInst) }
        .reduce(_ || _)
    entry.waitForward    := (continuing && previous.waitForward) ||
      requests.map { case (hit, req) => hit && req.waitForward }.reduce(_ || _)
    entry.blockBackward  := (continuing && previous.blockBackward) ||
      requests.map { case (hit, req) => hit && req.blockBackward }.reduce(_ || _)

    when(hasWrite) {
      assert(receivedUops <= entry.instrSize, "ROB group received too many instructions")
      when(continuing) {
        assert(previous.instrSize === first.instrSize, "continued ROB group size mismatch")
      }
      for ((hit, req) <- requests) {
        when(hit) {
          assert(req.robIdx === first.robIdx, "different ROB entries collided in a bank")
          assert(req.instrSize === first.instrSize, "compressed group size mismatch")
        }
      }
    }
  }

  commit.io.redirect         := io.redirectIn
  commit.io.line             := VecInit(banks.map(_.io.readData))
  commit.io.exception        := exceptionGen.io.out
  commit.io.mmioBusy         := io.lsq.mmioBusy
  commit.io.interruptPending := io.csr.intrBitSet
  commit.io.criticalError    := io.csr.criticalErrorState
  commit.io.walkFinished     := false.B // TODO(ROB-WALK): no completion until bank recovery exists.
  commit.io.rabWalkEnd       := rab.io.walkEnd
  exceptionGen.io.redirect   := io.redirectIn
  exceptionGen.io.writeback  := io.writeback
  rab.io.redirect            := io.redirectIn
  rab.io.robCommits          := commit.io.commits

  io.redirect           := io.redirectIn
  io.commits            := commit.io.commits
  io.rabCommits         := rab.io.rabCommits
  io.snpt               := rab.io.snpt
  io.snptLastEnq        := rab.io.snptLastEnq
  io.snptIsFull         := rab.io.snptIsFull
  io.robDeqPtr          := commit.io.deqPtr
  io.flushOut           := 0.U.asTypeOf(io.flushOut)
  io.exception          := 0.U.asTypeOf(io.exception)
  io.lsq.lcommit        := 0.U
  io.lsq.scommit        := 0.U
  io.lsq.commit         := false.B
  io.lsq.pendingPtr     := commit.io.deqPtr
  io.lsq.pendingPtrNext := commit.io.deqPtr
  io.csr.fflags         := 0.U.asTypeOf(io.csr.fflags)
  io.csr.dirtyFs        := false.B
  io.csr.retiredInstr   := 0.U
  io.debug.occupancy    := enqPtr.io.occupancy
  io.debug.enqPtr       := enqPtr.io.enqPtr
  io.debug.head         := VecInit(banks.map(_.io.readData))(commit.io.deqPtr.bank)
}
