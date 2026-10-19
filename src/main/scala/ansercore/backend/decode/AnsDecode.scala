/* Self-contained scalar RVB23 decoder for AnserCore.
 *
 * 这是一个“精简自包含”的标量 RVB23 译码器。它参考香山
 * (XiangShan) backend/decode 的列式译码表组织方式，但只保留标量 RVB23 所需的扩展，去掉了
 * 向量、多微操作拆分(DecodeUnitComp)、虚拟化与调试译码。
 *
 * 设计原则：
 *   1. 只依赖 chisel3 与 ansercore.backend.Bundles（不引入 rocket-chip 等外部依赖）。
 *   2. 译码表使用“列式 List[BitPat]”风格：每条 AnsDecode 生成一行，所有行拼成 decodeTable。
 *   3. 整数/浮点之间的转换(原香山 i2v/i2f/f2v/fcvt)统一收敛到 Bundles 已有的标量 FuType。
 *
 * 该实现直接属于 backend.decode 包，作为 Decode 的唯一标量译码基线。
 */
package ansercore.backend.decode

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.Bundles.{BundleParams, CommitType, DecodedInst, FuOpType, FuType, ImmType, SrcType, StaticInst}
import ansercore.backend.exu.csr.AnsCsrDecodeState

/* 位模式构造小工具。
 *
 * 香山通过 rocket-chip 的隐式 `uintToBitPat` / `Boolean.B` 生成列值；这里用最朴素的方式实现同样的
 * 效果，从而不依赖 rocket-chip。生成的 BitPat 都是“无通配”的定值模式（mask 全 1），因此可以
 * 直接用 `.value` 取出常量、用 `.mask.bitLength` 取出位宽。
 */
object AnsBits {
  def constPat(value: Int, width: Int): BitPat = {
    require(width > 0, "decode column width must be positive")
    val bin    = value.toBinaryString
    val padded = "0" * math.max(0, width - bin.length) + bin
    require(padded.length == width, s"value $value does not fit in $width bits")
    BitPat("b" + padded)
  }

  def boolPat(value: Boolean): BitPat = if (value) BitPat("b1") else BitPat("b0")
}

/* 非法指令与取指异常在 exceptionBits 中的位置（与现有 DecodeUnit 约定一致）。 */
object AnsDecodeExceptionBits {
  val illegalInstruction = 0
  val fetchFault         = 1
}

/* Zfa 的 D 精度 op 与 Zicbop prefetch 已并入 Bundles 的 FALUOpType / LSUOpType。 */

/* 译码列式表的公共常量与默认行。 */
trait AnsDecodeConstants {
  // 只用于“模式”里表示通配/常量；列值仍需用 AnsBits.constPat / boolPat 生成定值。
  def X = BitPat("b?")
  def N = BitPat("b0")
  def Y = BitPat("b1")
  def T = true
  def F = false

  /* 未命中任何模式时的默认行。
   *
   * 与香山用 selImm = INVALID_INSTR 表示非法不同，这里直接把默认行的 isIllegal 置为 true：
   * 查表未命中即非法指令，等价且更简单。
   */
  def decodeDefault: List[BitPat] = List(
    AnsBits.constPat(SrcType.none, SrcType.width),
    AnsBits.constPat(SrcType.none, SrcType.width),
    AnsBits.constPat(SrcType.none, SrcType.width),
    AnsBits.constPat(FuType.other, FuType.width),
    FuOpType.X,
    AnsBits.boolPat(false),                       // intWen
    AnsBits.boolPat(false),                       // fpWen
    AnsBits.boolPat(true),                        // isIllegal：默认非法
    AnsBits.boolPat(false),                       // blockBackward
    AnsBits.boolPat(false),                       // commitSelfFlush
    AnsBits.boolPat(false),                       // canRobCompress
    AnsBits.boolPat(false),                       // waitForward
    AnsBits.boolPat(false),                       // needsRoundingMode
    AnsBits.constPat(ImmType.none, ImmType.width), // selImm
  )
}

/* 一条标量译码表项。`generate()` 产出一行列值，顺序必须与 decodeDefault 完全一致。 */
abstract class AnsDecodeBase {
  def generate(): List[BitPat]
}

/* 标量译码行：三个源类型槽 + FU/微操作 + 各类控制位 + 立即数类型。 */
class AnsDecode(
  val sources:           Seq[Int],
  val fuType:            Int,
  val fuOpType:          BitPat,
  val immType:           Int,
  val intWen:            Boolean,
  val fpWen:             Boolean,
  val isIllegal:         Boolean,
  val waitForward:       Boolean,
  val blockBackward:     Boolean,
  val commitSelfFlush:   Boolean,
  val canRobCompress:    Boolean,
  val needsRoundingMode: Boolean,
) extends AnsDecodeBase {
  require(sources.length == 3, "scalar decode entries must describe three source slots")

  def generate(): List[BitPat] = List(
    AnsBits.constPat(sources(0), SrcType.width),
    AnsBits.constPat(sources(1), SrcType.width),
    AnsBits.constPat(sources(2), SrcType.width),
    AnsBits.constPat(fuType, FuType.width),
    fuOpType,
    AnsBits.boolPat(intWen),
    AnsBits.boolPat(fpWen),
    AnsBits.boolPat(isIllegal),
    AnsBits.boolPat(blockBackward),
    AnsBits.boolPat(commitSelfFlush),
    AnsBits.boolPat(canRobCompress),
    AnsBits.boolPat(waitForward),
    AnsBits.boolPat(needsRoundingMode),
    AnsBits.constPat(immType, ImmType.width),
  )
}

object AnsDecode {
  def apply(
    sources:           Seq[Int],
    fuType:            Int,
    fuOpType:          UInt,
    immType:           Int     = ImmType.none,
    intWen:            Boolean = false,
    fpWen:             Boolean = false,
    isIllegal:         Boolean = false,
    waitForward:       Boolean = false,
    blockBackward:     Boolean = false,
    commitSelfFlush:   Boolean = false,
    canRobCompress:    Boolean = false,
    needsRoundingMode: Boolean = false,
  ): AnsDecode = new AnsDecode(
    sources,
    fuType,
    BitPat(fuOpType),
    immType,
    intWen,
    fpWen,
    isIllegal,
    waitForward,
    blockBackward,
    commitSelfFlush,
    canRobCompress,
    needsRoundingMode,
  )
}

/* 常见源类型组合，供各扩展译码表复用。 */
trait AnsDecodeSources {
  val noSources        = Seq(SrcType.none, SrcType.none, SrcType.none)
  val integerUnary     = Seq(SrcType.integer, SrcType.none, SrcType.none)
  val integerBinary    = Seq(SrcType.integer, SrcType.integer, SrcType.none)
  val integerImmediate = Seq(SrcType.integer, SrcType.immediate, SrcType.none)
  val floatingUnary    = Seq(SrcType.floating, SrcType.none, SrcType.none)
  val floatingBinary   = Seq(SrcType.floating, SrcType.floating, SrcType.none)
  val floatingTernary  = Seq(SrcType.floating, SrcType.floating, SrcType.floating)

  /* 便捷构造：一行 (模式 -> 译码结果)。 */
  def entry(
    pattern:           BitPat,
    sources:           Seq[Int],
    fu:                Int,
    op:                UInt,
    imm:               Int     = ImmType.none,
    intWen:            Boolean = false,
    fpWen:             Boolean = false,
    waitForward:       Boolean = false,
    blockBackward:     Boolean = false,
    commitSelfFlush:   Boolean = false,
    canRobCompress:    Boolean = false,
    needsRoundingMode: Boolean = false,
  ): (BitPat, AnsDecodeBase) = pattern -> AnsDecode(
    sources,
    fu,
    op,
    imm,
    intWen,
    fpWen,
    false,
    waitForward,
    blockBackward,
    commitSelfFlush,
    canRobCompress,
    needsRoundingMode,
  )
}

/* RVB23 标量指令的 32 位编码（压缩指令由 AnsRvcExpander 展开后再进入此表）。
 *
 * 这些模式与现有 decode/bundles.scala 中的 RiscvInstructions 保持一致，便于新旧实现对照。
 */
object AnsRiscv {
  def wildcards(count: Int): String                                                 = "?" * count
  def pattern(bits:    String): BitPat                                              = BitPat("b" + bits)
  def masked(value: BigInt, mask: BigInt): BitPat                                   = {
    val bits = (31 to 0 by -1).map { bit =>
      if (((mask >> bit) & 1) == 1) {
        if (((value >> bit) & 1) == 1) "1" else "0"
      } else "?"
    }.mkString
    pattern(bits)
  }
  def rType(funct7: String, funct3: String, opcode: String = "0110011"): BitPat     =
    pattern(s"$funct7${wildcards(10)}$funct3${wildcards(5)}$opcode")
  def iType(funct3: String, opcode: String = "0010011"): BitPat                     =
    pattern(s"${wildcards(12)}${wildcards(5)}$funct3${wildcards(5)}$opcode")
  def shiftType(funct6: String, funct3: String, opcode: String = "0010011"): BitPat =
    pattern(s"$funct6${wildcards(11)}$funct3${wildcards(5)}$opcode")
  def rTypeW(funct7:   String, funct3: String): BitPat                              = rType(funct7, funct3, "0111011")
  def iTypeW(funct3:   String): BitPat                                              = iType(funct3, "0011011")
  def branchType(funct3: String): BitPat                                            =
    pattern(s"${wildcards(7)}${wildcards(5)}${wildcards(5)}$funct3${wildcards(5)}1100011")
  def loadType(funct3: String, opcode: String = "0000011"): BitPat                  =
    pattern(s"${wildcards(12)}${wildcards(5)}$funct3${wildcards(5)}$opcode")
  def storeType(funct3: String, opcode: String = "0100011"): BitPat                 =
    pattern(s"${wildcards(7)}${wildcards(5)}${wildcards(5)}$funct3${wildcards(5)}$opcode")
  def fpType(funct7:   String, funct3: String): BitPat                              = rType(funct7, funct3, "1010011")
  def fpFma(fmt:       String, opcode: String): BitPat                              = pattern(s"${wildcards(5)}$fmt${wildcards(18)}$opcode")
  def atomicType(funct5: String, funct3: String, rs2: String = "?????"): BitPat     =
    pattern(s"$funct5??$rs2${wildcards(5)}$funct3${wildcards(5)}0101111")

