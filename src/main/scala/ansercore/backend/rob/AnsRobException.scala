package ansercore.backend.rob

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.BundleParams
import ansercore.backend.rename.AnsRenameRedirect

class AnsRobExceptionGenIO(val params: BundleParams, val config: AnsRobParams) extends Bundle {
  val redirect  = Input(Valid(new AnsRenameRedirect(params)))
  val enq       = Input(Vec(config.renameWidth, Valid(new AnsRobExceptionInfo(params))))
  val writeback = Input(Vec(config.writebackPorts, Valid(new AnsRobWriteback(params))))
  val out       = Output(Valid(new AnsRobExceptionInfo(params)))
}

/** Retained oldest-exception boundary; candidate collection is wired, policy is deferred. */
class AnsRobExceptionGen(val params: BundleParams, val config: AnsRobParams) extends Module {
  val io = IO(new AnsRobExceptionGenIO(params, config))

  // TODO(ROB-EXCEPTION): retain the oldest surviving candidate using AnsRobPtr age,
  // filter flushed writebacks, and retire/clear the record only after a precise trap.
  io.out := 0.U.asTypeOf(io.out)
}
