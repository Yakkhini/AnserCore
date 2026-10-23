/* Scalar DecodeStage for RVB23.
 *
 * The stage owns the complete decode-to-rename boundary:
 *   input -> combinational decode -> T1 group buffer -> fusion -> rename.
 * RAT storage and returned physical register data belong to rename. Decode
 * only sends the pre-fusion logical addresses one cycle early.
 */
package ansercore.backend.decode

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.{BundleParams, DecodedInst, StaticInst, TrapInstInfo}
import ansercore.backend.exu.csr.AnsCsrDecodeState

/** Early RAT read request. The RAT keeps its registered address while `hold` is set. */
class AnsRatReadRequest(val addrWidth: Int) extends Bundle {
  val hold = Bool()
  val addr = UInt(addrWidth.W)
}

/** Single external interface for the scalar decode stage. */
class AnsDecodeStageIO(val params: BundleParams = BundleParams()) extends Bundle {
  val redirect      = Input(Bool())
  val disableFusion = Input(Bool())
  val fromCsr       = Input(new AnsCsrDecodeState)

  val in       = Vec(params.renameWidth, Flipped(Decoupled(new StaticInst(params))))
  val renameIn = Vec(params.renameWidth, Decoupled(new DecodedInst(params)))

  val trapInstInfo = Output(Valid(new TrapInstInfo(params)))

  // Both banks are read in parallel. srcType selects the bank in rename.
  val intRat = Output(
    Vec(
      params.renameWidth,
      Vec(2, new AnsRatReadRequest(params.logicalRegWidth)),
    )
  )
  val fpRat  = Output(
    Vec(
      params.renameWidth,
      Vec(3, new AnsRatReadRequest(params.logicalRegWidth)),
    )
  )

  val fusionInfo = Output(Vec(params.renameWidth - 1, new AnsFusionDecodeInfo))
}

/** Scalar RVB23 DecodeStage with one holding register between decode and rename. */
class AnsDecodeStage(val params: BundleParams = BundleParams()) extends Module {
  val width = params.renameWidth
  require(width >= 2, "fusion needs at least two rename lanes")
  require(params.numSrc >= 3, "RVB23 FMA needs three source slots")

  val io       = IO(new AnsDecodeStageIO(params))
  val decoders = Seq.fill(width)(Module(new AnsDecodeUnit(params)))
  val fusion   = Module(new AnsFusionDecoder(params))

  val decoded = Wire(Vec(width, new DecodedInst(params)))
  decoders.zipWithIndex.foreach { case (decoder, lane) =>
    decoder.io.enq.ctrlFlow := io.in(lane).bits
    decoder.io.enq.fromCsr  := io.fromCsr
    decoded(lane)           := decoder.io.deq.decodedInst
  }

  val t1Valid      = RegInit(VecInit(Seq.fill(width)(false.B)))
  val decodedT1    = Reg(Vec(width, new DecodedInst(params)))
  val visibleValid = VecInit(t1Valid.zip(fusion.io.clear).map { case (valid, clear) => valid && !clear })

  // A lane may be consumed independently. The register group can be replaced
  // only when every still-visible lane fires (or is already empty).
  val fired  = visibleValid
    .zip(io.renameIn.map(_.ready))
    .map { case (valid, ready) => !valid || ready }
    .reduce(_ && _)
  val accept = fired && !io.redirect

  io.in.foreach(_.ready := accept)

  // Read both RAT banks before fusion. Rename selects the proper bank with
  // srcType and uses fusionInfo when source 2 comes from the following lane.
  val holdRatRequest = t1Valid.asUInt.orR && !fired
  for (lane <- 0 until width) {
    for (port <- 0 until 2) {
      io.intRat(lane)(port).addr := decoded(lane).logicalSrc(port)
      io.intRat(lane)(port).hold := t1Valid(lane) && holdRatRequest
    }
    for (port <- 0 until 3) {
      io.fpRat(lane)(port).addr := decoded(lane).logicalSrc(port)
      io.fpRat(lane)(port).hold := t1Valid(lane) && holdRatRequest
    }
  }

  // Fusion samples the same accepted transaction as decodedT1.
  fusion.io.disableFusion := io.disableFusion
  fusion.io.accept        := accept
  fusion.io.in.zipWithIndex.foreach { case (fusionInput, lane) =>
    fusionInput.valid := io.in(lane).valid
    fusionInput.bits  := decoded(lane)
  }

  when(io.redirect) {
    t1Valid := VecInit(Seq.fill(width)(false.B))
  }.elsewhen(fired) {
    decodedT1 := decoded
    t1Valid   := VecInit(io.in.map(_.valid))
  }.otherwise {
    for (lane <- 0 until width) {
      when(io.renameIn(lane).fire) {
        t1Valid(lane) := false.B
      }
    }
  }

  val renameBits = Wire(Vec(width, new DecodedInst(params)))
  renameBits := decodedT1
  for (lane <- 0 until width - 1) {
    fusion.io.out(lane).bits.update(renameBits(lane))
    io.fusionInfo(lane) := fusion.io.info(lane)
  }

  io.renameIn.zipWithIndex.foreach { case (renameInput, lane) =>
    renameInput.valid := visibleValid(lane) && !io.redirect
    renameInput.bits  := renameBits(lane)
  }

  // Report the oldest exceptional instruction when it actually leaves decode.
  // Younger exceptions remain in T1 and are reported if not flushed.
  val exceptionFire   = VecInit((0 until width).map { lane =>
    io.renameIn(lane).fire && renameBits(lane).hasException
  })
  val oldestException = PriorityEncoder(exceptionFire)
  io.trapInstInfo.valid := exceptionFire.asUInt.orR
  io.trapInstInfo.bits  := 0.U.asTypeOf(new TrapInstInfo(params))
  when(io.trapInstInfo.valid) {
    io.trapInstInfo.bits.fromDecodedInst(renameBits(oldestException))
  }
}