  // RV64I 与标量控制流。
  val LB    = loadType("000")
  val LH    = loadType("001")
  val LW    = loadType("010")
  val LD    = loadType("011")
  val LBU   = loadType("100")
  val LHU   = loadType("101")
  val LWU   = loadType("110")
  val SB    = storeType("000")
  val SH    = storeType("001")
  val SW    = storeType("010")
  val SD    = storeType("011")
  val LUI   = pattern(s"${wildcards(20)}${wildcards(5)}0110111")
  val AUIPC = pattern(s"${wildcards(20)}${wildcards(5)}0010111")
  val JAL   = pattern(s"${wildcards(20)}${wildcards(5)}1101111")
  val JALR  = iType("000", "1100111")
  val BEQ   = branchType("000")
  val BNE   = branchType("001")
  val BLT   = branchType("100")
  val BGE   = branchType("101")
  val BLTU  = branchType("110")
  val BGEU  = branchType("111")

  val ADDI  = iType("000")
  val SLTI  = iType("010")
  val SLTIU = iType("011")
  val XORI  = iType("100")
  val ORI   = iType("110")
  val ANDI  = iType("111")
  val SLLI  = shiftType("000000", "001")
  val SRLI  = shiftType("000000", "101")
  val SRAI  = shiftType("010000", "101")
  val ADD   = rType("0000000", "000")
  val SUB   = rType("0100000", "000")
  val SLL   = rType("0000000", "001")
  val SLT   = rType("0000000", "010")
  val SLTU  = rType("0000000", "011")
  val XOR   = rType("0000000", "100")
  val SRL   = rType("0000000", "101")
  val SRA   = rType("0100000", "101")
  val OR    = rType("0000000", "110")
  val AND   = rType("0000000", "111")

  val ADDIW = iTypeW("000")
  val SLLIW = pattern(s"0000000${wildcards(10)}001${wildcards(5)}0011011")
  val SRLIW = pattern(s"0000000${wildcards(10)}101${wildcards(5)}0011011")
  val SRAIW = pattern(s"0100000${wildcards(10)}101${wildcards(5)}0011011")
  val ADDW  = rTypeW("0000000", "000")
  val SUBW  = rTypeW("0100000", "000")
  val SLLW  = rTypeW("0000000", "001")
  val SRLW  = rTypeW("0000000", "101")
  val SRAW  = rTypeW("0100000", "101")

  // M 扩展。
  val MUL    = rType("0000001", "000")
  val MULH   = rType("0000001", "001")
  val MULHSU = rType("0000001", "010")
  val MULHU  = rType("0000001", "011")
  val DIV    = rType("0000001", "100")
  val DIVU   = rType("0000001", "101")
  val REM    = rType("0000001", "110")
  val REMU   = rType("0000001", "111")
  val MULW   = rTypeW("0000001", "000")
  val DIVW   = rTypeW("0000001", "100")
  val DIVUW  = rTypeW("0000001", "101")
  val REMW   = rTypeW("0000001", "110")
  val REMUW  = rTypeW("0000001", "111")

  // A 扩展（省略需要额外多字执行约定的比较并交换指令）。
  val LR_W      = atomicType("00010", "010", "00000")
  val SC_W      = atomicType("00011", "010")
  val AMOSWAP_W = atomicType("00001", "010")
  val AMOADD_W  = atomicType("00000", "010")
  val AMOXOR_W  = atomicType("00100", "010")
  val AMOAND_W  = atomicType("01100", "010")
  val AMOOR_W   = atomicType("01000", "010")
  val AMOMIN_W  = atomicType("10000", "010")
  val AMOMAX_W  = atomicType("10100", "010")
  val AMOMINU_W = atomicType("11000", "010")
  val AMOMAXU_W = atomicType("11100", "010")
  val LR_D      = atomicType("00010", "011", "00000")
  val SC_D      = atomicType("00011", "011")
  val AMOSWAP_D = atomicType("00001", "011")
  val AMOADD_D  = atomicType("00000", "011")
  val AMOXOR_D  = atomicType("00100", "011")
  val AMOAND_D  = atomicType("01100", "011")
  val AMOOR_D   = atomicType("01000", "011")
  val AMOMIN_D  = atomicType("10000", "011")
  val AMOMAX_D  = atomicType("10100", "011")
  val AMOMINU_D = atomicType("11000", "011")
  val AMOMAXU_D = atomicType("11100", "011")

  // Zba / Zbb / Zbs / Zicond（RVB23 的 B 集合 + Zicond）。
  val ADD_UW    = rType("0000100", "000", "0111011")
  val SH1ADD    = rType("0010000", "010")
  val SH1ADD_UW = rType("0010000", "010", "0111011")
  val SH2ADD    = rType("0010000", "100")
  val SH2ADD_UW = rType("0010000", "100", "0111011")
  val SH3ADD    = rType("0010000", "110")
  val SH3ADD_UW = rType("0010000", "110", "0111011")
  val SLLI_UW   = shiftType("000010", "001", "0011011")
  val ANDN      = rType("0100000", "111")
  val ORN       = rType("0100000", "110")
  val XNOR      = rType("0100000", "100")
  val CLZ       = pattern("011000000000?????001?????0010011")
  val CLZW      = pattern("011000000000?????001?????0011011")
  val CTZ       = pattern("011000000001?????001?????0010011")
  val CTZW      = pattern("011000000001?????001?????0011011")
  val CPOP      = pattern("011000000010?????001?????0010011")
  val CPOPW     = pattern("011000000010?????001?????0011011")
  val MIN       = rType("0000101", "100")
  val MINU      = rType("0000101", "101")
  val MAX       = rType("0000101", "110")
  val MAXU      = rType("0000101", "111")
  val SEXT_B    = pattern("011000000100?????001?????0010011")
  val SEXT_H    = pattern("011000000101?????001?????0010011")
  val ZEXT_H    = pattern("000010000000?????100?????0111011")
  val ROL       = rType("0110000", "001")
  val ROLW      = rTypeW("0110000", "001")
  val ROR       = rType("0110000", "101")
  val RORW      = rTypeW("0110000", "101")
  val RORI      = shiftType("011000", "101")
  val RORIW     = pattern("0110000??????????101?????0011011")
  val ORC_B     = pattern("001010000111?????101?????0010011")
  val REV8      = pattern("011010111000?????101?????0010011")
  val BCLR      = rType("0100100", "001")
  val BCLRI     = shiftType("010010", "001")
  val BEXT      = rType("0100100", "101")
  val BEXTI     = shiftType("010010", "101")
  val BINV      = rType("0110100", "001")
  val BINVI     = shiftType("011010", "001")
  val BSET      = rType("0010100", "001")
  val BSETI     = shiftType("001010", "001")
  val CZERO_EQZ = rType("0000111", "101")
  val CZERO_NEZ = rType("0000111", "111")

  // F/D encodings. H encodings are kept as reference constants but are not
  // inserted into the RVB23 mandatory decode table below (Zfhmin is optional).
  val FLW       = loadType("010", "0000111")
  val FLD       = loadType("011", "0000111")
  val FLH       = loadType("001", "0000111")
  val FSW       = storeType("010", "0100111")
  val FSD       = storeType("011", "0100111")
  val FSH       = storeType("001", "0100111")
  val FADD_S    = pattern("0000000??????????????????1010011")
  val FSUB_S    = pattern("0000100??????????????????1010011")
  val FMUL_S    = pattern("0001000??????????????????1010011")
  val FDIV_S    = pattern("0001100??????????????????1010011")
  val FSQRT_S   = pattern("010110000000?????????????1010011")
  val FMIN_S    = fpType("0010100", "000")
  val FMAX_S    = fpType("0010100", "001")
  val FSGNJ_S   = fpType("0010000", "000")
  val FSGNJN_S  = fpType("0010000", "001")
  val FSGNJX_S  = fpType("0010000", "010")
  val FEQ_S     = fpType("1010000", "010")
  val FLE_S     = fpType("1010000", "000")
  val FLT_S     = fpType("1010000", "001")
  val FLEQ_S    = fpType("1010000", "100")
  val FLTQ_S    = fpType("1010000", "101")
  val FCLASS_S  = pattern("111000000000?????001?????1010011")
  val FADD_D    = pattern("0000001??????????????????1010011")
  val FSUB_D    = pattern("0000101??????????????????1010011")
  val FMUL_D    = pattern("0001001??????????????????1010011")
  val FDIV_D    = pattern("0001101??????????????????1010011")
  val FSQRT_D   = pattern("010110100000?????????????1010011")
  val FMIN_D    = fpType("0010101", "000")
  val FMAX_D    = fpType("0010101", "001")
  val FSGNJ_D   = fpType("0010001", "000")
  val FSGNJN_D  = fpType("0010001", "001")
  val FSGNJX_D  = fpType("0010001", "010")
  val FEQ_D     = fpType("1010001", "010")
  val FLE_D     = fpType("1010001", "000")
  val FLT_D     = fpType("1010001", "001")
  val FLEQ_D    = fpType("1010001", "100")
  val FLTQ_D    = fpType("1010001", "101")
  val FCLASS_D  = pattern("111000100000?????001?????1010011")
  val FMADD_S   = fpFma("00", "1000011")
  val FMSUB_S   = fpFma("00", "1000111")
  val FNMSUB_S  = fpFma("00", "1001011")
  val FNMADD_S  = fpFma("00", "1001111")
  val FMADD_D   = fpFma("01", "1000011")
  val FMSUB_D   = fpFma("01", "1000111")
  val FNMSUB_D  = fpFma("01", "1001011")
  val FNMADD_D  = fpFma("01", "1001111")
  val FMV_X_W   = pattern("111000000000?????000?????1010011")
  val FMV_W_X   = pattern("111100000000?????000?????1010011")
  val FMV_X_D   = pattern("111000100000?????000?????1010011")
  val FMV_D_X   = pattern("111100100000?????000?????1010011")
  val FMV_X_H   = pattern("111001000000?????000?????1010011")
  val FMV_H_X   = pattern("111101000000?????000?????1010011")
  val FCVT_W_S  = pattern("110000000000?????????????1010011")
  val FCVT_WU_S = pattern("110000000001?????????????1010011")
  val FCVT_L_S  = pattern("110000000010?????????????1010011")
  val FCVT_LU_S = pattern("110000000011?????????????1010011")
  val FCVT_S_W  = pattern("110100000000?????????????1010011")
  val FCVT_S_WU = pattern("110100000001?????????????1010011")
  val FCVT_S_L  = pattern("110100000010?????????????1010011")
  val FCVT_S_LU = pattern("110100000011?????????????1010011")
  val FCVT_W_D  = pattern("110000100000?????????????1010011")
  val FCVT_WU_D = pattern("110000100001?????????????1010011")
  val FCVT_L_D  = pattern("110000100010?????????????1010011")
  val FCVT_LU_D = pattern("110000100011?????????????1010011")
  val FCVT_D_W  = pattern("110100100000?????????????1010011")
  val FCVT_D_WU = pattern("110100100001?????????????1010011")
  val FCVT_D_L  = pattern("110100100010?????????????1010011")
  val FCVT_D_LU = pattern("110100100011?????????????1010011")
  val FCVT_S_D  = pattern("010000000001?????????????1010011")
  val FCVT_D_S  = pattern("010000100000?????????????1010011")
  val FCVT_S_H  = pattern("010000000010?????????????1010011")
  val FCVT_H_S  = pattern("010001000000?????????????1010011")

