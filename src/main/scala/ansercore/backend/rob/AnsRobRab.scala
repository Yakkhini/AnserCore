package ansercore.backend.rob

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.{AnsRabCommitIO, AnsRenameRedirect, AnsSnapshotPort}

class AnsRobRabIO(val params: BundleParams, val config: AnsRobParams) extends Bundle {
  val enq         = Input(Vec(config.renameWidth, Valid(new DynInst(params))))
  val robCommits  = Input(new AnsRobCommitIO(params, config))
  val redirect    = Input(Valid(new AnsRenameRedirect(params)))
  val canAccept   = Output(Bool())
  val walkEnd     = Output(Bool())
  val rabCommits  = Output(new AnsRabCommitIO(params))
  val snpt        = Output(new AnsSnapshotPort(params))
  val snptLastEnq = Output(Valid(UInt(params.robIndexWidth.W)))
  val snptIsFull  = Output(Bool())
}

/** RAB owns ordered destination mappings and RAT/FreeList retirement, including moves. */
class AnsRobRab(val params: BundleParams, val config: AnsRobParams) extends Module {
  val io = IO(new AnsRobRabIO(params, config))

  // TODO(ROB-RAB): allocate one mapping per destination and consume realDestSize budgets.
  // TODO(ROB-SNAPSHOT): save/restore RAB pointers alongside Rename's snapshot selection.
  val recovering = io.redirect.valid || io.robCommits.isWalk
  io.canAccept         := !recovering
  io.walkEnd           := !recovering
  io.rabCommits        := 0.U.asTypeOf(io.rabCommits)
  io.rabCommits.isWalk := io.robCommits.isWalk
  io.snpt              := 0.U.asTypeOf(io.snpt)
  io.snptLastEnq       := 0.U.asTypeOf(io.snptLastEnq)
  // Disable snapshot creation until matching ROB/RAB snapshots can be maintained.
  io.snptIsFull        := true.B
}
