package ansercore.backend.rob

import chisel3._
import chisel3.util._

/** Occupancy and admission policy. Counts ROB groups, not compressed instruction lanes. */
class AnsRobEnqPtr(val config: AnsRobParams) extends Module {
  val io = IO(new Bundle {
    val needAlloc            = Input(Vec(config.renameWidth, Bool()))
    val enq                  = Input(Vec(config.renameWidth, Valid(new AnsRobPtr(config))))
    val deqCount             = Input(UInt(config.commitCountWidth.W))
    val blocked              = Input(Bool())
    val canAccept            = Output(Bool())
    val canAcceptForDispatch = Output(Bool())
    val isEmpty              = Output(Bool())
    val isFull               = Output(Bool())
    val occupancy            = Output(UInt(config.occupancyWidth.W))
    val enqPtr               = Output(new AnsRobPtr(config))
  })

  val occupancy = RegInit(0.U(config.occupancyWidth.W))
  val enqPtr    = RegInit(0.U.asTypeOf(new AnsRobPtr(config)))
  val lastEnq   = RegInit(0.U.asTypeOf(Valid(new AnsRobPtr(config))))

  // Dispatch requests may contain several members of the same compressed group.
  // A group may also straddle cycles; only the latest group can be continued.
  val newGroups = io.enq.zipWithIndex.map { case (req, lane) =>
    val seenInGroup = io.enq
      .take(lane)
      .map(prev => prev.valid && prev.bits === req.bits)
      .foldLeft(false.B)(_ || _)
    val continued   = lastEnq.valid && lastEnq.bits === req.bits
    req.valid && !seenInGroup && !continued
  }
  val enqCount  = PopCount(newGroups)
  val hasEnq    = io.enq.map(_.valid).reduce(_ || _)
  val lastPtr   = PriorityMux(io.enq.reverse.map(req => req.valid -> req.bits))

  when(!io.blocked) {
    occupancy := occupancy + enqCount - io.deqCount
    when(hasEnq) {
      lastEnq.valid := true.B
      lastEnq.bits  := lastPtr
      enqPtr        := lastPtr.next
    }
    assert(io.deqCount <= occupancy, "ROB occupancy underflow")
    assert(occupancy +& enqCount <= config.robSize.U + io.deqCount, "ROB occupancy overflow")
  }

  // needAlloc is independent of req.valid (which already contains Dispatch.fire).
  // Keep the pipeline reservations without a ready -> valid -> ready loop.
  val demand = occupancy +& PopCount(io.needAlloc)
  io.canAccept            := !io.blocked && demand <= (config.robSize - config.renameWidth).U
  io.canAcceptForDispatch := !io.blocked && demand <= (config.robSize - 2 * config.renameWidth).U
  io.isEmpty              := occupancy === 0.U
  io.isFull               := occupancy === config.robSize.U
  io.occupancy            := occupancy
  io.enqPtr               := enqPtr
}