  // Zfa。
  val FLI_S       = pattern("111100000001?????000?????1010011")
  val FLI_D       = pattern("111100100001?????000?????1010011")
  val FLI_H       = pattern("111101000001?????000?????1010011")
  val FMINM_S     = fpType("0010100", "010")
  val FMAXM_S     = fpType("0010100", "011")
  val FMINM_D     = fpType("0010101", "010")
  val FMAXM_D     = fpType("0010101", "011")
  val FROUND_S    = pattern("010000000100?????????????1010011")
  val FROUND_D    = pattern("010000100100?????????????1010011")
  val FROUNDNX_S  = pattern("010000000101?????????????1010011")
  val FROUNDNX_D  = pattern("010000100101?????????????1010011")
  val FCVTMOD_W_D = pattern("110000101000?????001?????1010011")

  // 特权、排序与缓存管理。
  val CSRRW      = iType("001", "1110011")
  val CSRRS      = iType("010", "1110011")
  val CSRRC      = iType("011", "1110011")
  val CSRRWI     = iType("101", "1110011")
  val CSRRSI     = iType("110", "1110011")
  val CSRRCI     = iType("111", "1110011")
  val ECALL      = pattern("00000000000000000000000001110011")
  val EBREAK     = pattern("00000000000100000000000001110011")
  val SRET       = pattern("00010000001000000000000001110011")
  val MRET       = pattern("00110000001000000000000001110011")
  val WFI        = pattern("00010000010100000000000001110011")
  val SFENCE_VMA = pattern("0001001??????????000000001110011")
  val FENCE      = iType("000", "0001111")
  val FENCE_I    = iType("001", "0001111")
  val PAUSE      = pattern("00000001000000000000000000001111")
  val WRS_NTO    = pattern("00000000110100000000000001110011")
  val WRS_STO    = pattern("00000001110100000000000001110011")
  val CBO_INVAL  = pattern("000000000000?????010000000001111")
  val CBO_CLEAN  = pattern("000000000001?????010000000001111")
  val CBO_FLUSH  = pattern("000000000010?????010000000001111")
  val CBO_ZERO   = pattern("000000000100?????010000000001111")
  // Zicbop. The low five immediate bits are fixed to zero.
  val PREFETCH_I = masked(BigInt("6013", 16), BigInt("1f07fff", 16))
  val PREFETCH_R = masked(BigInt("106013", 16), BigInt("1f07fff", 16))
  val PREFETCH_W = masked(BigInt("306013", 16), BigInt("1f07fff", 16))
  // Zihintntl aliases. They are hints, so they must not create an rs2 dependency.
  val NTL_P1     = pattern("00000000001000000000000000110011")
  val NTL_PALL   = pattern("00000000001100000000000000110011")
  val NTL_S1     = pattern("00000000010000000000000000110011")
  val NTL_ALL    = pattern("00000000010100000000000000110011")
  val MOP_R      = pattern("1?00??0111???????100?????1110011")
  val MOP_RR     = pattern("1?00??1??????????100?????1110011")
}

/* 整数、乘除、控制流、原子与访存。 */
object AnsIntDecode extends AnsDecodeConstants with AnsDecodeSources {
  import AnsRiscv._
  import FuType.{alu, brh, div, jmp, ldu, mou, mul, stu}

  val decodeArray: Array[(BitPat, AnsDecodeBase)] = Array(
    // RV64I 访存。
    entry(LB, integerImmediate, ldu, LSUOpType.lb, ImmType.i, intWen                                         = true),
    entry(LH, integerImmediate, ldu, LSUOpType.lh, ImmType.i, intWen                                         = true),
    entry(LW, integerImmediate, ldu, LSUOpType.lw, ImmType.i, intWen                                         = true),
    entry(LD, integerImmediate, ldu, LSUOpType.ld, ImmType.i, intWen                                         = true),
    entry(LBU, integerImmediate, ldu, LSUOpType.lbu, ImmType.i, intWen                                       = true),
    entry(LHU, integerImmediate, ldu, LSUOpType.lhu, ImmType.i, intWen                                       = true),
    entry(LWU, integerImmediate, ldu, LSUOpType.lwu, ImmType.i, intWen                                       = true),
    entry(SB, integerBinary, stu, LSUOpType.sb, ImmType.s),
    entry(SH, integerBinary, stu, LSUOpType.sh, ImmType.s),
    entry(SW, integerBinary, stu, LSUOpType.sw, ImmType.s),
    entry(SD, integerBinary, stu, LSUOpType.sd, ImmType.s),
    entry(LUI, Seq(SrcType.none, SrcType.immediate, SrcType.none), alu, ALUOpType.lui, ImmType.u, intWen     = true),
    entry(AUIPC, Seq(SrcType.none, SrcType.immediate, SrcType.none), alu, ALUOpType.auipc, ImmType.u, intWen = true),
    // RV64I 运算。
    entry(ADDI, integerImmediate, alu, ALUOpType.add, ImmType.i, intWen                                      = true, canRobCompress = true),
    entry(SLTI, integerImmediate, alu, ALUOpType.slt, ImmType.i, intWen                                      = true, canRobCompress = true),
    entry(SLTIU, integerImmediate, alu, ALUOpType.sltu, ImmType.i, intWen                                    = true, canRobCompress = true),
    entry(XORI, integerImmediate, alu, ALUOpType.xor, ImmType.i, intWen                                      = true, canRobCompress = true),
    entry(ORI, integerImmediate, alu, ALUOpType.or, ImmType.i, intWen                                        = true, canRobCompress = true),
    entry(ANDI, integerImmediate, alu, ALUOpType.and, ImmType.i, intWen                                      = true, canRobCompress = true),
    entry(SLLI, integerImmediate, alu, ALUOpType.slli, ImmType.i, intWen                                     = true, canRobCompress = true),
    entry(SRLI, integerImmediate, alu, ALUOpType.srli, ImmType.i, intWen                                     = true, canRobCompress = true),
    entry(SRAI, integerImmediate, alu, ALUOpType.srai, ImmType.i, intWen                                     = true, canRobCompress = true),
    entry(ADD, integerBinary, alu, ALUOpType.add, intWen                                                     = true, canRobCompress = true),
    entry(SUB, integerBinary, alu, ALUOpType.sub, intWen                                                     = true, canRobCompress = true),
    entry(SLL, integerBinary, alu, ALUOpType.sll, intWen                                                     = true, canRobCompress = true),
    entry(SLT, integerBinary, alu, ALUOpType.slt, intWen                                                     = true, canRobCompress = true),
    entry(SLTU, integerBinary, alu, ALUOpType.sltu, intWen                                                   = true, canRobCompress = true),
    entry(XOR, integerBinary, alu, ALUOpType.xor, intWen                                                     = true, canRobCompress = true),
    entry(SRL, integerBinary, alu, ALUOpType.srl, intWen                                                     = true, canRobCompress = true),
    entry(SRA, integerBinary, alu, ALUOpType.sra, intWen                                                     = true, canRobCompress = true),
    entry(OR, integerBinary, alu, ALUOpType.or, intWen                                                       = true, canRobCompress = true),
    entry(AND, integerBinary, alu, ALUOpType.and, intWen                                                     = true, canRobCompress = true),
    entry(ADDIW, integerImmediate, alu, ALUOpType.addw, ImmType.i, intWen                                    = true, canRobCompress = true),
    entry(SLLIW, integerImmediate, alu, ALUOpType.slliw, ImmType.i, intWen                                   = true, canRobCompress = true),
    entry(SRLIW, integerImmediate, alu, ALUOpType.srliw, ImmType.i, intWen                                   = true, canRobCompress = true),
    entry(SRAIW, integerImmediate, alu, ALUOpType.sraiw, ImmType.i, intWen                                   = true, canRobCompress = true),
    entry(ADDW, integerBinary, alu, ALUOpType.addw, intWen                                                   = true, canRobCompress = true),
    entry(SUBW, integerBinary, alu, ALUOpType.subw, intWen                                                   = true, canRobCompress = true),
    entry(SLLW, integerBinary, alu, ALUOpType.sllw, intWen                                                   = true, canRobCompress = true),
    entry(SRLW, integerBinary, alu, ALUOpType.srlw, intWen                                                   = true, canRobCompress = true),
    entry(SRAW, integerBinary, alu, ALUOpType.sraw, intWen                                                   = true, canRobCompress = true),
    // M 扩展。
    entry(MUL, integerBinary, mul, MULOpType.mul, intWen                                                     = true, canRobCompress = true),
    entry(MULH, integerBinary, mul, MULOpType.mulh, intWen                                                   = true, canRobCompress = true),
    entry(MULHSU, integerBinary, mul, MULOpType.mulhsu, intWen                                               = true, canRobCompress = true),
    entry(MULHU, integerBinary, mul, MULOpType.mulhu, intWen                                                 = true, canRobCompress = true),
    entry(MULW, integerBinary, mul, MULOpType.mulw, intWen                                                   = true, canRobCompress = true),
    entry(DIV, integerBinary, div, DIVOpType.div, intWen                                                     = true, canRobCompress = true),
    entry(DIVU, integerBinary, div, DIVOpType.divu, intWen                                                   = true, canRobCompress = true),
    entry(REM, integerBinary, div, DIVOpType.rem, intWen                                                     = true, canRobCompress = true),
    entry(REMU, integerBinary, div, DIVOpType.remu, intWen                                                   = true, canRobCompress = true),
    entry(DIVW, integerBinary, div, DIVOpType.divw, intWen                                                   = true, canRobCompress = true),
    entry(DIVUW, integerBinary, div, DIVOpType.divuw, intWen                                                 = true, canRobCompress = true),
    entry(REMW, integerBinary, div, DIVOpType.remw, intWen                                                   = true, canRobCompress = true),
    entry(REMUW, integerBinary, div, DIVOpType.remuw, intWen                                                 = true, canRobCompress = true),
    // 跳转与条件分支。
    entry(JAL, Seq(SrcType.none, SrcType.immediate, SrcType.none), jmp, JumpOpType.jal, ImmType.j, intWen    = true),
    entry(JALR, integerImmediate, jmp, JumpOpType.jalr, ImmType.i, intWen                                    = true),
    entry(BEQ, integerBinary, brh, BRUOpType.beq, ImmType.b),
    entry(BNE, integerBinary, brh, BRUOpType.bne, ImmType.b),
    entry(BLT, integerBinary, brh, BRUOpType.blt, ImmType.b),
    entry(BGE, integerBinary, brh, BRUOpType.bge, ImmType.b),
    entry(BLTU, integerBinary, brh, BRUOpType.bltu, ImmType.b),
    entry(BGEU, integerBinary, brh, BRUOpType.bgeu, ImmType.b),
    // A 扩展。
    entry(LR_W, integerUnary, mou, LSUOpType.lrW, intWen                                                     = true, waitForward    = true, blockBackward = true),
    entry(SC_W, integerBinary, mou, LSUOpType.scW, intWen                                                    = true, waitForward    = true, blockBackward = true),
    entry(AMOSWAP_W, integerBinary, mou, LSUOpType.amoswapW, intWen                                          = true, waitForward    = true, blockBackward = true),
    entry(AMOADD_W, integerBinary, mou, LSUOpType.amoaddW, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOXOR_W, integerBinary, mou, LSUOpType.amoxorW, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOAND_W, integerBinary, mou, LSUOpType.amoandW, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOOR_W, integerBinary, mou, LSUOpType.amoorW, intWen                                              = true, waitForward    = true, blockBackward = true),
    entry(AMOMIN_W, integerBinary, mou, LSUOpType.amominW, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOMINU_W, integerBinary, mou, LSUOpType.amominuW, intWen                                          = true, waitForward    = true, blockBackward = true),
    entry(AMOMAX_W, integerBinary, mou, LSUOpType.amomaxW, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOMAXU_W, integerBinary, mou, LSUOpType.amomaxuW, intWen                                          = true, waitForward    = true, blockBackward = true),
    entry(LR_D, integerUnary, mou, LSUOpType.lrD, intWen                                                     = true, waitForward    = true, blockBackward = true),
    entry(SC_D, integerBinary, mou, LSUOpType.scD, intWen                                                    = true, waitForward    = true, blockBackward = true),
    entry(AMOSWAP_D, integerBinary, mou, LSUOpType.amoswapD, intWen                                          = true, waitForward    = true, blockBackward = true),
    entry(AMOADD_D, integerBinary, mou, LSUOpType.amoaddD, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOXOR_D, integerBinary, mou, LSUOpType.amoxorD, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOAND_D, integerBinary, mou, LSUOpType.amoandD, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOOR_D, integerBinary, mou, LSUOpType.amoorD, intWen                                              = true, waitForward    = true, blockBackward = true),
    entry(AMOMIN_D, integerBinary, mou, LSUOpType.amominD, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOMINU_D, integerBinary, mou, LSUOpType.amominuD, intWen                                          = true, waitForward    = true, blockBackward = true),
    entry(AMOMAX_D, integerBinary, mou, LSUOpType.amomaxD, intWen                                            = true, waitForward    = true, blockBackward = true),
    entry(AMOMAXU_D, integerBinary, mou, LSUOpType.amomaxuD, intWen                                          = true, waitForward    = true, blockBackward = true),
  )
}

