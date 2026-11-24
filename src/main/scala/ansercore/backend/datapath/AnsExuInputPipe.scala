/* Operand capture after BypassNetwork. Fixed requests spend exactly one cycle here;
 * each uncertain group has a separate conservative request slot (no drain/refill).
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._
import ansercore.backend.Bundles._
import ansercore.backend.issue.{AnsExuParams, AnsIssueParams, AnsIssueUtils}
import ansercore.backend.rename.AnsRenameRedirect

class AnsExuInputPipeIO(val params: BundleParams, val exu: AnsExuParams) extends Bundle {
  val flush         = Input(Valid(new AnsRenameRedirect(params)))
  val in            = Flipped(Decoupled(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc))))
  val out           = Decoupled(in.bits.cloneType)
  val uncertainOut  = Vec(exu.uncertainGroups.size, Decoupled(in.bits.cloneType))
  val uncertainBusy = Output(UInt(FuType.width.W))
}

class AnsExuInputPipe(
  val params: BundleParams = BundleParams(),
  val exu:    AnsExuParams = AnsIssueParams.exuByIndex(0),
) extends Module {
  val io        = IO(new AnsExuInputPipeIO(params, exu))
  val inFlushed = AnsIssueUtils.needFlush(io.in.bits.robIdx, io.flush)

  if (exu.splitUncertain || !exu.hasUncertainFu) {
    val valid = RegInit(false.B)
    val bits  = Reg(chiselTypeOf(io.in.bits))
    val fixed = exu.isFixedFu(io.in.bits.fuType)
    valid        := io.in.fire && fixed && !inFlushed
    when(io.in.fire && fixed)(bits := io.in.bits)
    io.out.valid := valid && !AnsIssueUtils.needFlush(bits.robIdx, io.flush)
    io.out.bits  := bits
    io.in.ready  := fixed
    when(io.out.valid)(assert(io.out.ready, "fixed EXU input must be accepted on schedule"))
  } else {
    // Remaining uncertain-only owners (FP DIV and memory) keep their existing scope.
    require(!exu.hasCertainFu)
    io.in.ready  := false.B
    io.out.valid := false.B
    io.out.bits  := 0.U.asTypeOf(io.out.bits)
  }

  val groups =
    if (exu.splitUncertain) exu.uncertainGroups
    else if (exu.hasUncertainFu) Seq(exu.fuTypes)
    else Seq.empty
  val busy   = groups.zipWithIndex.map { case (fus, i) =>
    val out      = if (exu.splitUncertain) io.uncertainOut(i) else io.out
    val valid    = RegInit(false.B)
    val bits     = Reg(chiselTypeOf(io.in.bits))
    val flushed  = AnsIssueUtils.needFlush(bits.robIdx, io.flush)
    val selected = fus.map(f => io.in.bits.fuType === f.U).reduce(_ || _)
    out.valid := valid && !flushed
    out.bits  := bits
    when(selected)(io.in.ready := !valid || flushed)
    when(io.in.fire && selected && !inFlushed) {
      valid := true.B
      bits  := io.in.bits
    }.elsewhen(flushed || out.fire) {
      valid := false.B
    }
    Mux(valid && !flushed, fus.reduce(_ | _).U(FuType.width.W), 0.U(FuType.width.W))
  }
  io.uncertainBusy := busy.foldLeft(0.U(FuType.width.W))(_ | _)
}
