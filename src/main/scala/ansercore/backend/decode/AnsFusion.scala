/* Scalar RVB23 macro-op fusion for Decode. */
package ansercore.backend.decode

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.Bundles.{BundleParams, DecodedInst, FuOpType, FuType, ImmType, SrcType}

/** Information needed by rename when the fused source 2 was not read directly. */
class AnsFusionDecodeInfo extends FusionInfo

/** Fields that fusion changes on the instruction sent to rename. */
class AnsFusionDecodeReplace(val params: BundleParams = BundleParams()) extends Bundle {
  val fuType      = Valid(FuType())
  val fuOpType    = Valid(FuOpType())
  val logicalSrc2 = Valid(UInt(params.logicalRegWidth.W))
  val src2Type    = Valid(SrcType())
  val immType     = Valid(ImmType())

  def update(inst: DecodedInst): Unit = {
    when(fuType.valid)(inst.fuType             := fuType.bits)
    when(fuOpType.valid)(inst.fuOpType         := fuOpType.bits)
    when(logicalSrc2.valid)(inst.logicalSrc(1) := logicalSrc2.bits)
    when(src2Type.valid)(inst.srcType(1)       := src2Type.bits)
    when(immType.valid)(inst.immType           := immType.bits)
  }
}

/** Base class for one two-instruction fusion rule. */
abstract class AnsBaseFusionCase(pair: Seq[Valid[DecodedInst]], val params: BundleParams = BundleParams()) {
  require(pair.length == 2, "a fusion case consumes exactly two instructions")
  require(params.instructionWidth == 32, "fusion patterns are 32-bit RVB23 instructions")

  protected val lookupTable: Seq[(BitPat, AnsDecodeBase)] =
    AnsIntDecode.decodeArray ++ AnsBitmanipDecode.decodeArray

  def instr: Seq[UInt]     = pair.map(_.bits.instr)
  def rs1(i:    Int): UInt = instr(i)(19, 15)
  def rs2(i:    Int): UInt = instr(i)(24, 20)
  def rd(i:     Int): UInt = instr(i)(11, 7)
  def shamt6(i: Int): UInt = instr(i)(25, 20)
  def shamt5(i: Int): UInt = instr(i)(24, 20)

  def withSameDest: Bool   = rd(0) === rd(1)
  def destToRs1: Bool      = rd(0) === rs1(1)
  def destToRs2: Bool      = rd(0) === rs2(1)
  def instr2Rs1ToRs2: Bool = rs1(1) === rs2(1)

  protected def decodeFor(pattern: BitPat): AnsDecode =
    lookupTable
      .collectFirst { case (p, row: AnsDecode) if p == pattern => row }
      .getOrElse(throw new IllegalArgumentException(s"fusion pattern not in decode table: $pattern"))

  def replace(field: AnsDecode => Int): Option[Int] = {
    (thisInstr, fusedInstr) match {
      case (Some(original), Some(target)) =>
        val originalValue = field(decodeFor(original))
        val targetValue   = field(decodeFor(target))
        if (originalValue != targetValue) Some(targetValue) else None
      case _                              => None
    }
  }

  def isValid: Bool
  def thisInstr: Option[BitPat]  = None
  def fusedInstr: Option[BitPat] = None
  def fuType: Option[Int]        = replace(_.fuType)

  // Fusion moves the opcode into another FuType group; with per-group numbering the
  // target op's value can coincide with the source op, so raw-value comparison would
  // drop the replacement. Emit fuOpType whenever the FuType changed instead.
  def fuOpType: Option[Int] =
    (thisInstr, fusedInstr) match {
      case (_, Some(target)) if fuType.isDefined => Some(decodeFor(target).fuOpType.value.toInt)
      case _                                     => replace(_.fuOpType.value.toInt)
    }

  def src2Type: Option[Int] = replace(_.sources(1))
  // This also clears an immediate left by the first shift instruction.
  def immType: Option[Int]  = replace(_.immType)