/* RVB23 必选的 B 子集（Zba/Zbb/Zbs）与 Zicond。 */
object AnsBitmanipDecode extends AnsDecodeConstants with AnsDecodeSources {
  import AnsRiscv._
  import FuType.bku

  def bin(pattern: BitPat, op: UInt): (BitPat, AnsDecodeBase) =
    entry(pattern, integerBinary, bku, op, intWen = true, canRobCompress = true)
  def un(pattern: BitPat, op: UInt): (BitPat, AnsDecodeBase)  =
    entry(pattern, integerUnary, bku, op, intWen = true, canRobCompress = true)
  def imm(pattern: BitPat, op: UInt): (BitPat, AnsDecodeBase) =
    entry(pattern, integerImmediate, bku, op, ImmType.i, intWen = true, canRobCompress = true)

  val decodeArray: Array[(BitPat, AnsDecodeBase)] = Array(
    bin(ADD_UW, BKUOpType.addUw),
    bin(SH1ADD, BKUOpType.sh1add),
    bin(SH1ADD_UW, BKUOpType.sh1addUw),
    bin(SH2ADD, BKUOpType.sh2add),
    bin(SH2ADD_UW, BKUOpType.sh2addUw),
    bin(SH3ADD, BKUOpType.sh3add),
    bin(SH3ADD_UW, BKUOpType.sh3addUw),
    imm(SLLI_UW, BKUOpType.slliUw),
    bin(ANDN, BKUOpType.andn),
    bin(ORN, BKUOpType.orn),
    bin(XNOR, BKUOpType.xnor),
    un(CLZ, BKUOpType.clz),
    un(CLZW, BKUOpType.clzw),
    un(CTZ, BKUOpType.ctz),
    un(CTZW, BKUOpType.ctzw),
    un(CPOP, BKUOpType.cpop),
    un(CPOPW, BKUOpType.cpopw),
    bin(MIN, BKUOpType.min),
    bin(MINU, BKUOpType.minu),
    bin(MAX, BKUOpType.max),
    bin(MAXU, BKUOpType.maxu),
    un(SEXT_B, BKUOpType.sextb),
    un(SEXT_H, BKUOpType.sexth),
    un(ZEXT_H, BKUOpType.zexth),
    bin(ROL, BKUOpType.rol),
    bin(ROLW, BKUOpType.rolw),
    bin(ROR, BKUOpType.ror),
    bin(RORW, BKUOpType.rorw),
    imm(RORI, BKUOpType.rori),
    imm(RORIW, BKUOpType.roriw),
    un(ORC_B, BKUOpType.orcb),
    un(REV8, BKUOpType.rev8),
    bin(BCLR, BKUOpType.bclr),
    imm(BCLRI, BKUOpType.bclri),
    bin(BEXT, BKUOpType.bext),
    imm(BEXTI, BKUOpType.bexti),
    bin(BINV, BKUOpType.binv),
    imm(BINVI, BKUOpType.binvi),
    bin(BSET, BKUOpType.bset),
    imm(BSETI, BKUOpType.bseti),
    bin(CZERO_EQZ, BKUOpType.czeroEqz),
    bin(CZERO_NEZ, BKUOpType.czeroNez),
  )
}

/* F/D scalar decode. Optional Zfhmin encodings remain in AnsRiscv as reference
 * constants but are deliberately not inserted into decodeArray.
 *
 * 关键点：香山把标量的 int↔fp、fp 立即数等放在向量管道里（FuType.i2v/i2f/f2v），这里一律映射回
 * Bundles 已有的标量 FuType：fcvt（转换/搬运）、falu / fmac / fdiv（算术）。
 */
object AnsFpDecode extends AnsDecodeConstants with AnsDecodeSources {
  import AnsRiscv._
  import FuType.{falu, fcvt, fdiv, fmac, ldu, stu}

  val fpLoadSource  = integerImmediate
  val fpStoreSource = Seq(SrcType.integer, SrcType.floating, SrcType.none)

  def fpBin(
    pattern:            BitPat,
    op:                 UInt,
    fu:                 Int     = falu,
    intWen:             Boolean = false,
    needsRm:            Boolean = false,
  ): (BitPat, AnsDecodeBase)                                                         =
    entry(
      pattern,
      floatingBinary,
      fu,
      op,
      intWen            = intWen,
      fpWen             = !intWen,
      canRobCompress    = true,
      needsRoundingMode = needsRm,
    )
  def fpUn(
    pattern:            BitPat,
    op:                 UInt,
    fu:                 Int     = falu,
    intWen:             Boolean = false,
    needsRm:            Boolean = false,
  ): (BitPat, AnsDecodeBase)                                                         =
    entry(
      pattern,
      floatingUnary,
      fu,
      op,
      intWen            = intWen,
      fpWen             = !intWen,
      canRobCompress    = true,
      needsRoundingMode = needsRm,
    )
  def fpFmaEntry(pattern: BitPat, op: UInt, fu: Int = fmac): (BitPat, AnsDecodeBase) =
    entry(pattern, floatingTernary, fu, op, fpWen = true, canRobCompress = true, needsRoundingMode = true)
  def toInt(pattern: BitPat, op: UInt): (BitPat, AnsDecodeBase)                      =
    entry(pattern, floatingUnary, fcvt, op, intWen = true, canRobCompress = true, needsRoundingMode = true)
  def toFp(pattern: BitPat, op: UInt): (BitPat, AnsDecodeBase)                       =
    entry(pattern, integerUnary, fcvt, op, fpWen = true, canRobCompress = true, needsRoundingMode = true)
  // 位搬运：int→fp 或 fp→int，不涉及算术，改用 fcvt。
  def move(pattern: BitPat, op: UInt, fromInteger: Boolean): (BitPat, AnsDecodeBase) =
    entry(
      pattern,
      if (fromInteger) integerUnary else floatingUnary,
      fcvt,
      op,
      intWen         = !fromInteger,
      fpWen          = fromInteger,
      canRobCompress = true,
    )

