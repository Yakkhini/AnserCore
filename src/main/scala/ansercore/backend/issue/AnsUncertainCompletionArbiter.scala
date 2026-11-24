package ansercore.backend.issue

import chisel3._
import chisel3.util._
import ansercore.backend.Bundles._
import ansercore.backend.rename.AnsRenameRedirect

/** One completion per cycle. DIV wins after reset; only a successful transfer rotates priority. A stalled selection
  * stays locked until accepted or cancelled by redirect.
  */
class AnsUncertainCompletionArbiter(val params: BundleParams = BundleParams()) extends Module {
  private val n = AnsIssueParams.nUncertainGroups
  require(n == 2, "scalar completion arbitration supports exactly DIV and CSR/FENCE")
  val io        = IO(new Bundle {
    val flush = Input(Valid(new AnsRenameRedirect(params)))
    val in    = Vec(n, Flipped(Decoupled(new ExuOutput(params, ExecutionUnitParams()))))
    val out   = Decoupled(new ExuOutput(params, ExecutionUnitParams()))
  })
  val preferCsr = RegInit(false.B)
  val locked    = RegInit(false.B)
  val lockedCsr = RegInit(false.B)
  val live      = VecInit(io.in.map(in => in.valid && !AnsIssueUtils.needFlush(in.bits.robIdx, io.flush)))
  val lockLive  = locked && live(lockedCsr)
  val chooseCsr = Mux(lockLive, lockedCsr, live(1) && (preferCsr || !live(0)))
  io.out.valid                        := live(chooseCsr)
  io.out.bits                         := io.in(chooseCsr).bits
  for (i <- 0 until n) io.in(i).ready := io.out.ready && io.out.valid && chooseCsr === (i == 1).B
  when(io.out.fire) {
    preferCsr := !chooseCsr
    locked    := false.B
  }.elsewhen(io.out.valid) {
    locked    := true.B
    lockedCsr := chooseCsr
  }.otherwise {
    locked := false.B
  }
}