  def lsrc2NeedZero: Boolean = false
  def lsrc2NeedMux: Boolean  = false
  def lsrc2MuxResult: UInt   = Mux(destToRs1, rs2(1), rs1(1))
  def fusionName: String
}

/** Shift-pair conversions: zext/sext idioms formed from two shifts. */
final class AnsFusedShiftPair(
  pair:          Seq[Valid[DecodedInst]],
  params:        BundleParams,
  firstPattern:  BitPat,
  firstShift:    Int,
  secondPattern: BitPat,
  secondShift:   Int,
  wordShift:     Boolean,
  targetPattern: BitPat,
  name:          String,
) extends AnsBaseFusionCase(pair, params) {
  def shift(i: Int): UInt = if (wordShift) shamt5(i) else shamt6(i)

  def isValid: Bool =
    (instr(0) === firstPattern) &&
      (instr(1) === secondPattern) &&
      (shift(0) === firstShift.U) &&
      (shift(1) === secondShift.U) &&
      withSameDest &&
      destToRs1

  override def thisInstr: Option[BitPat]  = Some(firstPattern)
  override def fusedInstr: Option[BitPat] = Some(targetPattern)
  override def lsrc2NeedZero: Boolean     = true
  def fusionName: String                  = name
}

/** slli + add idioms that become shNadd. */
final class AnsFusedShNadd(
  pair:          Seq[Valid[DecodedInst]],
  params:        BundleParams,
  shift:         Int,
  targetPattern: BitPat,
  name:          String,
) extends AnsBaseFusionCase(pair, params) {
  def isValid: Bool =
    (instr(0) === AnsRiscv.SLLI) &&
      (shamt6(0) === shift.U) &&
      (instr(1) === AnsRiscv.ADD) &&
      !instr2Rs1ToRs2 &&
      withSameDest &&
      (destToRs1 || destToRs2)

  override def thisInstr: Option[BitPat]  = Some(AnsRiscv.SLLI)
  override def fusedInstr: Option[BitPat] = Some(targetPattern)
  override def lsrc2NeedMux: Boolean      = true
  def fusionName: String                  = name
}

/** Adjacent instruction fusion. `accept` is the group transaction accepted by DecodeStage; a pair is sampled only when
  * both lanes are valid, exception-free and in the same frontend block.
  */
class AnsFusionDecoder(val params: BundleParams = BundleParams()) extends Module {
  val width = params.renameWidth
  require(width >= 2, "fusion needs at least two instructions per group")

  val io = IO(new Bundle {
    val disableFusion = Input(Bool())
    val accept        = Input(Bool())
    val in            = Vec(width, Flipped(ValidIO(new DecodedInst(params))))
    val out           = Vec(width - 1, ValidIO(new AnsFusionDecodeReplace(params)))
    val info          = Vec(width - 1, Output(new AnsFusionDecodeInfo))
    val clear         = Vec(width, Output(Bool()))
  })

  io.clear.head := false.B

