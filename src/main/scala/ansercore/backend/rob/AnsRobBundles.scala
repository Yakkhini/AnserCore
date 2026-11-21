package ansercore.backend.rob

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.dispatch.AnsRobEnqIO
import ansercore.backend.rename.{AnsRabCommitIO, AnsRenameRedirect, AnsSnapshotPort}

/** One ROB group; per-destination mappings belong to the RAB, not this entry. */
class AnsRobEntry(val params: BundleParams = BundleParams()) extends Bundle {
  private val countWidth = log2Ceil(params.renameWidth + 1)

  val valid          = Bool()
  val robIdx         = UInt(params.robIndexWidth.W)
  val instr          = UInt(params.instructionWidth.W)
  val pc             = UInt(params.pcWidth.W)
  val exceptionBits  = UInt(params.exceptionBits.W)
  val ftqPtr         = new FtqPtr(params)
  val ftqOffset      = UInt(params.ftqOffsetWidth.W)
  val commitType     = CommitType()
  val instrSize      = UInt(countWidth.W)
  val receivedUops   = UInt(countWidth.W)
  val realDestSize   = UInt(countWidth.W)
  val uopNum         = UInt(countWidth.W)
  val stdWritebacked = Bool()
  val fpWen          = Bool()
  val wflags         = Bool()
  val fflags         = UInt(5.W)
  val mmio           = Bool()
  val needFlush      = Bool()
  val waitForward    = Bool()
  val blockBackward  = Bool()

  def allEnqueued: Bool   = receivedUops === instrSize
  def isWritebacked: Bool = allEnqueued && !uopNum.orR && stdWritebacked
}

class AnsRobCommitInfo(val params: BundleParams = BundleParams()) extends Bundle {
  val robIdx       = UInt(params.robIndexWidth.W)
  val ftqPtr       = new FtqPtr(params)
  val ftqOffset    = UInt(params.ftqOffsetWidth.W)
  val instrSize    = UInt(log2Ceil(params.renameWidth + 1).W)
  val realDestSize = UInt(log2Ceil(params.renameWidth + 1).W)
  val commitType   = CommitType()
  val fpWen        = Bool()
  val wflags       = Bool()
  val fflags       = UInt(5.W)
  val needFlush    = Bool()
  val mmio         = Bool()
}

/** ROB commit and Walk have different widths; RAB retirement is another interface. */
class AnsRobCommitIO(val params: BundleParams, val config: AnsRobParams) extends Bundle {
  val isCommit    = Bool()
  val commitValid = Vec(config.commitWidth, Bool())
  val info        = Vec(config.commitWidth, new AnsRobCommitInfo(params))
  val isWalk      = Bool()
  val walkValid   = Vec(config.walkWidth, Bool())
  val walkInfo    = Vec(config.walkWidth, new AnsRobCommitInfo(params))
}

/** Only ROB-relevant writeback fields; no result data or physical-register write port. */
class AnsRobWriteback(val params: BundleParams = BundleParams()) extends Bundle {
  val robIdx        = UInt(params.robIndexWidth.W)
  val exceptionBits = UInt(params.exceptionBits.W)
  val replayInst    = Bool()
  val flushPipe     = Bool()
  val stdWriteback  = Bool()
  val writeFflags   = Bool()
  val fflags        = UInt(5.W)
}

class AnsRobLsqIO(val config: AnsRobParams) extends Bundle {
  val lcommit        = Output(UInt(config.commitCountWidth.W))
  val scommit        = Output(UInt(config.commitCountWidth.W))
  val commit         = Output(Bool())
  val pendingPtr     = Output(new AnsRobPtr(config))
  val pendingPtrNext = Output(new AnsRobPtr(config))
  val mmioBusy       = Input(Bool())
}

class AnsRobCsrIO(val params: BundleParams = BundleParams()) extends Bundle {
  val intrBitSet         = Input(Bool())
  val trapTarget         = Input(UInt(params.pcWidth.W))
  val wfiEvent           = Input(Bool())
  val criticalErrorState = Input(Bool())
  val fflags             = Output(Valid(UInt(5.W)))
  val dirtyFs            = Output(Bool())
  val retiredInstr       = Output(UInt(7.W))
}

class AnsRobExceptionInfo(val params: BundleParams = BundleParams()) extends Bundle {
  val robIdx        = UInt(params.robIndexWidth.W)
  val pc            = UInt(params.pcWidth.W)
  val instr         = UInt(params.instructionWidth.W)
  val exceptionBits = UInt(params.exceptionBits.W)
  val ftqPtr        = new FtqPtr(params)
  val ftqOffset     = UInt(params.ftqOffsetWidth.W)
  val replayInst    = Bool()
  val flushPipe     = Bool()
  val isInterrupt   = Bool()
}

/** Precise ROB flush candidate for CtrlBlock; distinct from an execution-unit branch redirect. */
class AnsRobFlushInfo(val params: BundleParams = BundleParams()) extends Bundle {
  val robIdx      = UInt(params.robIndexWidth.W)
  val ftqPtr      = new FtqPtr(params)
  val ftqOffset   = UInt(params.ftqOffsetWidth.W)
  val target      = UInt(params.pcWidth.W)
  val flushItself = Bool()
  val replayInst  = Bool()
  val isInterrupt = Bool()
}

/** Bring-up visibility only; these signals never participate in handshakes. */
class AnsRobDebugIO(val params: BundleParams, val config: AnsRobParams) extends Bundle {
  val occupancy = UInt(config.occupancyWidth.W)
  val enqPtr    = new AnsRobPtr(config)
  val head      = new AnsRobEntry(params)
}

class AnsRobIO(val params: BundleParams, val config: AnsRobParams) extends Bundle {
  val enq         = new AnsRobEnqIO(params)
  val writeback   = Input(Vec(config.writebackPorts, Valid(new AnsRobWriteback(params))))
  val redirectIn  = Input(Valid(new AnsRenameRedirect(params)))
  // Compatibility broadcast for the current Backend. Arbitration belongs to CtrlBlock.
  val redirect    = Output(Valid(new AnsRenameRedirect(params)))
  val rabCommits  = Output(new AnsRabCommitIO(params))
  val snpt        = Output(new AnsSnapshotPort(params))
  val snptLastEnq = Output(Valid(UInt(params.robIndexWidth.W)))
  val snptIsFull  = Output(Bool())
  val commits     = Output(new AnsRobCommitIO(params, config))
  val flushOut    = Output(Valid(new AnsRobFlushInfo(params)))
  val exception   = Output(Valid(new ExceptionInfo(params)))
  val lsq         = new AnsRobLsqIO(config)
  val csr         = new AnsRobCsrIO(params)
  val robDeqPtr   = Output(new AnsRobPtr(config))
  val robFull     = Output(Bool())
  val debug       = Output(new AnsRobDebugIO(params, config))
}