  val decodeArray: Array[(BitPat, AnsDecodeBase)] = Array(
    // RVB23U64 mandatory F/D loads and stores.
    entry(FLW, fpLoadSource, ldu, LSUOpType.flw, ImmType.i, fpWen = true),
    entry(FLD, fpLoadSource, ldu, LSUOpType.fld, ImmType.i, fpWen = true),
    entry(FSW, fpStoreSource, stu, LSUOpType.fsw, ImmType.s),
    entry(FSD, fpStoreSource, stu, LSUOpType.fsd, ImmType.s),
    // S 精度算术/比较。
    fpBin(FADD_S, FALUOpType.faddS, needsRm                       = true),
    fpBin(FSUB_S, FALUOpType.fsubS, needsRm                       = true),
    fpBin(FMUL_S, FMACOpType.fmulS, fu                            = fmac, needsRm = true),
    fpBin(FDIV_S, FDivSqrtOpType.fdivS, fu                        = fdiv, needsRm = true),
    fpUn(FSQRT_S, FDivSqrtOpType.fsqrtS, fu                       = fdiv, needsRm = true),
    fpBin(FMIN_S, FALUOpType.fminS),
    fpBin(FMAX_S, FALUOpType.fmaxS),
    fpBin(FSGNJ_S, FALUOpType.fsgnjS),
    fpBin(FSGNJN_S, FALUOpType.fsgnjnS),
    fpBin(FSGNJX_S, FALUOpType.fsgnjxS),
    fpBin(FEQ_S, FALUOpType.feqS, intWen                          = true),
    fpBin(FLE_S, FALUOpType.fleS, intWen                          = true),
    fpBin(FLT_S, FALUOpType.fltS, intWen                          = true),
    fpUn(FCLASS_S, FALUOpType.fclassS, intWen                     = true),
    // D 精度算术/比较。
    fpBin(FADD_D, FALUOpType.faddD, needsRm                       = true),
    fpBin(FSUB_D, FALUOpType.fsubD, needsRm                       = true),
    fpBin(FMUL_D, FMACOpType.fmulD, fu                            = fmac, needsRm = true),
    fpBin(FDIV_D, FDivSqrtOpType.fdivD, fu                        = fdiv, needsRm = true),
    fpUn(FSQRT_D, FDivSqrtOpType.fsqrtD, fu                       = fdiv, needsRm = true),
    fpBin(FMIN_D, FALUOpType.fminD),
    fpBin(FMAX_D, FALUOpType.fmaxD),
    fpBin(FSGNJ_D, FALUOpType.fsgnjD),
    fpBin(FSGNJN_D, FALUOpType.fsgnjnD),
    fpBin(FSGNJX_D, FALUOpType.fsgnjxD),
    fpBin(FEQ_D, FALUOpType.feqD, intWen                          = true),
    fpBin(FLE_D, FALUOpType.fleD, intWen                          = true),
    fpBin(FLT_D, FALUOpType.fltD, intWen                          = true),
    fpUn(FCLASS_D, FALUOpType.fclassD, intWen                     = true),
    // FMA。
    fpFmaEntry(FMADD_S, FMACOpType.fmaddS),
    fpFmaEntry(FMSUB_S, FMACOpType.fmsubS),
    fpFmaEntry(FNMSUB_S, FMACOpType.fnmsubS),
    fpFmaEntry(FNMADD_S, FMACOpType.fnmaddS),
    fpFmaEntry(FMADD_D, FMACOpType.fmaddD),
    fpFmaEntry(FMSUB_D, FMACOpType.fmsubD),
    fpFmaEntry(FNMSUB_D, FMACOpType.fnmsubD),
    fpFmaEntry(FNMADD_D, FMACOpType.fnmaddD),
    // 寄存器搬运：int↔fp。
    move(FMV_X_W, FCVTOpType.fmvXW, fromInteger                   = false),
    move(FMV_W_X, FCVTOpType.fmvWX, fromInteger                   = true),
    move(FMV_X_D, FCVTOpType.fmvXD, fromInteger                   = false),
    move(FMV_D_X, FCVTOpType.fmvDX, fromInteger                   = true),
    // int↔fp 转换。
    toInt(FCVT_W_S, FCVTOpType.fcvtWS),
    toInt(FCVT_WU_S, FCVTOpType.fcvtWuS),
    toInt(FCVT_L_S, FCVTOpType.fcvtLS),
    toInt(FCVT_LU_S, FCVTOpType.fcvtLuS),
    toFp(FCVT_S_W, FCVTOpType.fcvtSW),
    toFp(FCVT_S_WU, FCVTOpType.fcvtSWu),
    toFp(FCVT_S_L, FCVTOpType.fcvtSL),
    toFp(FCVT_S_LU, FCVTOpType.fcvtSLu),
    toInt(FCVT_W_D, FCVTOpType.fcvtWD),
    toInt(FCVT_WU_D, FCVTOpType.fcvtWuD),
    toInt(FCVT_L_D, FCVTOpType.fcvtLD),
    toInt(FCVT_LU_D, FCVTOpType.fcvtLuD),
    toFp(FCVT_D_W, FCVTOpType.fcvtDW),
    toFp(FCVT_D_WU, FCVTOpType.fcvtDWu),
    toFp(FCVT_D_L, FCVTOpType.fcvtDL),
    toFp(FCVT_D_LU, FCVTOpType.fcvtDLu),
    // fp→fp 转换（S/D）。
    fpUn(FCVT_S_D, FCVTOpType.fcvtSD, fu                          = fcvt, needsRm = true),
    fpUn(FCVT_D_S, FCVTOpType.fcvtDS, fu                          = fcvt, needsRm = true),
  )
}

/* Zfa scalar instructions required by RVB23. */
object AnsZfaDecode extends AnsDecodeConstants with AnsDecodeSources {
  import AnsRiscv._
  import FuType.{falu, fcvt}

  val decodeArray: Array[(BitPat, AnsDecodeBase)] = Array(
    entry(FLI_S, noSources, falu, FALUOpType.fliS, fpWen                 = true, canRobCompress = true),
    entry(FLI_D, noSources, falu, FALUOpType.fliD, fpWen                 = true, canRobCompress = true),
    entry(FMINM_S, floatingBinary, falu, FALUOpType.fminmS, fpWen        = true, canRobCompress = true),
    entry(FMAXM_S, floatingBinary, falu, FALUOpType.fmaxmS, fpWen        = true, canRobCompress = true),
    entry(FMINM_D, floatingBinary, falu, FALUOpType.fminmD, fpWen        = true, canRobCompress = true),
    entry(FMAXM_D, floatingBinary, falu, FALUOpType.fmaxmD, fpWen        = true, canRobCompress = true),
    entry(FLEQ_S, floatingBinary, falu, FALUOpType.fleqS, intWen         = true, canRobCompress = true),
    entry(FLTQ_S, floatingBinary, falu, FALUOpType.fltqS, intWen         = true, canRobCompress = true),
    entry(FLEQ_D, floatingBinary, falu, FALUOpType.fleqD, intWen         = true, canRobCompress = true),
    entry(FLTQ_D, floatingBinary, falu, FALUOpType.fltqD, intWen         = true, canRobCompress = true),
    entry(
      FROUND_S,
      floatingUnary,
      fcvt,
      FCVTOpType.froundS,
      fpWen                                                              = true,
      canRobCompress                                                     = true,
      needsRoundingMode                                                  = true,
    ),
    entry(
      FROUND_D,
      floatingUnary,
      fcvt,
      FCVTOpType.froundD,
      fpWen                                                              = true,
      canRobCompress                                                     = true,
      needsRoundingMode                                                  = true,
    ),
    entry(
      FROUNDNX_S,
      floatingUnary,
      fcvt,
      FCVTOpType.froundnxS,
      fpWen                                                              = true,
      canRobCompress                                                     = true,
      needsRoundingMode                                                  = true,
    ),
    entry(
      FROUNDNX_D,
      floatingUnary,
      fcvt,
      FCVTOpType.froundnxD,
      fpWen                                                              = true,
      canRobCompress                                                     = true,
      needsRoundingMode                                                  = true,
    ),
    entry(FCVTMOD_W_D, floatingUnary, fcvt, FCVTOpType.fcvtmodWD, intWen = true, canRobCompress = true),
  )
}

/* 系统、排序、缓存管理、Svinval/Zawrs/Zicbop/Zimop。 */
object AnsSystemDecode extends AnsDecodeConstants with AnsDecodeSources {
  import AnsRiscv._
  import FuType.{alu, csr, fence, stu}

