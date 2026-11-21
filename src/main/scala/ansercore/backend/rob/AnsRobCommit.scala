package ansercore.backend.rob

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.BundleParams
import ansercore.backend.rename.AnsRenameRedirect

class AnsRobCommitIOInternal(val params: BundleParams, val config: AnsRobParams) extends Bundle {
  val redirect         = Input(Valid(new AnsRenameRedirect(params)))
  val line             = Input(Vec(config.bankCount, new AnsRobEntry(params)))
  val exception        = Input(Valid(new AnsRobExceptionInfo(params)))
  val mmioBusy         = Input(Bool())
  val interruptPending = Input(Bool())
  val criticalError    = Input(Bool())
  val walkFinished     = Input(Bool())
  val rabWalkEnd       = Input(Bool())
  val commits          = Output(new AnsRobCommitIO(params, config))
  val deqPtr           = Output(new AnsRobPtr(config))
  val deqCount         = Output(UInt(config.commitCountWidth.W))
  val walk             = Output(Bool())
}

/** Owns retirement order and the recovery state; never invents completed work. */
class AnsRobCommit(val params: BundleParams, val config: AnsRobParams) extends Module {
  val io                    = IO(new AnsRobCommitIOInternal(params, config))
  val sIdle :: sWalk :: Nil = Enum(2)
  val state                 = RegInit(sIdle)
  val deqPtr                = RegInit(0.U.asTypeOf(new AnsRobPtr(config)))

  when(io.redirect.valid) {
    state := sWalk
  }.elsewhen(state === sWalk && io.walkFinished && io.rabWalkEnd) {
    state := sIdle
  }

  // TODO(ROB-COMMIT): select the completed prefix, advance deqPtr and clear banks.
  // TODO(ROB-WALK): scan younger entries and wait for all recovery participants.
  io.deqPtr         := deqPtr
  io.deqCount       := 0.U
  io.walk           := state === sWalk
  io.commits        := 0.U.asTypeOf(io.commits)
  io.commits.isWalk := io.walk
}