  val pairs = io.in.dropRight(1).zip(io.in.drop(1)).map { case (left, right) => Seq(left, right) }
  pairs.zip(io.out).zip(io.info).zipWithIndex.foreach { case (((pair, out), info), index) =>
    val rules: Seq[AnsBaseFusionCase] = Seq(
      new AnsFusedShiftPair(
        pair,
        params,
        AnsRiscv.SLLI,
        32,
        AnsRiscv.SRLI,
        32,
        wordShift = false,
        AnsRiscv.ADD_UW,
        "slli32_srli32",
      ),
      new AnsFusedShiftPair(
        pair,
        params,
        AnsRiscv.SLLI,
        48,
        AnsRiscv.SRLI,
        48,
        wordShift = false,
        AnsRiscv.ZEXT_H,
        "slli48_srli48",
      ),
      new AnsFusedShiftPair(
        pair,
        params,
        AnsRiscv.SLLIW,
        16,
        AnsRiscv.SRLIW,
        16,
        wordShift = true,
        AnsRiscv.ZEXT_H,
        "slliw16_srliw16",
      ),
      new AnsFusedShiftPair(
        pair,
        params,
        AnsRiscv.SLLIW,
        16,
        AnsRiscv.SRAIW,
        16,
        wordShift = true,
        AnsRiscv.SEXT_H,
        "slliw16_sraiw16",
      ),
      new AnsFusedShNadd(pair, params, 1, AnsRiscv.SH1ADD, "slli1_add"),
      new AnsFusedShNadd(pair, params, 2, AnsRiscv.SH2ADD, "slli2_add"),
      new AnsFusedShNadd(pair, params, 3, AnsRiscv.SH3ADD, "slli3_add"),
    )

    val pairValid    = pair.map(_.valid).reduce(_ && _)
    val pairSafe     = !pair(0).bits.hasException &&
      !pair(1).bits.hasException &&
      !pair(0).bits.isLastInFtqEntry &&
      pair(0).bits.ftqPtr === pair(1).bits.ftqPtr
    val pairEligible = pairValid && pairSafe
    val hit          = VecInit(rules.map(_.isValid))
    val hitReg       = RegInit(VecInit(Seq.fill(rules.size)(false.B)))
    val validReg     = RegInit(false.B)
    val enabledReg   = RegInit(false.B)
    val notHintReg   = RegInit(false.B)
    val muxSourceReg = RegInit(0.U(params.logicalRegWidth.W))
    val destToRs1Reg = RegInit(false.B)

    when(io.accept) {
      when(pairEligible) {
        assert(PopCount(hit) <= 1.U, "overlapping Decode fusion rules")
      }
      hitReg       := Mux(pairEligible, hit, 0.U.asTypeOf(hit))
      validReg     := pairEligible
      enabledReg   := !io.disableFusion
      notHintReg   := pair.map(_.bits.instr(11, 7) =/= 0.U).reduce(_ && _)
      destToRs1Reg := rules.head.destToRs1
      muxSourceReg := rules.head.lsrc2MuxResult
    }

    out.valid := validReg && enabledReg && notHintReg && !io.clear(index) && hitReg.asUInt.orR

    def connectReplacement(field: AnsFusionDecodeReplace => Valid[UInt], values: Seq[Option[Int]]): Unit = {
      val candidates = hitReg.zip(values).collect { case (hitBit, Some(value)) => hitBit -> value }
      field(out.bits).valid := false.B
      field(out.bits).bits  := 0.U
      if (candidates.nonEmpty) {
        field(out.bits).valid := out.valid && candidates.map(_._1).reduce(_ || _)
        field(out.bits).bits  := Mux1H(candidates.map(_._1), candidates.map(_._2.U))
      }
    }

    connectReplacement(_.fuType, rules.map(_.fuType))
    connectReplacement(_.fuOpType, rules.map(_.fuOpType))
    connectReplacement(_.src2Type, rules.map(_.src2Type))
    connectReplacement(_.immType, rules.map(_.immType))

    val useZero = hitReg
      .zip(rules)
      .collect { case (hitBit, rule) if rule.lsrc2NeedZero => hitBit }
      .reduceOption(_ || _)
      .getOrElse(false.B)
    val useMux  = hitReg
      .zip(rules)
      .collect { case (hitBit, rule) if rule.lsrc2NeedMux => hitBit }
      .reduceOption(_ || _)
      .getOrElse(false.B)

    out.bits.logicalSrc2.valid := out.valid && enabledReg && (useZero || useMux)
    out.bits.logicalSrc2.bits  := Mux(useMux, muxSourceReg, 0.U)
    info.rs2FromZero           := out.valid && enabledReg && useZero
    info.rs2FromRs1            := out.valid && enabledReg && useMux && !destToRs1Reg
    info.rs2FromRs2            := out.valid && enabledReg && useMux && destToRs1Reg

    // A successful fusion consumes the second instruction in the pair.
    io.clear(index + 1) := out.valid
  }
}