  val decodeArray: Array[(BitPat, AnsDecodeBase)] = Array(
    // Zicsr。立即数类型为 Z：{rd, rs1, csr} 拼接，CSR 地址位于低 12 位。
    entry(
      CSRRW,
      Seq(SrcType.integer, SrcType.immediate, SrcType.none),
      csr,
      CSROpType.csrrw,
      ImmType.z,
      intWen                                                                         = true,
      waitForward                                                                    = true,
      blockBackward                                                                  = true,
    ),
    entry(
      CSRRS,
      Seq(SrcType.integer, SrcType.immediate, SrcType.none),
      csr,
      CSROpType.csrrs,
      ImmType.z,
      intWen                                                                         = true,
      waitForward                                                                    = true,
      blockBackward                                                                  = true,
    ),
    entry(
      CSRRC,
      Seq(SrcType.integer, SrcType.immediate, SrcType.none),
      csr,
      CSROpType.csrrc,
      ImmType.z,
      intWen                                                                         = true,
      waitForward                                                                    = true,
      blockBackward                                                                  = true,
    ),
    entry(
      CSRRWI,
      Seq(SrcType.immediate, SrcType.immediate, SrcType.none),
      csr,
      CSROpType.csrrwi,
      ImmType.z,
      intWen                                                                         = true,
      waitForward                                                                    = true,
      blockBackward                                                                  = true,
    ),
    entry(
      CSRRSI,
      Seq(SrcType.immediate, SrcType.immediate, SrcType.none),
      csr,
      CSROpType.csrrsi,
      ImmType.z,
      intWen                                                                         = true,
      waitForward                                                                    = true,
      blockBackward                                                                  = true,
    ),
    entry(
      CSRRCI,
      Seq(SrcType.immediate, SrcType.immediate, SrcType.none),
      csr,
      CSROpType.csrrci,
      ImmType.z,
      intWen                                                                         = true,
      waitForward                                                                    = true,
      blockBackward                                                                  = true,
    ),
    entry(ECALL, noSources, csr, CSROpType.ecall, blockBackward                      = true, commitSelfFlush = true),
    entry(EBREAK, noSources, csr, CSROpType.ebreak, blockBackward                    = true, commitSelfFlush = true),
    entry(SRET, noSources, csr, CSROpType.sret, blockBackward                        = true, commitSelfFlush = true),
    entry(MRET, noSources, csr, CSROpType.mret, blockBackward                        = true, commitSelfFlush = true),
    entry(WFI, noSources, csr, CSROpType.wfi, blockBackward                          = true),
    entry(WRS_NTO, noSources, csr, CSROpType.wrsNto, blockBackward                   = true),
    entry(WRS_STO, noSources, csr, CSROpType.wrsSto, blockBackward                   = true),
    // 排序与缓存管理。
    entry(PAUSE, noSources, fence, FenceOpType.pause, blockBackward                  = true, commitSelfFlush = true),
    // PAUSE has the FENCE opcode shape; keep it before the generic FENCE row.
    entry(FENCE, noSources, fence, FenceOpType.fence, blockBackward                  = true, commitSelfFlush = true),
    entry(FENCE_I, noSources, fence, FenceOpType.fenceI, blockBackward               = true, commitSelfFlush = true),
    entry(SFENCE_VMA, integerBinary, fence, FenceOpType.sfenceVma, blockBackward     = true, commitSelfFlush = true),
    entry(CBO_INVAL, integerUnary, stu, LSUOpType.cboInval, ImmType.s, blockBackward = true),
    entry(CBO_CLEAN, integerUnary, stu, LSUOpType.cboClean, ImmType.s, blockBackward = true),
    entry(CBO_FLUSH, integerUnary, stu, LSUOpType.cboFlush, ImmType.s, blockBackward = true),
    entry(CBO_ZERO, integerUnary, stu, LSUOpType.cboZero, ImmType.s, blockBackward   = true),
    // Zihintntl aliases are architecturally no-ops; do not read rs2 as an operand.
    entry(NTL_P1, noSources, alu, ALUOpType.nop),
    entry(NTL_PALL, noSources, alu, ALUOpType.nop),
    entry(NTL_S1, noSources, alu, ALUOpType.nop),
    entry(NTL_ALL, noSources, alu, ALUOpType.nop),
    // Zicbop prefetch hints read the base address but do not write a register.
    entry(PREFETCH_I, integerImmediate, FuType.ldu, LSUOpType.prefetchI, ImmType.i),
    entry(PREFETCH_R, integerImmediate, FuType.ldu, LSUOpType.prefetchR, ImmType.i),
    entry(PREFETCH_W, integerImmediate, FuType.ldu, LSUOpType.prefetchW, ImmType.i),
    // Zimop。
    // Standard MOPs write zero to rd and have no architectural source dependency.
    entry(MOP_R, noSources, alu, ALUOpType.mopR, intWen                              = true, canRobCompress  = true),
    entry(MOP_RR, noSources, alu, ALUOpType.mopRr, intWen                            = true, canRobCompress  = true),
  )
}

/* 压缩指令展开器：16 位 RV64C 指令 -> 等价的 32 位指令。
 *
 * 真实流水线里展开属于前端（需要据此推进 PC），这里放在同一文件中以便自包含演示：AnsDecodeUnit
 * 内部先展开再查表。RVB23U64 必选的 Zcb/Zcmop 也在这里展开；C.JAL 是 RV32 专用，因此不实现。
 */
class AnsRvcExpander extends Module {
  val io = IO(new Bundle {
    val in      = Input(UInt(16.W))
    val out     = Output(UInt(32.W))
    val illegal = Output(Bool())
  })

  val c        = io.in
  val quadrant = c(1, 0)
  val funct3   = c(15, 13)
  val isQ0     = quadrant === "b00".U
  val isQ1     = quadrant === "b01".U
  val isQ2     = quadrant === "b10".U

  def plus8(x: UInt): UInt = (x +& 8.U)(4, 0)

  val rd       = c(11, 7)
  val rs2      = c(6, 2)
  val rdPrime  = plus8(c(4, 2))
  val rs1Prime = plus8(c(9, 7))
  val x0       = 0.U(5.W)
  val imm0     = 0.U(12.W)

  def rType(opcode: Int, funct7: Int, rs2v: UInt, rs1v: UInt, funct3v: Int, rdv: UInt): UInt =
    Cat(funct7.U(7.W), rs2v(4, 0), rs1v(4, 0), funct3v.U(3.W), rdv(4, 0), opcode.U(7.W))
  def iType(opcode: Int, funct3v: Int, rdv: UInt, rs1v: UInt, imm: UInt): UInt               =
    Cat(imm(11, 0), rs1v(4, 0), funct3v.U(3.W), rdv(4, 0), opcode.U(7.W))
  def sType(opcode: Int, funct3v: Int, rs1v: UInt, rs2v: UInt, imm: UInt): UInt              =
    Cat(imm(11, 5), rs2v(4, 0), rs1v(4, 0), funct3v.U(3.W), imm(4, 0), opcode.U(7.W))
  def bType(funct3v: Int, rs1v: UInt, rs2v: UInt, imm: UInt): UInt                           =
    Cat(imm(12), imm(10, 5), rs2v(4, 0), rs1v(4, 0), funct3v.U(3.W), imm(4, 1), imm(11), "b1100011".U(7.W))
  def uType(opcode: Int, rdv: UInt, imm20: UInt): UInt                                       =
    Cat(imm20(19, 0), rdv(4, 0), opcode.U(7.W))
  def jType(rdv: UInt, imm: UInt): UInt                                                      =
    Cat(imm(20), imm(10, 1), imm(11), imm(19, 12), rdv(4, 0), "b1101111".U(7.W))

  def se6to12(x: UInt): UInt = Cat(Fill(6, x(5)), x) // 6 位符号扩展成 12 位

  val imm6I  = Cat(Fill(6, c(12)), c(12, 7)) // C.ADDI/C.LI 等的 6 位有符号立即数
  val shamt6 = c(12, 7)

  def matches16(value: Int, mask: Int): Bool = (c & mask.U(16.W)) === value.U(16.W)

  // Zcb encodings (the constants are the ratified match/mask values).
  val isCLBU   = matches16(0x8000, 0xfc03)
  val isCLHU   = matches16(0x8400, 0xfc43)
  val isCLH    = matches16(0x8440, 0xfc43)
  val isCSB    = matches16(0x8800, 0xfc03)
  val isCSH    = matches16(0x8c00, 0xfc43)
  val isCZEXTB = matches16(0x9c61, 0xfc7f)
  val isCSEXTB = matches16(0x9c65, 0xfc7f)
  val isCZEXTH = matches16(0x9c69, 0xfc7f)
  val isCSEXTH = matches16(0x9c6d, 0xfc7f)
  val isCZEXTW = matches16(0x9c71, 0xfc7f)
  val isCNOT   = matches16(0x9c75, 0xfc7f)
  val isCMUL   = matches16(0x9c41, 0xfc63)
  val isZcb    = Seq(isCLBU, isCLHU, isCLH, isCSB, isCSH, isCZEXTB, isCSEXTB, isCZEXTH, isCSEXTH, isCZEXTW, isCNOT, isCMUL)
    .reduce(_ || _)

  // Zcmop has eight fixed encodings; the baseline architectural effect is no register write.
  val isCmop = Seq(0x6081, 0x6181, 0x6281, 0x6381, 0x6481, 0x6581, 0x6681, 0x6781)
    .map(value => matches16(value, 0xffff))
    .reduce(_ || _)

  val zcbByteImm = Cat(0.U(10.W), c(5), c(6))
  val zcbHalfImm = Cat(0.U(10.W), c(5), 0.U(1.W))

  def buildZcb: UInt = MuxCase(
    0.U(32.W),
    Seq(
      isCLBU   -> iType(0x03, 4, rdPrime, rs1Prime, zcbByteImm),
      isCLHU   -> iType(0x03, 5, rdPrime, rs1Prime, zcbHalfImm),
      isCLH    -> iType(0x03, 1, rdPrime, rs1Prime, zcbHalfImm),
      isCSB    -> sType(0x23, 0, rs1Prime, rdPrime, zcbByteImm),
      isCSH    -> sType(0x23, 1, rs1Prime, rdPrime, zcbHalfImm),
      isCZEXTB -> iType(0x13, 7, rs1Prime, rs1Prime, 0x0ff.U(12.W)),
      isCSEXTB -> iType(0x13, 1, rs1Prime, rs1Prime, 0x604.U(12.W)),
      isCZEXTH -> rType(0x3b, 0x04, 0.U(5.W), rs1Prime, 4, rs1Prime),
      isCSEXTH -> iType(0x13, 1, rs1Prime, rs1Prime, 0x605.U(12.W)),
      isCZEXTW -> rType(0x3b, 0x04, 0.U(5.W), rs1Prime, 0, rs1Prime),
      isCNOT   -> iType(0x13, 4, rs1Prime, rs1Prime, 0xfff.U(12.W)),
      isCMUL   -> rType(0x33, 0x01, rdPrime, rs1Prime, 0, rs1Prime),
    ),
  )

