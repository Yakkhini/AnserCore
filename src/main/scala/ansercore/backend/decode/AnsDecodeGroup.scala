/* Combinational scalar decode group for RVB23.
 *
 * This module only expands instructions and runs the decode tables. T1 holding,
 * fusion patching, RAT early reads and trap reporting live in the Backend, which
 * owns the decode-to-rename boundary.
 */
package ansercore.backend.decode

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.{BundleParams, DecodedInst, StaticInst}
import ansercore.backend.exu.csr.AnsCsrDecodeState

/** Pure combinational decode group: one DecodeUnit per rename lane. */
class AnsDecodeGroupIO(val params: BundleParams = BundleParams()) extends Bundle {
  val fromCsr = Input(new AnsCsrDecodeState)

  /** Group transaction accepted by the Backend T1 register. */
  val accept = Input(Bool())

  val in  = Vec(params.renameWidth, Flipped(Decoupled(new StaticInst(params))))
  val out = Vec(params.renameWidth, Output(new DecodedInst(params)))
}

class AnsDecodeGroup(val params: BundleParams = BundleParams()) extends Module {
  val width = params.renameWidth
  require(width >= 1, "decode group needs at least one lane")
  require(params.numSrc >= 3, "RVB23 FMA needs three source slots")

  val io       = IO(new AnsDecodeGroupIO(params))
  val decoders = Seq.fill(width)(Module(new AnsDecodeUnit(params)))

  decoders.zipWithIndex.foreach { case (decoder, lane) =>
    decoder.io.enq.ctrlFlow := io.in(lane).bits
    decoder.io.enq.fromCsr  := io.fromCsr
    io.out(lane)            := decoder.io.deq.decodedInst
    io.in(lane).ready       := io.accept
  }
}