  def buildQ0: UInt = {
    val offLwSw = Cat(0.U(5.W), c(5), c(12, 10), c(6), 0.U(2.W))
    val offLdSd = Cat(0.U(4.W), c(6, 5), c(12, 10), 0.U(3.W))
    MuxCase(
      0.U(32.W),
      Seq(
        (funct3 === "b000".U) -> iType(
          0x13,
          0,
          rdPrime,
          2.U(5.W),
          Cat(0.U(2.W), c(10, 7), c(12, 11), c(5), c(6), 0.U(2.W)),
        ),
        (funct3 === "b001".U) -> iType(0x07, 3, rdPrime, rs1Prime, Cat(0.U(4.W), c(6, 5), c(12, 10), 0.U(3.W))),
        (funct3 === "b010".U) -> iType(0x03, 2, rdPrime, rs1Prime, offLwSw),
        (funct3 === "b011".U) -> iType(0x03, 3, rdPrime, rs1Prime, offLdSd),
        (funct3 === "b101".U) -> sType(0x27, 3, rs1Prime, rdPrime, offLdSd),
        (funct3 === "b110".U) -> sType(0x23, 2, rs1Prime, rdPrime, offLwSw),
        (funct3 === "b111".U) -> sType(0x23, 3, rs1Prime, rdPrime, offLdSd),
      ),
    )
  }

  def buildQ1: UInt = {
    val sub      = c(6, 5)
    val miscAlu  = MuxCase(
      0.U(32.W),
      Seq(
        (c(11, 10) === "b00".U)                                     -> iType(0x13, 1, rd, rd, Cat(0.U(6.W), shamt6)),
        (c(11, 10) === "b01".U)                                     -> iType(0x13, 5, rd, rd, Cat(0.U(6.W), shamt6)),
        (c(11, 10) === "b10".U)                                     -> iType(0x13, 7, rdPrime, rdPrime, se6to12(c(12, 7))),
        (c(11, 10) === "b11".U && c(12) === 0.U && sub === "b00".U) -> rType(0x33, 0x20, rs1Prime, rdPrime, 0, rdPrime),
        (c(11, 10) === "b11".U && c(12) === 0.U && sub === "b01".U) -> rType(0x33, 0x00, rs1Prime, rdPrime, 4, rdPrime),
        (c(11, 10) === "b11".U && c(12) === 0.U && sub === "b10".U) -> rType(0x33, 0x00, rs1Prime, rdPrime, 6, rdPrime),
        (c(11, 10) === "b11".U && c(12) === 0.U && sub === "b11".U) -> rType(0x33, 0x00, rs1Prime, rdPrime, 7, rdPrime),
        (c(11, 10) === "b11".U && c(12) === 1.U && sub === "b00".U) -> rType(0x3b, 0x20, rs1Prime, rdPrime, 0, rdPrime),
        (c(11, 10) === "b11".U && c(12) === 1.U && sub === "b01".U) -> rType(0x3b, 0x00, rs1Prime, rdPrime, 0, rdPrime),
      ),
    )
    val addi16sp = iType(0x13, 0, 2.U(5.W), 2.U(5.W), Cat(Fill(2, c(12)), c(12), c(4, 3), c(5), c(2), c(6), 0.U(4.W)))
    val luiImm20 = Cat(Fill(14, c(12)), c(12), c(6, 2))
    val cjImm    = Cat(c(12), c(8), c(10, 9), c(6), c(7), c(2), c(11), c(5, 3), 0.U(1.W))
    val cbImm    = Cat(Fill(4, c(12)), c(12), c(6, 5), c(2), c(11, 10), c(4, 3), 0.U(1.W))
    MuxCase(
      iType(0x13, 0, rd, rd, imm6I),
      Seq(
        (funct3 === "b001".U)               -> iType(0x1b, 0, rd, rd, imm6I),
        (funct3 === "b010".U)               -> iType(0x13, 0, rd, x0, imm6I),
        (funct3 === "b011".U && rd === 2.U) -> addi16sp,
        (funct3 === "b011".U)               -> uType(0x37, rd, luiImm20),
        (funct3 === "b100".U)               -> miscAlu,
        (funct3 === "b101".U)               -> jType(x0, Cat(Fill(9, cjImm(11)), cjImm)),
        (funct3 === "b110".U)               -> bType(0, rs1Prime, x0, cbImm),
        (funct3 === "b111".U)               -> bType(1, rs1Prime, x0, cbImm),
      ),
    )
  }

  def buildQ2: UInt = {
    val fldsp     = iType(0x07, 3, rd, 2.U(5.W), Cat(0.U(3.W), c(4, 2), c(12), c(6, 5), 0.U(3.W)))
    val lwsp      = iType(0x03, 2, rd, 2.U(5.W), Cat(0.U(4.W), c(3, 2), c(12), c(6, 4), 0.U(2.W)))
    val ldsp      = iType(0x03, 3, rd, 2.U(5.W), Cat(0.U(3.W), c(4, 2), c(12), c(6, 5), 0.U(3.W)))
    val fsdsp     = sType(0x27, 3, 2.U(5.W), rs2, Cat(0.U(3.W), c(9, 7), c(12, 10), 0.U(3.W)))
    val swsp      = sType(0x23, 2, 2.U(5.W), rs2, Cat(0.U(4.W), c(8, 7), c(12, 9), 0.U(2.W)))
    val sdsp      = sType(0x23, 3, 2.U(5.W), rs2, Cat(0.U(3.W), c(9, 7), c(12, 10), 0.U(3.W)))
    val jrMv      = MuxCase(
      rType(0x33, 0x00, rs2, x0, 0, rd),
      Seq(
        (rs2 === 0.U) -> iType(0x67, 0, x0, rd, imm0)
      ),
    )
    val ebreakAdd = MuxCase(
      rType(0x33, 0x00, rs2, rd, 0, rd),
      Seq(
        ((rs2 === 0.U) && (rd === 0.U)) -> "b00000000000100000000000001110011".U(32.W),
        ((rs2 === 0.U) && (rd =/= 0.U)) -> iType(0x67, 0, 1.U(5.W), rd, imm0),
      ),
    )
    MuxCase(
      iType(0x13, 1, rd, rd, Cat(0.U(6.W), shamt6)),
      Seq(
        (funct3 === "b001".U)                  -> fldsp,
        (funct3 === "b010".U)                  -> lwsp,
        (funct3 === "b011".U)                  -> ldsp,
        (funct3 === "b100".U && c(12) === 0.U) -> jrMv,
        (funct3 === "b100".U)                  -> ebreakAdd,
        (funct3 === "b101".U)                  -> fsdsp,
        (funct3 === "b110".U)                  -> swsp,
        (funct3 === "b111".U)                  -> sdsp,
      ),
    )
  }

  // 保留/Zcb 编码：Q0 funct3=100；Q1 中 C.ADDIW rd=0 / C.LUI rd=0；Q2 中 C.LWSP/LDSP rd=0、C.JR rs1=0。
  // Q0 funct3=000 为 C.ADDI4SPN，nzuimm 全 0 是保留编码。
  val q0ImmZero       = (c(12, 11) === 0.U) && (c(10, 7) === 0.U) && (c(6) === 0.U) && (c(5) === 0.U)
  val addi16spImmZero = (rd === 2.U) && (c(12) === 0.U) && (c(6, 2) === 0.U)
  val illegalQ1       = isQ1 && (
    ((funct3 === "b001".U) && (rd === 0.U)) ||
      ((funct3 === "b011".U) && ((rd === 0.U) || addi16spImmZero))
  )
  val illegalQ2       = isQ2 && (((funct3 === "b010".U) && (rd === 0.U)) ||
    ((funct3 === "b011".U) && (rd === 0.U)) ||
    ((funct3 === "b100".U) && (c(12) === 0.U) && (rs2 === 0.U) && (rd === 0.U)))

  // Zcb occupies the otherwise-reserved funct3=100 spaces in Q0/Q1.
  val correctedIllegalQ0 = isQ0 && (((funct3 === "b100".U) && !isZcb) || ((funct3 === "b000".U) && q0ImmZero))
  val cmopExpansion      = iType(0x13, 0, 0.U(5.W), 0.U(5.W), 0.U(12.W))

  io.out     := MuxCase(
    0.U(32.W),
    Seq(isZcb -> buildZcb, isCmop -> cmopExpansion, isQ0 -> buildQ0, isQ1 -> buildQ1, isQ2 -> buildQ2),
  )
  io.illegal := !(isQ0 || isQ1 || isQ2) || correctedIllegalQ0 || illegalQ1 || illegalQ2
}

/* 译码单元 IO。 */
class AnsDecodeUnitEnqIO(val params: BundleParams = BundleParams()) extends Bundle {
  // 前端给出的是可能尚未展开的取指指令：低 16 位在压缩时即有效指令。
  val ctrlFlow         = Input(new StaticInst(params))
  // CSR supplies stable state predicates; address/privilege checks stay in CSR execution.
  val fromCsr          = Input(new AnsCsrDecodeState)
}

class AnsDecodeUnitDeqIO(val params: BundleParams = BundleParams()) extends Bundle {
  val decodedInst = Output(new DecodedInst(params))
}

class AnsDecodeUnitIO(val params: BundleParams = BundleParams()) extends Bundle {
  val enq = new AnsDecodeUnitEnqIO(params)
  val deq = new AnsDecodeUnitDeqIO(params)
}

/* 精简标量 RVB23 译码单元。
 *
 * 结构与香山 DecodeUnit 对应：拼表 -> 查表 -> 派生控制位；但去掉了向量、多微操作、虚拟化与调试。
 */
class AnsDecodeUnit(val params: BundleParams = BundleParams()) extends Module {
  require(params.xlen == 64, "RVB23 is defined only for RV64")
  require(params.instructionWidth == 32, "the scalar decoder consumes 32-bit instructions")
  require(params.logicalRegWidth == 5, "RISC-V register fields are five bits wide")
  require(params.numSrc >= 3, "the scalar decoder exposes three source slots")

  val io = IO(new AnsDecodeUnitIO(params))

  val ctrlFlow     = io.enq.ctrlFlow
  val isCompressed = ctrlFlow.instr(1, 0) =/= "b11".U

  // 压缩指令先展开；非压缩指令直接使用原始 32 位。
  val rvc = Module(new AnsRvcExpander)
  rvc.io.in := ctrlFlow.instr(15, 0)
  val instruction = Mux(isCompressed, rvc.io.out, ctrlFlow.instr)

  val decodeTable: Array[(BitPat, AnsDecodeBase)] =
    // Zicbop uses an ORI-shaped encoding, so its specific rows must precede
    // the generic OP-IMM rows in AnsIntDecode.
    AnsSystemDecode.decodeArray ++
      AnsIntDecode.decodeArray ++
      AnsFpDecode.decodeArray ++
      AnsBitmanipDecode.decodeArray ++
      AnsZfaDecode.decodeArray

  val table: Array[(BitPat, List[BitPat])] =
    decodeTable.map { case (pat, row) => (pat, row.generate()) }
  val default                              = AnsIntDecode.decodeDefault
  val columnWidths: Seq[Int]               = Seq(
    SrcType.width,
    SrcType.width,
    SrcType.width,
    FuType.width,
    FuOpType.width,
    1,
    1,
    1,
    1,
    1,
    1,
    1,
    1,
    ImmType.width,
  )
  require(default.length == columnWidths.length, "decode default column count mismatch")
  require(table.forall(_._2.length == default.length), "decode table column count mismatch")
  require(table.map(_._1).distinct.length == table.length, "decode table contains duplicate patterns")

  /* 列式查表：逐列做优先级 mux（rocket DecodeLogic 的等价语义，避免引入 rocket-chip 依赖）。 */
  def decodeColumns(addr: UInt): List[UInt] = {
    val matches = table.map { case (pat, _) => pat === addr }
    columnWidths.zipWithIndex.map { case (width, col) =>
      val defaultCell = default(col)
      MuxCase(
        defaultCell.value.U(width.W),
        table.indices.map(row => matches(row) -> table(row)._2(col).value.U(width.W)),
      )
    }.toList
  }

  val cols              = decodeColumns(instruction)
  val fuType            = cols(3)
  val fuOpType          = cols(4)
  val intWen            = cols(5)(0)
  val fpWen             = cols(6)(0)
  val rowIllegal        = cols(7)(0)
  val blockBackward     = cols(8)(0)
  val commitSelfFlush   = cols(9)(0)
  val canRobCompress    = cols(10)(0)
  val waitForward       = cols(11)(0)
  val needsRoundingMode = cols(12)(0)
  val immType           = cols(13)

  def signExtend(value: UInt, valueWidth: Int): UInt = {
    require(params.xlen >= valueWidth, "immediate is wider than XLEN")
    if (params.xlen == valueWidth) value else Cat(Fill(params.xlen - valueWidth, value(valueWidth - 1)), value)
  }

  def zeroExtend(value: UInt, valueWidth: Int): UInt = {
    require(params.xlen >= valueWidth, "immediate is wider than XLEN")
    if (params.xlen == valueWidth) value else Cat(0.U((params.xlen - valueWidth).W), value)
  }

  // 立即数：I/S/B/U/J 沿用标准抽取；Z 在 Decode 中表示 CSR 地址。
  // CSR zimm 保留在 logicalSrc(0)/原始 instr 中，不增加重复的流水字段。
  val csrAddrValue = instruction(31, 20)
  val imm          = MuxLookup(immType, 0.U(params.xlen.W))(
    Seq(
      ImmType.i.U(ImmType.width.W) -> signExtend(instruction(31, 20), 12),
      ImmType.s.U(ImmType.width.W) -> signExtend(Cat(instruction(31, 25), instruction(11, 7)), 12),
      ImmType.b.U(ImmType.width.W) -> signExtend(
        Cat(instruction(31), instruction(7), instruction(30, 25), instruction(11, 8), 0.U(1.W)),
        13,
      ),
      ImmType.u.U(ImmType.width.W) -> signExtend(Cat(instruction(31, 12), 0.U(12.W)), 32),
      ImmType.j.U(ImmType.width.W) -> signExtend(
        Cat(instruction(31), instruction(19, 12), instruction(20), instruction(30, 21), 0.U(1.W)),
        21,
      ),
      ImmType.z.U(ImmType.width.W) -> zeroExtend(csrAddrValue, 12),
    )
  )

  val decoded = Wire(new DecodedInst(params))
  decoded := 0.U.asTypeOf(new DecodedInst(params))

  // 身份信息即使非法也要透传。
  decoded.instr              := ctrlFlow.instr
  decoded.pc                 := ctrlFlow.pc
  decoded.predictedTaken     := ctrlFlow.predictedTaken
  decoded.isLastInFetchBlock := ctrlFlow.isLastInFetchBlock
  decoded.sequence           := ctrlFlow.sequence
  decoded.fetchSlot          := ctrlFlow.fetchSlot

  decoded.fuType            := fuType
  decoded.fuOpType          := fuOpType
  // x0 is architecturally immutable. Suppress the rename/writeback request for
  // alu instructions targeting x0 (including Zimop's defined write-zero result).
  decoded.intWen            := intWen && (instruction(11, 7) =/= 0.U)
  decoded.fpWen             := fpWen
  decoded.waitForward       := waitForward
  decoded.blockBackward     := blockBackward
  decoded.commitSelfFlush   := commitSelfFlush
  decoded.canRobCompress    := canRobCompress
  decoded.needsRoundingMode := needsRoundingMode
  decoded.rm                := instruction(14, 12)
  decoded.immType           := immType
  decoded.imm               := imm

  // FLI encodes a five-bit floating-point constant index in rs1. It is not a
  // register source, but the index must still reach the floating-point unit.
  val isFli = (AnsRiscv.FLI_S === instruction) || (AnsRiscv.FLI_D === instruction)
  when(isFli) {
    decoded.imm := zeroExtend(instruction(19, 15), 5)
  }

  // commitType 由 fuType 派生（不占表列）。
  decoded.commitType := MuxCase(
    CommitType.normal.U,
    Seq(
      (fuType === FuType.ldu.U)   -> CommitType.load.U,
      (fuType === FuType.stu.U)   -> CommitType.store.U,
      (fuType === FuType.mou.U)   -> CommitType.atomic.U,
      (fuType === FuType.fence.U) -> CommitType.fence.U,
      (fuType === FuType.jmp.U)   -> CommitType.redirect.U,
      (fuType === FuType.brh.U)   -> CommitType.redirect.U,
    ),
  )

  // 逻辑寄存器号来自展开后的 32 位指令。
  decoded.logicalSrc(0) := instruction(19, 15)
  decoded.logicalSrc(1) := instruction(24, 20)
  decoded.logicalSrc(2) := instruction(31, 27)
  for (slot <- 3 until params.numSrc) {
    decoded.logicalSrc(slot) := 0.U
  }
  decoded.logicalDest   := instruction(11, 7)
  for (slot <- 0 until params.numSrc) {
    decoded.srcType(slot) := cols(slot)
  }

  val usesFloatingState = fpWen || cols.take(3).map(SrcType.isFloating).reduce(_ || _)
  val reservedRm        = instruction(14, 12) === 5.U || instruction(14, 12) === 6.U
  val illegalRm         = needsRoundingMode && (
    reservedRm || (instruction(14, 12) === 7.U && io.enq.fromCsr.frmIllegal)
  )
  val csrStateIllegal   =
    (usesFloatingState && io.enq.fromCsr.fsOff) ||
      illegalRm ||
      (fuType === FuType.csr.U && fuOpType === CSROpType.wfi && io.enq.fromCsr.wfiIllegal) ||
      (fuType === FuType.csr.U && fuOpType === CSROpType.wrsNto && io.enq.fromCsr.wrsNtoIllegal) ||
      (fuType === FuType.csr.U && fuOpType === CSROpType.sret && io.enq.fromCsr.sretIllegal) ||
      (fuType === FuType.csr.U && fuOpType === CSROpType.mret && io.enq.fromCsr.mretIllegal) ||
      (fuType === FuType.fence.U && fuOpType === FenceOpType.sfenceVma && io.enq.fromCsr.sfenceVmaIllegal) ||
      ((AnsRiscv.CBO_ZERO === instruction) && io.enq.fromCsr.cboZeroIllegal) ||
      (((AnsRiscv.CBO_CLEAN === instruction) || (AnsRiscv.CBO_FLUSH === instruction)) &&
        io.enq.fromCsr.cboCleanFlushIllegal) ||
      ((AnsRiscv.CBO_INVAL === instruction) && io.enq.fromCsr.cboInvalIllegal)

  // 非法来源：表格未命中、压缩展开失败，或 CSR/特权状态不允许执行。
  val illegal        = rowIllegal || (isCompressed && rvc.io.illegal) || csrStateIllegal
  val illegalMask    = (BigInt(1) << AnsDecodeExceptionBits.illegalInstruction).U(params.exceptionBits.W)
  val fetchFaultMask = if (params.exceptionBits > AnsDecodeExceptionBits.fetchFault) {
    Mux(
      ctrlFlow.fetchFault,
      (BigInt(1) << AnsDecodeExceptionBits.fetchFault).U(params.exceptionBits.W),
      0.U(params.exceptionBits.W),
    )
  } else {
    0.U(params.exceptionBits.W)
  }
  decoded.exceptionBits := ctrlFlow.exceptionBits | Mux(
    illegal,
    illegalMask,
    0.U(params.exceptionBits.W),
  ) | fetchFaultMask
  decoded.isMove        := (BitPat("b000000000000?????000?????0010011") === instruction) &&
    (instruction(11, 7) =/= 0.U) && !decoded.hasException

  io.deq.decodedInst      := decoded
}
