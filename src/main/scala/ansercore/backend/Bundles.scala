package ansercore.backend

import chisel3._
import chisel3.util._

/** Bundles shared by the scalar backend pipeline.
  *
  * The definitions in this file deliberately depend only on Chisel and the local parameter records below. They describe
  * pipeline contracts rather than the implementation of any particular processor.
  */
object Bundles {
  private def widthFor(size: Int): Int = math.max(1, log2Ceil(size))

  /** Fetch-side exception bitmap carried from the frontend to decode.
    *
    * 只包含前端能够观测到的取指异常；译码器在 `AnsDecodeExceptionBits` 中把它们映射到统一的异常位图。
    */
  object FetchExceptionBits {
    val misaligned  = 0
    val accessFault = 1
    val pageFault   = 2
    val width       = 3
  }

  /** Width and capacity choices used by the bundles.
    *
    * 这些参数决定 bundle 的硬件形状，而不仅仅是软件侧的默认值。 These parameters define hardware widths and capacities, not software defaults.
    *
    * Keeping them in one record prevents a producer and a consumer from silently choosing different widths for the same
    * field. 将参数集中管理，可以避免接口两端为同一字段使用不一致的位宽。
    */
  final case class BundleParams(
    /** Integer datapath width / 整数数据通路位宽. */
    xlen:                Int = 64,
    /** Virtual or physical PC width carried through the backend / 后端传递的 PC 位宽. */
    pcWidth:             Int = 64,
    /** Encoded instruction width / 指令编码位宽. */
    instructionWidth:    Int = 32,
    /** Number of register-like source operands / 类寄存器源操作数数量. */
    numSrc:              Int = 3,
    /** Integer source slots per lane; scalar int instructions have at most 2. */
    numIntSrc:           Int = 2,
    /** FP source slots per lane; only FP FMA needs 3 (numSrc defaults to 3). */
    numFpSrc:            Int = 3,
    /** Instructions grouped in one frontend block / 一个前端 block 中的指令数量. */
    fetchWidth:          Int = 4,
    /** Fetch slots per FTQ entry; default matches 4 lanes with two halfword slots per lane. */
    predictWidth:        Int = 8,
    /** Number of entries in the fetch target queue / 取指目标队列深度. */
    ftqEntries:          Int = 64,
    /** Logical register index width / 逻辑寄存器编号位宽. */
    logicalRegWidth:     Int = 5,
    /** Physical register index width after rename / 重命名后物理寄存器编号位宽. */
    physicalRegWidth:    Int = 8,
    /** ROB sequence index width / 重排序缓冲区序号位宽. */
    robIndexWidth:       Int = 8,
    /** Issue-queue and memory-queue index width / 发射队列和访存队列索引位宽. */
    queueIndexWidth:     Int = 8,
    /** Number of independently represented exception bits / 异常位图中的异常数量. */
    exceptionBits:       Int = 16,
    /** Rename width used to encode instruction size metadata / 重命名宽度对应的指令尺寸编码. */
    renameWidth:         Int = 4,
    /** Number of load-pipeline dependency slots / 每个源操作数记录的前序 load 依赖槽数量. */
    loadPipelineWidth:   Int = 2,
    /** Bits per load dependency slot / 单个 load 依赖槽的编码位宽. */
    loadDependencyWidth: Int = 2,
    /** Maximum execution-unit latency represented by busy tables / busy 表可表示的最大执行延迟. */
    maxLatency:          Int = 16,
    /** Maximum execution units addressable by a source code. 源选择编码可寻址的最大执行单元数。 */
    maxExuCount:         Int = 16,
    /** Maximum number of replicated wakeup destinations / 唤醒复制目的端的最大数量. */
    maxWakeupCopies:     Int = 2,
    /** Integer RegCache entries / 整数寄存器缓存条目数. */
    intRegCacheEntries:  Int = 16,
    /** Memory RegCache entries / 访存寄存器缓存条目数. */
    memRegCacheEntries:  Int = 12,
    /** IQ 唤醒广播是否在 Backend 合流后打一拍.
      *
      * true（当前默认）：切断 "IQ 选择 -> 唤醒队列 -> 全后端合流 -> 所有 IQ 的源状态比较"
      * 这条组合路径，代价是 0-lat 依赖链多一拍。
      *
      * false（预留）：香山式组合广播。启用前必须同步把 `AnsEntry` 的唤醒命中数据源从
      * bypass2 改为 bypass1，并补齐 0-lat 取消回滚；否则会读到错误的旁路周期。
      * 详见 markdown/IssueQueue 设计文档 §5.5。
      */
    registerWakeupBroadcast: Boolean = true,
  ) {
    require(xlen > 0, "xlen must be positive")
    require(pcWidth > 0, "pcWidth must be positive")
    require(instructionWidth > 0, "instructionWidth must be positive")
    require(numSrc > 0, "numSrc must be positive")
    require(numIntSrc > 0 && numIntSrc <= numSrc, "numIntSrc must be in (0, numSrc]")
    require(numFpSrc > 0 && numFpSrc <= numSrc, "numFpSrc must be in (0, numSrc]")
    require(fetchWidth > 0, "fetchWidth must be positive")
    require(predictWidth >= fetchWidth, "predictWidth must cover at least one slot per fetch lane")
    require(ftqEntries > 1, "ftqEntries must be greater than one")
    require(logicalRegWidth > 0 && physicalRegWidth > 0, "register widths must be positive")
    require(robIndexWidth > 0 && queueIndexWidth > 0, "queue widths must be positive")
    require(exceptionBits > 0, "exception width must be positive")
    require(renameWidth > 0, "rename capacity must be positive")
    require(
      loadPipelineWidth > 0 && loadDependencyWidth >= 2,
      "load dependency dimensions must provide two reason bits",
    )
    require(maxLatency > 0, "maximum latency must be positive")
    require(maxExuCount > 0 && maxWakeupCopies >= 0, "execution-unit parameters are invalid")
    require(intRegCacheEntries > 0 && memRegCacheEntries > 0, "RegCache sizes must be positive")

    /** Width of the FTQ pointer value field. */
    def ftqPtrWidth: Int = widthFor(ftqEntries)

    /** Width of the FTQ entry offset field. */
    def ftqOffsetWidth: Int = widthFor(predictWidth)

    /** Total RegCache entries / 寄存器缓存总条目数. */
    def regCacheEntries: Int = intRegCacheEntries + memRegCacheEntries

    /** Global RegCache index width / 寄存器缓存全局索引位宽. */
    def regCacheIndexWidth: Int = widthFor(regCacheEntries)
  }

  /** Circular pointer into the fetch target queue.
    *
    * `flag` distinguishes the two halves of the circular queue, so age comparisons remain correct across wrap-around.
    * 与香山一致：`flag` 用于区分环形队列的两个半区，跨回绕比较不能退化成裸 `UInt >`。
    */
  class FtqPtr(val params: BundleParams = BundleParams()) extends Bundle {
    val flag  = Bool()
    val value = UInt(params.ftqPtrWidth.W)

    def ===(that: FtqPtr): Bool = this.asUInt === that.asUInt

    def =/=(that: FtqPtr): Bool = this.asUInt =/= that.asUInt

    def >(that: FtqPtr): Bool = (flag ^ that.flag) ^ (value > that.value)

    def <(that: FtqPtr): Bool = (flag ^ that.flag) ^ (value < that.value)

    def isAfter(that: FtqPtr): Bool = this > that

    def isBefore(that: FtqPtr): Bool = this < that
  }

  object FtqPtr {
    def apply(
      flag:   Bool,
      value:  UInt,
      params: BundleParams = BundleParams(),
    ): FtqPtr = {
      val ptr = Wire(new FtqPtr(params))
      ptr.flag  := flag
      ptr.value := value
      ptr
    }
  }

  /** Static properties of one execution unit.
    *
    * `numRegSrc` and `wbPathNum` change the dimensions of several nested fields. They must therefore be passed
    * identically to every bundle at the boundary of that execution unit.
    *
    * `numRegSrc` 和 `wbPathNum` 会改变多个嵌套字段的维度，必须在执行单元 边界两侧使用完全相同的配置。
    */
  final case class ExecutionUnitParams(
    /** Stable execution-unit identifier / 执行单元的稳定编号. */
    exuIdx:         Int     = 0,
    /** Number of source values read before execution / 执行前读取的源操作数数量. */
    numRegSrc:      Int     = 3,
    /** Number of data paths that can write back / 可写回的数据通路数量. */
    wbPathNum:      Int     = 1,
    /** Maximum source width accepted by the unit / 执行单元接受的最大源数据位宽. */
    srcDataWidth:   Int     = 64,
    /** Maximum result width produced by the unit / 执行单元产生的最大结果位宽. */
    destDataWidth:  Int     = 64,
    /** Unit has an integer-register destination / 是否写整数寄存器. */
    writesInteger:  Boolean = true,
    /** Unit has a floating-point destination / 是否写浮点寄存器. */
    writesFloating: Boolean = false,
    /** Unit can resolve a control-flow instruction / 是否能解析控制流指令. */
    hasBranch:      Boolean = false,
    /** Unit can execute a load / 是否能执行 load. */
    hasLoad:        Boolean = false,
    /** Unit can execute a store / 是否能执行 store. */
    hasStore:       Boolean = false,
    /** Unit can emit a redirect / 是否能产生重定向. */
    hasRedirect:    Boolean = false,
    /** Certain integer latency used by scheduling / 调度使用的确定整数延迟. */
    intLatency:     Int     = 1,
    /** Certain floating-point latency used by scheduling / 调度使用的确定浮点延迟. */
    fpLatency:      Int     = 3,
  ) {
    require(exuIdx >= 0, "exuIdx must be non-negative")
    require(numRegSrc > 0 && wbPathNum > 0, "execution-unit dimensions must be positive")
    require(srcDataWidth > 0 && destDataWidth > 0, "execution-unit data widths must be positive")
    require(intLatency > 0 && fpLatency > 0, "execution-unit latencies must be positive")
  }

  /** Issue-queue properties that affect one-hot and wakeup fields.
    *
    * `wakeupSourceExuIdx` uses local positions in the encoded source field; it is not a global one-hot hardware field.
    * The order must be stable across source encoding and decoding.
    *
    * `wakeupSourceExuIdx` 使用源选择字段中的局部位置，而不是全局硬件位图； 编码和解码两侧必须保持相同的顺序。
    */
  final case class IssueQueueParams(
    /** Number of entries represented by the address one-hot / 队列条目数量. */
    numEntries:         Int      = 16,
    /** Execution units that can wake this queue / 可以唤醒该队列的执行单元编号. */
    wakeupSourceExuIdx: Seq[Int] = Seq.empty,
  ) {
    require(numEntries > 0, "numEntries must be positive")
    require(wakeupSourceExuIdx.forall(_ >= 0), "execution-unit indices must be non-negative")
  }

  object SrcType {
    val width     = 2
    val integer   = 0
    val floating  = 1
    val immediate = 2
    val none      = 3

    def apply(): UInt = UInt(width.W)

    def isInteger(source: UInt): Bool = source === integer.U

    def isFloating(source: UInt): Bool = source === floating.U

    def isRegister(source: UInt): Bool = isInteger(source) || isFloating(source)
  }

  object SourceState {
    val width       = 2
    val ready       = 0
    val waiting     = 1
    val unavailable = 2

    def apply(): UInt = UInt(width.W)

    def isReady(state: UInt): Bool = state === ready.U
  }

  /* Where an operand comes from at dequeue time / 出队时操作数的来源.
   * Bit 3 marks a "registered" source (PRF or RegCache) that needs a physical
   * read port; the remaining bits select the datum. The encoding follows the
   * scalar backend reference so the IQ and DataPath agree on decay order.
   */
  object DataSource {
    val width        = 4
    val zero         = 0
    /** 组合 forward：直接使用 EXU 当拍输出。
      * 当前 IQ 唤醒广播打一拍，DataPath 的 forward 通路恒为 0（见 AnsDataPath 的 fwd），
      * 该编码只为将来 0-lat 同拍发射预留，与香山保持一致。 */
    val forward      = 1
    val bypass       = 2
    val bypass2      = 3
    val immediate    = 4
    val regCache     = 6
    val registerFile = 8

    /** Legacy names kept for the decode-side helper below. */
    val constant = zero

    def apply(): UInt = UInt(width.W)

    def readReg(source:      UInt): Bool = source(3)
    def readRegOH(source:    UInt): Bool = source === registerFile.U
    def readRegCache(source: UInt): Bool = source === regCache.U
    def readForward(source:  UInt): Bool = source === forward.U
    def readBypass(source:   UInt): Bool = source === bypass.U
    def readBypass2(source:  UInt): Bool = source === bypass2.U
    def readImm(source:      UInt): Bool = source === immediate.U
    def readZero(source:     UInt): Bool = source === zero.U
  }

  /* Functional-unit class of an instruction.
   * 指令所属的功能单元类别。
   *
   * The encoding follows XiangShan's scalar `FuType`: every real class owns
   * one bit.  Zero is reserved for the decode-table default.  This makes class
   * predicates short, synthesizable OR-reductions and keeps the operation
   * identity as the pair `(fuType, fuOpType)` rather than the op field alone.
   * 编码沿用香山标量 FuType 的一热形式；0 保留给译码表默认项。这样类别判断
   * 可以直接写成一热 OR，完整运算由 `(fuType, fuOpType)` 唯一确定。
   */
  object FuType {
    private def oneHot(bit: Int): Int = 1 << bit

    // Integer / control.  Names intentionally stay short and functional-unit oriented.
    val jmp   = oneHot(0)
    val brh   = oneHot(1)
    val csr   = oneHot(2)
    val alu   = oneHot(3)
    val mul   = oneHot(4)
    val div   = oneHot(5)
    val fence = oneHot(6)
    val bku   = oneHot(7)

    // Floating point.
    val falu = oneHot(8)
    val fmac = oneHot(9)
    val fcvt = oneHot(10)
    val fdiv = oneHot(11)

    // Load / store / atomic.  ldu/stu/mou match the XiangShan abbreviations.
    val ldu = oneHot(12)
    val stu = oneHot(13)
    val mou = oneHot(14)

    /** Zero is the invalid/default table row, not a functional unit. */
    val other       = 0
    val memberCount = 15
    val width       = memberCount

    def apply(): UInt = UInt(width.W)

    private def isAny(fuType: UInt, values: Int*): Bool =
      values.map(value => fuType(Integer.numberOfTrailingZeros(value))).foldLeft(false.B)(_ || _)

    def isMemory(fuType: UInt): Bool = isAny(fuType, ldu, stu, mou)

    def isLoadStore(fuType: UInt): Bool = isAny(fuType, ldu, stu)

    def isLoad(fuType: UInt): Bool = isAny(fuType, ldu)

    def isStore(fuType: UInt): Bool = isAny(fuType, stu)

    def isAtomic(fuType: UInt): Bool = isAny(fuType, mou)

    def isAMO(fuType: UInt): Bool = isAtomic(fuType)

    // Control flow covers both jumps and conditional branches.
    def isControlFlow(fuType: UInt): Bool = isAny(fuType, jmp, brh)

    def isJump(fuType: UInt): Bool = isAny(fuType, jmp)

    def isBrh(fuType: UInt): Bool = isAny(fuType, brh)

    def isFence(fuType: UInt): Bool = isAny(fuType, fence)

    def isCsr(fuType: UInt): Bool = isAny(fuType, csr)

    def isAlu(fuType: UInt): Bool = isAny(fuType, alu)

    def isBku(fuType: UInt): Bool = isAny(fuType, bku)

    def isFArith(fuType: UInt): Bool = isAny(fuType, falu, fmac, fcvt, fdiv)

    def isDivSqrt(fuType: UInt): Bool = isAny(fuType, div, fdiv)
  }

  /* Shared micro-op field. 具体操作按功能单元分组管理。
   *
   * The numeric values intentionally restart in every group, like XiangShan's
   * package-level operation objects.  A consumer must always match both fields:
   * `fuType === FuType.alu && fuOpType === ALUOpType.add`, never `fuOpType`
   * alone.  Nine bits leave room for encoded subfields used by future RVB23
   * execution units while keeping the bundle width stable.
   */
  object FuOpType {
    val width = 9

    def apply(): UInt = UInt(width.W)

    def X: BitPat     = BitPat("b1_1111_1111")
    def invalid: UInt = "b1_1111_1111".U

    /** Match the complete operation identity, never the group-local opcode alone. */
    def is(fuType: UInt, op: UInt, expectedFuType: Int, expectedOp: UInt): Bool =
      (fuType === expectedFuType.U(FuType.width.W)) && (op === expectedOp)
  }

  /* Integer ALU, shift and upper-immediate operations. 整数 ALU、移位与高位立即数操作。 */
  object ALUOpType {
    // [6:4] selects the operation family; low bits select the operation.
    def sll = "b000_0001".U
    def srl = "b000_0101".U
    def sra = "b000_0111".U

    def addw = "b001_0000".U
    def subw = "b001_0010".U
    def sllw = "b001_1000".U
    def srlw = "b001_1001".U
    def sraw = "b001_1010".U

    def add  = "b010_0001".U
    def sub  = "b011_0000".U
    def sltu = "b011_0001".U
    def slt  = "b011_0010".U

    def and = "b100_0000".U
    def or  = "b100_0010".U
    def xor = "b100_0100".U

    // Register/immediate forms execute the same operation; SrcType selects operand 2.
    def slli  = sll
    def srli  = srl
    def srai  = sra
    def slliw = sllw
    def srliw = srlw
    def sraiw = sraw

    def lui   = "b110_0000".U
    def auipc = "b110_0010".U
    def nop   = "b111_0000".U
    def mopR  = "b111_0001".U
    def mopRr = "b111_0010".U

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Integer multiply/divide operations share one encoded subspace.
   * 整数乘除法共享一个子编码空间，FuType 再区分 mul 与 div。
   *
   * Bit 4 separates divide from multiply, while the low bits retain the
   * operation shape.  This mirrors XiangShan's MDUOpType and lets one MDU
   * inspect the op without inventing a second global opcode namespace.
   */
  object MDUOpType {
    // [4] divide, [2] word operation; divide [1] unsigned, [0] remainder.
    def mul    = "b00000".U
    def mulh   = "b00001".U
    def mulhsu = "b00010".U
    def mulhu  = "b00011".U
    def mulw   = "b00100".U

    def div   = "b10000".U
    def rem   = "b10001".U
    def divu  = "b10010".U
    def remu  = "b10011".U
    def divw  = "b10100".U
    def remw  = "b10101".U
    def divuw = "b10110".U
    def remuw = "b10111".U

    def apply(): UInt = UInt(FuOpType.width.W)

    def isMul(op:       UInt): Bool = !op(4)
    def isDiv(op:       UInt): Bool = op(4)
    def isSignedDiv(op: UInt): Bool = isDiv(op) && !op(1)
    def isWord(op:      UInt): Bool = op(2)
  }

  /** Compatibility views used by decode tables; the canonical group is MDUOpType. */
  object MULOpType {
    def mul    = MDUOpType.mul
    def mulh   = MDUOpType.mulh
    def mulhsu = MDUOpType.mulhsu
    def mulhu  = MDUOpType.mulhu
    def mulw   = MDUOpType.mulw

    def apply(): UInt = MDUOpType()
  }

  object DIVOpType {
    def div   = MDUOpType.div
    def divu  = MDUOpType.divu
    def rem   = MDUOpType.rem
    def remu  = MDUOpType.remu
    def divw  = MDUOpType.divw
    def divuw = MDUOpType.divuw
    def remw  = MDUOpType.remw
    def remuw = MDUOpType.remuw

    def apply(): UInt = MDUOpType()
  }

  /* Unconditional jumps. 无条件跳转。 */
  object JumpOpType {
    def jal  = "b00".U
    def jalr = "b01".U

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Conditional branches. 条件分支。 */
  object BRUOpType {
    // [3:1] comparison kind; [0] inverts the branch condition.
    def beq  = "b000_000".U
    def bne  = "b000_001".U
    def blt  = "b000_100".U
    def bge  = "b000_101".U
    def bltu = "b001_000".U
    def bgeu = "b001_001".U

    def isInvert(op: UInt): Bool = op(0)
    def cmpType(op:  UInt): UInt = op(3, 1)

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* CSR, privileged and Zimop operations. CSR、特权与 Zimop 操作。 */
  object CSROpType {
    // CSR accesses preserve funct3 in [2:0]; [2] therefore means immediate.
    def csrrw  = "b001_001".U
    def csrrs  = "b001_010".U
    def csrrc  = "b001_011".U
    def csrrwi = "b001_101".U
    def csrrsi = "b001_110".U
    def csrrci = "b001_111".U

    def ecall  = "b100_000".U
    def ebreak = "b100_001".U
    def wrsNto = "b100_010".U
    def wrsSto = "b100_011".U
    def sret   = "b100_100".U
    def mret   = "b100_101".U
    def wfi    = "b100_110".U

    def isCsrAccess(op: UInt): Bool = op(3)
    def needImm(op:     UInt): Bool = op(2)
    def csrCmd(op:      UInt): UInt = op(1, 0)

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Fence and TLB operations. 屏障与 TLB 操作。 */
  object FenceOpType {
    def fence     = "b10000".U
    def sfenceVma = "b10001".U
    def fenceI    = "b10010".U
    def pause     = "b10100".U

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Loads, stores, atomics, cache-management and prefetch operations.
   * 访存、原子、缓存管理与预取操作。
   *
   * Each FuType-owned subgroup starts at zero.  For example, `lh` and `sh`
   * deliberately share an opcode; `ldu` and `stu` provide the distinction.
   * 每个 FuType 子组独立编号，例如 `lh` 与 `sh` 可同码，必须结合 ldu/stu 判断。
   */
  object LSUOpType {
    // ldu: [1:0] is access size, [2] unsigned; floating loads use [4].
    def lb  = "b0_0000".U
    def lh  = "b0_0001".U
    def lw  = "b0_0010".U
    def ld  = "b0_0011".U
    def lbu = "b0_0100".U
    def lhu = "b0_0101".U
    def lwu = "b0_0110".U
    def flh = "b1_0001".U
    def flw = "b1_0010".U
    def fld = "b1_0011".U

    // Software prefetch keeps the hint kind in [1:0].
    def prefetchI = "b0_1000".U
    def prefetchR = "b0_1001".U
    def prefetchW = "b0_1010".U

    // stu: integer stores reuse the size encoding; floating stores use [4].
    def sb  = "b0_0000".U
    def sh  = "b0_0001".U
    def sw  = "b0_0010".U
    def sd  = "b0_0011".U
    def fsh = "b1_0001".U
    def fsw = "b1_0010".U
    def fsd = "b1_0011".U

    def cboZero  = "b0_0111".U
    def cboClean = "b0_1100".U
    def cboFlush = "b0_1101".U
    def cboInval = "b0_1110".U

    // mou: [1:0] is access size; [5:2] selects LR/SC/AMO operation.
    def lrW      = "b000010".U
    def scW      = "b000110".U
    def amoswapW = "b001010".U
    def amoaddW  = "b001110".U
    def amoxorW  = "b010010".U
    def amoandW  = "b010110".U
    def amoorW   = "b011010".U
    def amominW  = "b011110".U
    def amomaxW  = "b100010".U
    def amominuW = "b100110".U
    def amomaxuW = "b101010".U
    def amocasW  = "b101110".U

    def lrD      = "b000011".U
    def scD      = "b000111".U
    def amoswapD = "b001011".U
    def amoaddD  = "b001111".U
    def amoxorD  = "b010011".U
    def amoandD  = "b010111".U
    def amoorD   = "b011011".U
    def amominD  = "b011111".U
    def amomaxD  = "b100011".U
    def amominuD = "b100111".U
    def amomaxuD = "b101011".U
    def amocasD  = "b101111".U
    def amocasQ  = "b101100".U

    def size(op:  UInt): UInt = op(1, 0)
    def isLr(op:  UInt): Bool = op === lrW || op === lrD
    def isSc(op:  UInt): Bool = op === scW || op === scD
    def isCbo(op: UInt): Bool = op(3, 2) === "b11".U

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Bit-manipulation operations: Zba/Zbb/Zbs (the RVB23 "B" set) and Zicond.
   * 位操作：Zba/Zbb/Zbs（RVB23 的 B 集合）与 Zicond。
   */
  object BKUOpType {
    // Zba and shifts use the same operation-family layout as ALUOpType.
    def slliUw   = "b000_0000".U
    def rol      = "b000_1001".U
    def ror      = "b000_1011".U
    def rolw     = "b001_1100".U
    def rorw     = "b001_1101".U
    def addUw    = "b010_0000".U
    def sh1addUw = "b010_1000".U
    def sh1add   = "b010_1001".U
    def sh2addUw = "b010_1010".U
    def sh2add   = "b010_1011".U
    def sh3addUw = "b010_1100".U
    def sh3add   = "b010_1101".U

    def maxu = "b011_0100".U
    def minu = "b011_0101".U
    def max  = "b011_0110".U
    def min  = "b011_0111".U

    def andn  = "b100_0001".U
    def orn   = "b100_0011".U
    def xnor  = "b100_0101".U
    def orcb  = "b100_0110".U
    def rev8  = "b100_0111".U
    def sextb = "b100_1000".U
    def sexth = "b100_1010".U
    def zexth = "b110_0001".U

    def bclr = "b000_0010".U
    def bset = "b000_0011".U
    def binv = "b000_0100".U
    def bext = "b000_0110".U

    // Register/immediate forms share the operation; SrcType selects the index.
    def rori  = ror
    def roriw = rorw
    def bclri = bclr
    def bexti = bext
    def binvi = binv
    def bseti = bset

    def czeroEqz = "b111_0100".U
    def czeroNez = "b111_0110".U

    // Count operations use a tagged subspace so they cannot alias rotate codes.
    def clz   = "b10_001000".U
    def clzw  = "b10_001001".U
    def ctz   = "b10_001010".U
    def ctzw  = "b10_001011".U
    def cpop  = "b10_001100".U
    def cpopw = "b10_001101".U

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Floating-point add/compare/sign-injection and Zfa arithmetic. */
  object FALUOpType {
    // [6:5] format: H=00, S=01, D=10; [4:0] operation.
    def faddS   = "b01_00000".U
    def fsubS   = "b01_00001".U
    def fminS   = "b01_00010".U
    def fmaxS   = "b01_00011".U
    def fsgnjS  = "b01_00110".U
    def fsgnjnS = "b01_00111".U
    def fsgnjxS = "b01_01000".U
    def feqS    = "b01_01001".U
    def fltS    = "b01_01011".U
    def fleS    = "b01_01100".U
    def fleqS   = "b01_01101".U
    def fltqS   = "b01_01110".U
    def fclassS = "b01_01111".U
    def fliS    = "b01_10000".U
    def fmaxmS  = "b01_10011".U
    def fminmS  = "b01_11110".U

    def faddD   = "b10_00000".U
    def fsubD   = "b10_00001".U
    def fminD   = "b10_00010".U
    def fmaxD   = "b10_00011".U
    def fsgnjD  = "b10_00110".U
    def fsgnjnD = "b10_00111".U
    def fsgnjxD = "b10_01000".U
    def feqD    = "b10_01001".U
    def fltD    = "b10_01011".U
    def fleD    = "b10_01100".U
    def fleqD   = "b10_01101".U
    def fltqD   = "b10_01110".U
    def fclassD = "b10_01111".U
    def fliD    = "b10_10000".U
    def fmaxmD  = "b10_10011".U
    def fminmD  = "b10_11110".U

    def fliH = "b00_10000".U

    def format(op: UInt): UInt = op(6, 5)
    def opcode(op: UInt): UInt = op(4, 0)

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Floating-point fused multiply-add and multiplies. 浮点乘加与乘法。 */
  object FMACOpType {
    // [5:4] format: S=01, D=10; [3:0] operation.
    def fmulS   = "b01_0000".U
    def fmaddS  = "b01_0001".U
    def fmsubS  = "b01_0010".U
    def fnmsubS = "b01_0011".U
    def fnmaddS = "b01_0100".U

    def fmulD   = "b10_0000".U
    def fmaddD  = "b10_0001".U
    def fmsubD  = "b10_0010".U
    def fnmsubD = "b10_0011".U
    def fnmaddD = "b10_0100".U

    def format(op: UInt): UInt = op(5, 4)
    def opcode(op: UInt): UInt = op(3, 0)

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Floating-point conversions and register moves. 浮点转换与寄存器搬运。
   *
   * Conversion direction, integer signedness and source/destination format are
   * encoded here.  Execution never needs to recover them from the instruction.
   */
  object FCVTOpType {
    // [8:7] kind: 00 move, 01 fp->int, 10 int->fp, 11 fp->fp/Zfa.
    // Floating formats: H=00, S=01, D=10. Integer width: W=0, L=1.

    // move: kind ## fp-to-int ## format ## padding
    def fmvHX = "b00_0_00_0000".U
    def fmvXH = "b00_1_00_0000".U
    def fmvWX = "b00_0_01_0000".U
    def fmvXW = "b00_1_01_0000".U
    def fmvDX = "b00_0_10_0000".U
    def fmvXD = "b00_1_10_0000".U

    // fp->int: kind ## is-long ## unsigned ## source-format ## padding
    def fcvtWS  = "b01_0_0_01_000".U
    def fcvtWuS = "b01_0_1_01_000".U
    def fcvtLS  = "b01_1_0_01_000".U
    def fcvtLuS = "b01_1_1_01_000".U
    def fcvtWD  = "b01_0_0_10_000".U
    def fcvtWuD = "b01_0_1_10_000".U
    def fcvtLD  = "b01_1_0_10_000".U
    def fcvtLuD = "b01_1_1_10_000".U

    // int->fp: kind ## destination-format ## is-long ## unsigned ## padding
    def fcvtSW  = "b10_01_0_0_000".U
    def fcvtSWu = "b10_01_0_1_000".U
    def fcvtSL  = "b10_01_1_0_000".U
    def fcvtSLu = "b10_01_1_1_000".U
    def fcvtDW  = "b10_10_0_0_000".U
    def fcvtDWu = "b10_10_0_1_000".U
    def fcvtDL  = "b10_10_1_0_000".U
    def fcvtDLu = "b10_10_1_1_000".U

    // fp->fp: kind ## 00 ## source-format ## destination-format ## padding
    def fcvtSD = "b11_00_10_01_0".U
    def fcvtDS = "b11_00_01_10_0".U
    def fcvtSH = "b11_00_00_01_0".U
    def fcvtHS = "b11_00_01_00_0".U

    // Zfa: kind ## subkind ## source-format ## nx ## padding
    def froundS   = "b11_01_01_0_00".U
    def froundnxS = "b11_01_01_1_00".U
    def froundD   = "b11_01_10_0_00".U
    def froundnxD = "b11_01_10_1_00".U
    def fcvtmodWD = "b11_10_10_001".U

    def kind(op:      UInt): UInt = op(8, 7)
    def isMove(op:    UInt): Bool = kind(op) === "b00".U
    def isFpToInt(op: UInt): Bool = kind(op) === "b01".U
    def isIntToFp(op: UInt): Bool = kind(op) === "b10".U

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  /* Floating-point divide and square root. 浮点除法与开方。 */
  object FDivSqrtOpType {
    // [2:1] format: S=01, D=10; [0] square root.
    def fdivS  = "b01_0".U
    def fsqrtS = "b01_1".U
    def fdivD  = "b10_0".U
    def fsqrtD = "b10_1".U

    def format(op: UInt): UInt = op(2, 1)
    def isSqrt(op: UInt): Bool = op(0)

    def apply(): UInt = UInt(FuOpType.width.W)
  }

  object ImmType {
    val width = 3
    val none  = 0
    val i     = 1
    val s     = 2
    val b     = 3
    val u     = 4
    val j     = 5
    val z     = 6

    /** FLI encodes a five-bit floating-point constant index in the rs1 field. */
    val fli = 7

    def apply(): UInt = UInt(width.W)
  }

  /** Immediate payload carried by the pipeline.
    *
    * Decode stores only the bits encoded in the instruction; sign or zero extension to XLEN happens at the execution
    * side. B/J drop their implicit zero LSB and U keeps 20 bits without the 12-bit shift, so the packed field is
    * `width` bits instead of XLEN. 立即数在流水线中只保留编码位段，扩展由执行端完成。
    */
  object ImmPacking {
    val width = 20

    /** Extract the immediate payload selected by `immType` from a 32-bit instruction. */
    def pack(instr: UInt, immType: UInt): UInt =
      MuxLookup(immType, 0.U(width.W))(
        Seq(
          ImmType.i.U(ImmType.width.W)   -> instr(31, 20),
          ImmType.s.U(ImmType.width.W)   -> Cat(instr(31, 25), instr(11, 7)),
          ImmType.b.U(ImmType.width.W)   -> Cat(instr(31), instr(7), instr(30, 25), instr(11, 8)),
          ImmType.u.U(ImmType.width.W)   -> instr(31, 12),
          ImmType.j.U(ImmType.width.W)   -> Cat(instr(31), instr(19, 12), instr(20), instr(30, 21)),
          ImmType.z.U(ImmType.width.W)   -> instr(31, 20),
          ImmType.fli.U(ImmType.width.W) -> instr(19, 15),
        )
      )

    /** Expand a packed immediate to XLEN using its encoding type. */
    def extend(packed: UInt, immType: UInt, xlen: Int): UInt = {
      require(xlen >= 32, "packed immediates are defined for XLEN >= 32")

      def signed(value: UInt, valueWidth: Int): UInt =
        if (valueWidth >= xlen) value
        else Cat(Fill(xlen - valueWidth, value(valueWidth - 1)), value)

      def unsigned(value: UInt, valueWidth: Int): UInt =
        if (valueWidth >= xlen) value
        else Cat(0.U((xlen - valueWidth).W), value)

      MuxLookup(immType, 0.U(xlen.W))(
        Seq(
          ImmType.i.U(ImmType.width.W)   -> signed(packed(11, 0), 12),
          ImmType.s.U(ImmType.width.W)   -> signed(packed(11, 0), 12),
          ImmType.b.U(ImmType.width.W)   -> signed(Cat(packed(11, 0), 0.U(1.W)), 13),
          ImmType.u.U(ImmType.width.W)   -> signed(Cat(packed(19, 0), 0.U(12.W)), 32),
          ImmType.j.U(ImmType.width.W)   -> signed(Cat(packed(19, 0), 0.U(1.W)), 21),
          ImmType.z.U(ImmType.width.W)   -> unsigned(packed(11, 0), 12),
          ImmType.fli.U(ImmType.width.W) -> unsigned(packed(4, 0), 5),
        )
      )
    }
  }

  object CommitType {
    val width     = 3
    val normal    = 0
    val exception = 1
    val fence     = 2
    val redirect  = 3
    val noCommit  = 4
    val load      = 5
    val store     = 6
    val atomic    = 7

    def apply(): UInt = UInt(width.W)
  }

  object RedirectCause {
    val width            = 2
    val branchMispredict = 0
    val exception        = 1
    val replay           = 2
    val other            = 3

    def apply(): UInt = UInt(width.W)
  }

  /** Connect fields with the same name from a source-shaped bundle.
    *
    * Only fields already present in both records are connected; ownership of fields with different names remains
    * explicit at the call site. 只连接两端同名字段，不同名字段仍由调用方显式处理，避免隐藏接口语义。
    */
  def connectSamePort(bundleSource: Bundle, bundleSink: Bundle): Unit = {
    bundleSource.elements.foreach { case (name, data) =>
      if (bundleSink.elements.contains(name)) {
        data := bundleSink.elements(name)
      }
    }
  }

  /** Frontend-to-decode contract / 取指到译码之间的接口.
    *
    * RVB23 只规定指令的架构语义；预测信息和前端身份标签属于乱序实现所需的微架构状态。
    */
  class StaticInst(val params: BundleParams = BundleParams()) extends Bundle {

    /** 前端提供的原始指令编码；压缩指令使用低 16 bit，译码器负责展开。 */
    val instr = UInt(params.instructionWidth.W)

    /** 当前指令的 PC，用于控制流目标、异常现场和提交端精确状态。 */
    val pc = UInt(params.pcWidth.W)

    /** 前端已识别的取指异常位图；译码器会把它映射到统一异常位图并追加非法指令位。 */
    val exceptionBits = UInt(FetchExceptionBits.width.W)

    /** 前端预测的跳转方向，执行阶段据此判断方向预测是否错误。 */
    val predictedTaken = Bool()

    /** FTQ entry pointer; with `ftqOffset` it identifies the instruction across the frontend pipeline. */
    val ftqPtr = new FtqPtr(params)

    /** Slot within the FTQ entry; two halfword slots per lane when RVC is enabled. */
    val ftqOffset = UInt(params.ftqOffsetWidth.W)

    /** 当前指令是否为该 FTQ entry 中最后一条有效指令，用于阻止融合/ROB 压缩跨块。 */
    val isLastInFtqEntry = Bool()

  }

  /** Decode output before register renaming / 重命名前的译码结果.
    *
    * 这里只保留标量 RVB23 和乱序后端需要的信息；不包含向量、虚拟化、SSIT/WaitTable 或调试单步状态。
    */
  class DecodedInst(val params: BundleParams = BundleParams()) extends Bundle {

    /** 原始指令编码，用于异常报告、调试和提交端指令识别。 */
    val instr = UInt(params.instructionWidth.W)

    /** 指令 PC。 */
    val pc = UInt(params.pcWidth.W)

    /** 统一异常位图，包含前端异常、取指故障和非法指令。 */
    val exceptionBits = UInt(params.exceptionBits.W)

    /** 前端预测方向。 */
    val predictedTaken = Bool()

    /** FTQ entry pointer; with `ftqOffset` it identifies the instruction across the frontend pipeline. */
    val ftqPtr = new FtqPtr(params)

    /** Slot within the FTQ entry; two halfword slots per lane when RVC is enabled. */
    val ftqOffset = UInt(params.ftqOffsetWidth.W)

    /** 该 FTQ entry 的末条标志，融合和 ROB 压缩不能跨越该边界。 */
    val isLastInFtqEntry = Bool()

    /** 每个源槽的类别：整数寄存器、浮点寄存器、立即数或无源。 */
    val srcType = Vec(params.numSrc, SrcType())

    /** 译码得到的逻辑源寄存器号；重命名后转换为 `psrc`。 */
    val logicalSrc = Vec(params.numSrc, UInt(params.logicalRegWidth.W))

    /** 逻辑目的寄存器号。 */
    val logicalDest = UInt(params.logicalRegWidth.W)

    /** 功能单元类别；与 `fuOpType` 共同唯一确定标量运算。 */
    val fuType = FuType()

    /** 功能单元内部操作码，其含义由 `fuType` 解释。 */
    val fuOpType = FuOpType()

    /** 指令是否写整数寄存器堆。 */
    val intWen = Bool()

    /** 指令是否写浮点寄存器堆。 */
    val fpWen = Bool()

    /** 必须等待更老指令先向前推进，适用于部分 CSR/内存序指令。 */
    val waitForward = Bool()

    /** 阻止更年轻指令越过当前指令。 */
    val blockBackward = Bool()

    /** 指令提交时必须刷新自身之后的流水线状态。 */
    val commitSelfFlush = Bool()

    /** 指令允许与相邻简单指令共享 ROB 条目。 */
    val canRobCompress = Bool()

    /** 立即数字段的编码类型，供融合和执行输入选择使用。 */
    val immType = ImmType()

    /** 打包后的立即数；执行端用 `ImmPacking.extend` 扩展到 XLEN。 */
    val imm = UInt(ImmPacking.width.W)

    /** 可由重命名阶段消除的整数寄存器 move。 */
    val isMove = Bool()

    /** ROB/提交端使用的指令类别。 */
    val commitType = CommitType()

    /** 浮点运算是否需要读取动态舍入模式。 */
    val needsRoundingMode = Bool()

    /** 指令编码中的浮点舍入模式；仅在 `needsRoundingMode` 时参与合法性和执行。 */
    val rm = UInt(3.W)

    /** 是否已经携带异常。异常状态只存一份，避免额外状态与位图不一致。 */
    def hasException: Bool = exceptionBits.orR
  }

  /** Shared scalar fusion source-selection metadata. */
  class FusionInfo extends Bundle {
    val rs2FromRs1  = Bool()
    val rs2FromRs2  = Bool()
    val rs2FromZero = Bool()
  }

  /** Minimal identity kept for an instruction that raised a frontend trap. */
  class TrapInstInfo(val params: BundleParams = BundleParams()) extends Bundle {
    val instr     = UInt(params.instructionWidth.W)
    val pc        = UInt(params.pcWidth.W)
    val ftqPtr    = new FtqPtr(params)
    val ftqOffset = UInt(params.ftqOffsetWidth.W)

    /** Compare two instructions in FTQ order using wrap-aware pointer age. */
    def needFlush(currentFtqPtr: FtqPtr, currentFtqOffset: UInt): Bool =
      (ftqPtr === currentFtqPtr && ftqOffset > currentFtqOffset) ||
        ftqPtr.isAfter(currentFtqPtr)

    def sameInst(currentFtqPtr: FtqPtr, currentFtqOffset: UInt): Bool =
      (ftqPtr === currentFtqPtr) && (ftqOffset === currentFtqOffset)

    def fromDecodedInst(decodedInst: DecodedInst): Unit = {
      instr     := decodedInst.instr
      pc        := decodedInst.pc
      ftqPtr    := decodedInst.ftqPtr
      ftqOffset := decodedInst.ftqOffset
    }
  }

  /** Renamed instruction sent to dispatch / 完成重命名并送往分派的微操作.
    *
    * 这些字段中只有指令、寄存器和异常语义由 RVB23 直接约束；ROB、LSQ、预测、依赖和快照字段是实现精确异常与乱序执行所必需的状态。
    */
  class DynInst(val params: BundleParams = BundleParams()) extends Bundle {

    /** 原始指令编码。 */
    val instr = UInt(params.instructionWidth.W)

    /** 指令 PC。 */
    val pc = UInt(params.pcWidth.W)

    /** 统一异常位图；非零时该指令不能产生正常架构写回。 */
    val exceptionBits = UInt(params.exceptionBits.W)

    /** 前端预测方向，供控制流执行单元检查误预测。 */
    val predictedTaken = Bool()

    /** FTQ entry pointer; with `ftqOffset` it identifies the instruction after rename. */
    val ftqPtr = new FtqPtr(params)

    /** Slot within the FTQ entry. */
    val ftqOffset = UInt(params.ftqOffsetWidth.W)

    /** 重命名后仍需保留的源类别，用于选择寄存器堆和唤醒域。 */
    val srcType = Vec(params.numSrc, SrcType())

    /** 逻辑目的寄存器，用于提交或重定向后的映射恢复。 */
    val logicalDest = UInt(params.logicalRegWidth.W)

    /** 功能单元类别。 */
    val fuType = FuType()

    /** 功能单元内部操作码。 */
    val fuOpType = FuOpType()

    /** 整数寄存器写使能。 */
    val intWen = Bool()

    /** 浮点寄存器写使能。 */
    val fpWen = Bool()

    /** 等待更老指令向前推进。 */
    val waitForward = Bool()

    /** 阻止更年轻指令越过。 */
    val blockBackward = Bool()

    /** 提交时刷新后续流水线。 */
    val commitSelfFlush = Bool()

    /** 立即数类型。 */
    val immType = ImmType()

    /** 打包立即数；送执行端后再扩展。 */
    val imm = UInt(ImmPacking.width.W)

    /** 译码识别且满足消除条件的 move。 */
    val isMove = Bool()

    /** 提交类别。 */
    val commitType = CommitType()

    /** 是否读取浮点动态舍入模式。 */
    val needsRoundingMode = Bool()

    /** 译码阶段保留下来的浮点舍入模式。 */
    val rm = UInt(3.W)

    /** 每个物理源操作数在进入分派时的就绪状态。 */
    val srcState = Vec(params.numSrc, SourceState())

    /** 推测 load 唤醒依赖，用于 load 取消后的选择性回滚；它不属于 SSIT/WaitTable。 */
    val srcLoadDependency = Vec(
      params.numSrc,
      Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W)),
    )

    /** 每个源操作数是否命中 RegCache；由 Dispatch 查询标签表后填写。 */
    val useRegCache = Vec(params.numSrc, Bool())

    /** 命中 RegCache 时对应的全局索引；未命中时无意义。 */
    val regCacheIdx = Vec(params.numSrc, UInt(params.regCacheIndexWidth.W))

    /** 重命名后的物理源寄存器号。 */
    val psrc = Vec(params.numSrc, UInt(params.physicalRegWidth.W))

    /** 新分配或 move 消除复用的物理目的寄存器号。 */
    val pdest = UInt(params.physicalRegWidth.W)

    /** 指令所属的 ROB 条目。 */
    val robIdx = UInt(params.robIndexWidth.W)

    /** 当前压缩 ROB 组包含的指令数，不是 16/32-bit 指令字节数。 */
    val instrSize = UInt(widthFor(params.renameWidth + 1).W)

    /** Load Queue 条目号，由分派/LSQ 分配。 */
    val lqIdx = UInt(params.queueIndexWidth.W)

    /** Store Queue 条目号，由分派/LSQ 分配。 */
    val sqIdx = UInt(params.queueIndexWidth.W)

    /** 当前 move 是否已通过复用物理源寄存器完成消除。 */
    val eliminatedMove = Bool()

    /** 当前指令是否触发一份重命名恢复快照。 */
    val snapshot = Bool()

    /** 指令是否因执行条件变化而需要重新发射。 */
    val replayInst = Bool()

    /** 是否已经携带异常；由异常位图组合产生，不额外保存一份状态。 */
    def hasException: Bool = exceptionBits.orR

    def srcIsReady: Vec[Bool] = {
      VecInit(srcType.zip(srcState).map { case (sourceType, sourceState) =>
        !SrcType.isRegister(sourceType) || SourceState.isReady(sourceState)
      })
    }

    def isMemory: Bool = FuType.isMemory(fuType)

    def isLoad: Bool = FuType.isLoad(fuType)

    def isStore: Bool = FuType.isStore(fuType)

    def isControlFlow: Bool = FuType.isControlFlow(fuType)

    /** Clear selected exception state while preserving explicitly requested bits. */
    def clearExceptions(
      keepBits:    Seq[Int] = Seq.empty,
      clearFlush:  Boolean  = true,
      clearReplay: Boolean  = true,
    ): this.type = {
      for (bit <- 0 until params.exceptionBits if !keepBits.contains(bit)) {
        exceptionBits(bit) := false.B
      }
      if (clearFlush) {
        commitSelfFlush := false.B
      }
      if (clearReplay) {
        replayInst := false.B
      }
      this
    }

    def needWriteRf: Bool = intWen || fpWen
  }

  /** Marker for a writeback source used by scheduler bookkeeping. */
  trait BundleSource {
    var wakeupSource = "undefined"
    var sourceIndex  = 0
  }

  /** Common wakeup payload shared by writeback and issue-queue sources.
    *
    * A wakeup is valid only when the physical destination matches and the destination register class agrees with the
    * consumer's source class. 唤醒必须同时满足物理目的寄存器匹配和寄存器类别匹配，不能只比较编号。
    */
  abstract class IssueQueueWakeUpBaseBundle(
    val params:     BundleParams,
    val exuIndices: Seq[Int],
  ) extends Bundle {
    val intWen = Bool()
    val fpWen  = Bool()
    val pdest  = UInt(params.physicalRegWidth.W)

    def wakeUp(successor: Seq[(UInt, UInt)], valid: Bool): Seq[Bool] = {
      successor.map { case (successorPdest, successorSrcType) =>
        val pdestMatch = pdest === successorPdest
        val classMatch =
          (SrcType.isInteger(successorSrcType) && intWen) ||
            (SrcType.isFloating(successorSrcType) && fpWen)
        pdestMatch && classMatch && valid
      }
    }

    def wakeUpFromIssueQueue(successor: Seq[(UInt, UInt)]): Seq[Bool] = {
      successor.map { case (successorPdest, successorSrcType) =>
        val pdestMatch = pdest === successorPdest
        val classMatch =
          (SrcType.isInteger(successorSrcType) && intWen) ||
            (SrcType.isFloating(successorSrcType) && fpWen)
        pdestMatch && classMatch
      }
    }

    def hasOnlyOneSource: Boolean = exuIndices.size == 1

    def hasMultiSources: Boolean = exuIndices.size > 1

    def isWritebackWakeup: Boolean = this.isInstanceOf[IssueQueueWBWakeUpBundle]

    def isIssueQueueWakeup: Boolean = this.isInstanceOf[IssueQueueIQWakeUpBundle]

    def exuIdx: Int = {
      require(hasOnlyOneSource, "exuIdx is meaningful only for a single source")
      exuIndices.head
    }
  }

  class IssueQueueWBWakeUpBundle(
    exuIndices: Seq[Int],
    params:     BundleParams = BundleParams(),
  ) extends IssueQueueWakeUpBaseBundle(params, exuIndices)

  /** Wakeup generated directly by an issue queue entry.
    *
    * Copy lanes are physically present only as a small bounded array. The `copyValid` bit tells consumers whether those
    * lanes carry meaningful data. 复制唤醒端口采用有界小数组，`copyValid` 用来区分端口存在和数据有效。
    */
  class IssueQueueIQWakeUpBundle(
    val exuIndex:  Int,
    val exuParams: ExecutionUnitParams,
    params:        BundleParams = BundleParams(),
    copyWakeupOut: Boolean      = false,
    copyNum:       Int          = 0,
  ) extends IssueQueueWakeUpBaseBundle(params, Seq(exuIndex)) {
    require(copyNum >= 0, "copyNum must be non-negative")
    require(copyNum <= params.maxWakeupCopies, "copyNum exceeds configured wakeup capacity")

    val loadDependency = Vec(
      params.loadPipelineWidth,
      UInt(params.loadDependencyWidth.W),
    )
    val isZeroLatency  = Bool()

    /** Register-cache slot reserved for this result; consumed by dispatch tag table. */
    val rcDest     = UInt(params.regCacheIndexWidth.W)
    val copyValid  = Bool()
    val copyDest   = Vec(math.max(copyNum, 1), UInt(params.physicalRegWidth.W))
    val copyIntWen = Vec(math.max(copyNum, 1), Bool())
    val copyFpWen  = Vec(math.max(copyNum, 1), Bool())

    def fromExuInput(exuInput: ExuInput): Unit = {
      intWen         := exuInput.intWen
      fpWen          := exuInput.fpWen
      pdest          := exuInput.pdest
      loadDependency := exuInput.loadDependency
      copyValid      := copyWakeupOut.B && (copyNum > 0).B
    }
  }

  /** Scalar rounding-mode requirement / 标量浮点舍入模式需求. */
  class RoundingModeUseBundle extends Bundle {
    val needed = Bool()
  }

  /** Per-stage issue response encoding / 每个流水级的响应编码.
    *
    *   - `block`     : 这一级没有通过，条目必须清 issued 并重新发射；
    *   - `uncertain` : 这一级通过了，但最终结果还要等后续阶段（访存/向量反馈），条目既不清理也不重发；
    *   - `success`   : 最终完成，条目可以清除。
    *
    * 编码与香山的 RespType 一致。当前 AnserCore 只产生 block/success，uncertain 为预留语义。
    */
  object IssueRespType {
    val width     = 2
    val block     = 0
    val uncertain = 1
    val success   = 3

    def isBlocked(resp:   UInt): Bool = resp === block.U
    def isUncertain(resp: UInt): Bool = resp === uncertain.U
    def isSuccess(resp:   UInt): Bool = resp === success.U
  }

  /** One issue-queue entry response / 一个发射队列条目的响应.
    *
    * `resp = block` 表示这一级没有通过，指令需要重发；`success` 表示可以清除条目；
    * `uncertain` 表示这一级通过但还不是最终完成（当前未产生，留给访存/向量反馈）。
    * `fuType` 回送以便忙表释放正确的延迟槽。
    */
  class IssueEntryResponse(val params: BundleParams = BundleParams()) extends Bundle {
    val robIdx = UInt(params.robIndexWidth.W)
    val fuType = FuType()
    val resp   = UInt(IssueRespType.width.W)
  }

  /** Issue queue to datapath / 发射队列到数据通路的接口. */
  class IssueQueueIssueBundle(
    val issueParams: IssueQueueParams    = IssueQueueParams(),
    val exuParams:   ExecutionUnitParams = ExecutionUnitParams(),
    val params:      BundleParams        = BundleParams(),
  ) extends Bundle {
    // `srcType` is separate from `src`; it selects the source of each value.
    // `srcType` 与 `src` 分开，前者决定每个数据值的选择来源。
    val srcType     = Vec(exuParams.numRegSrc, SrcType())
    val regCacheIdx = Vec(
      exuParams.numRegSrc,
      UInt(params.regCacheIndexWidth.W),
    )
    val immType     = ImmType()

    /** Physical source addresses used by DataPath for the PRF read ports. */
    val psrc       = Vec(exuParams.numRegSrc, UInt(params.physicalRegWidth.W))
    val common     = new ExuInput(params, exuParams)
    val addrOH     = UInt(issueParams.numEntries.W)
    val issueValid = Bool()

    def exuIdx: Int = exuParams.exuIdx

    def wakeupSourceExuIdx: Seq[Int] = issueParams.wakeupSourceExuIdx
  }

  /** Oldest-group response / 最老组选择结果. */
  class OGRespBundle(val params: BundleParams = BundleParams()) extends Bundle {
    val og0resp = Valid(new IssueEntryResponse(params))
    val og1resp = Valid(new IssueEntryResponse(params))
  }

  /** Busy-table write payload.
    *
    * A countdown value reserves a writeback slot for a known latency. The dequeue bits are separate because a table can
    * be updated and consumed in the same cycle. 倒计时值表示已知延迟占用的写回槽；dequeue 位单独存在，因为同一周期 可能同时更新和消费 busy 表。
    */
  class WbFuBusyTableWriteBundle(
    val exuParams: ExecutionUnitParams,
    val params:    BundleParams = BundleParams(),
  ) extends Bundle {

    /** Slot-reservation vector, indexed by latency; the last bit is never used. */
    val intWbBusyTable = UInt((params.maxLatency + 1).W)
    val fpWbBusyTable  = UInt((params.maxLatency + 1).W)

    /** Latency vector of the instruction dequeued this cycle (one-hot bit at its latency). */
    val intDeqRespSet = UInt((params.maxLatency + 1).W)
    val fpDeqRespSet  = UInt((params.maxLatency + 1).W)
  }

  /** Busy-table read payload / busy 表读取接口. */
  class WbFuBusyTableReadBundle(
    val exuParams: ExecutionUnitParams,
    val params:    BundleParams = BundleParams(),
  ) extends Bundle {
    val intWbBusyTable = UInt((params.maxLatency + 1).W)
    val fpWbBusyTable  = UInt((params.maxLatency + 1).W)
  }

  /** Writeback conflict flags / 写回冲突标志. */
  class WbConflictBundle(
    val exuParams: ExecutionUnitParams,
    val params:    BundleParams = BundleParams(),
  ) extends Bundle {
    val intConflict = Bool()
    val fpConflict  = Bool()
  }

  class ImmInfo(val params: BundleParams = BundleParams()) extends Bundle {
    val imm     = UInt(ImmPacking.width.W)
    val immType = ImmType()
  }

  /** Redirect emitted by an execution unit / 执行单元产生的控制流重定向. */
  class Redirect(val params: BundleParams = BundleParams()) extends Bundle {
    val target = UInt(params.pcWidth.W)
    val taken  = Bool()
    val cause  = RedirectCause()
    val robIdx = UInt(params.robIndexWidth.W)
  }

  /** Issue-to-execution payload.
    *
    * The optional-looking information in the reference design is represented here by always-present fields plus
    * explicit valid bits. This keeps the record shape deterministic for Chisel connections and makes field ownership
    * visible in waveforms.
    *
    * 参考实现中的可选字段在这里改为固定字段加 valid 位，使 Chisel 接口形状 稳定，并能在波形中直接看出字段是否属于当前执行类型。
    */
  class ExuInput(
    val params:    BundleParams        = BundleParams(),
    val exuParams: ExecutionUnitParams = ExecutionUnitParams(),
    copyWakeupOut: Boolean             = false,
    copyNum:       Int                 = 0,
  ) extends Bundle {
    require(copyNum >= 0, "copyNum must be non-negative")
    require(copyNum <= params.maxWakeupCopies, "copyNum exceeds configured wakeup capacity")

    val fuType   = FuType()
    val fuOpType = FuOpType()
    val immType  = ImmType()
    val srcType  = Vec(exuParams.numRegSrc, SrcType())
    val src      = Vec(exuParams.numRegSrc, UInt(exuParams.srcDataWidth.W))

    /** Packed immediate; expand with `ImmPacking.extend` before use. */
    val imm          = UInt(ImmPacking.width.W)
    val nextPcOffset = UInt(params.pcWidth.W)
    val robIdx       = UInt(params.robIndexWidth.W)
    val iqIdx        = UInt(params.queueIndexWidth.W)
    val isFirstIssue = Bool()

    // Copy fields are meaningful only when copyValid is asserted.
    // 复制字段只有在 copyValid 置位时才有意义。
    val copyValid  = Bool()
    val copyDest   = Vec(math.max(copyNum, 1), UInt(params.physicalRegWidth.W))
    val copyIntWen = Vec(math.max(copyNum, 1), Bool())
    val copyFpWen  = Vec(math.max(copyNum, 1), Bool())

    val pdest             = UInt(params.physicalRegWidth.W)
    val intWen            = Bool()
    val fpWen             = Bool()
    val needsRoundingMode = Bool()
    val commitSelfFlush   = Bool()
    val pc                = UInt(params.pcWidth.W)
    val ftqPtr            = new FtqPtr(params)
    val ftqOffset         = UInt(params.ftqOffsetWidth.W)

    val predictTarget = UInt(params.pcWidth.W)
    val predictTaken  = Bool()
    val predictValid  = Bool()

    // LSQ indices remain part of the scalar memory pipeline. SSIT/WaitTable
    // prediction fields are intentionally absent until that predictor exists.
    val lqIdx = UInt(params.queueIndexWidth.W)
    val sqIdx = UInt(params.queueIndexWidth.W)

    val dataSources    = Vec(exuParams.numRegSrc, DataSource())
    val exuSources     = Vec(
      exuParams.numRegSrc,
      UInt(widthFor(params.maxExuCount + 1).W),
    )
    val srcTimer       = Vec(exuParams.numRegSrc, UInt(3.W))
    val loadDependency = Vec(
      params.loadPipelineWidth,
      UInt(params.loadDependencyWidth.W),
    )

    def fromIssueBundle(source: IssueQueueIssueBundle): Unit = {
      connectSamePort(this, source.common)
      srcType := source.srcType
      srcTypeCopy(source.srcType)
      immType := source.immType
    }

    private def srcTypeCopy(source: Vec[UInt]): Unit = {
      // The source and destination dimensions are fixed by the unit contract.
      // 两端维度由执行单元配置固定，连接前应保证它们一致。
      require(source.length == dataSources.length, "source count mismatch")
      dataSources.zip(source).foreach { case (destination, sourceType) =>
        destination := Mux(
          SrcType.isRegister(sourceType),
          DataSource.registerFile.U,
          Mux(
            sourceType === SrcType.immediate.U,
            DataSource.immediate.U,
            DataSource.constant.U,
          ),
        )
      }
    }
  }

  /** Execution-unit result / 执行单元结果. */
  class ExuOutput(
    val params:    BundleParams        = BundleParams(),
    val exuParams: ExecutionUnitParams = ExecutionUnitParams(),
  ) extends Bundle
      with BundleSource {
    val data            = Vec(
      exuParams.wbPathNum,
      UInt(exuParams.destDataWidth.W),
    )
    val pdest           = UInt(params.physicalRegWidth.W)
    val robIdx          = UInt(params.robIndexWidth.W)
    val intWen          = Bool()
    val fpWen           = Bool()
    val redirect        = Valid(new Redirect(params))
    val fpFlags         = UInt(5.W)
    val writeFpFlags    = Bool()
    val exceptionBits   = UInt(params.exceptionBits.W)
    val hasException    = Bool()
    val commitSelfFlush = Bool()
    val replayInst      = Bool()
    val lqIdx           = UInt(params.queueIndexWidth.W)
    val sqIdx           = UInt(params.queueIndexWidth.W)
    val ftqPtr          = new FtqPtr(params)
  }

  /** Register-file write port / 寄存器堆写端口. */
  class RegisterWriteBundle(
    val addressWidth: Int,
    val dataWidth:    Int,
  ) extends Bundle {
    require(addressWidth > 0 && dataWidth > 0, "register-write widths must be positive")

    val wen  = Bool()
    val addr = UInt(addressWidth.W)
    val data = UInt(dataWidth.W)
  }

  /** Writeback payload made from one selected execution result.
    *
    * `dataIndex` is a Scala-time choice of result path. It is intentionally not a hardware mux select: the selected
    * path is part of the physical writeback-port configuration.
    *
    * `dataIndex` 是 elaboration 阶段确定的数据路径，而不是硬件 mux 选择信号； 选中的路径属于物理写回端口配置。
    */
  class WriteBackBundle(
    val params:    BundleParams        = BundleParams(),
    val exuParams: ExecutionUnitParams = ExecutionUnitParams(),
    val dataIndex: Int                 = 0,
  ) extends Bundle
      with BundleSource {
    require(dataIndex >= 0 && dataIndex < exuParams.wbPathNum, "invalid writeback data index")

    val intWen          = Bool()
    val fpWen           = Bool()
    val pdest           = UInt(params.physicalRegWidth.W)
    val data            = UInt(exuParams.destDataWidth.W)
    val robIdx          = UInt(params.robIndexWidth.W)
    val commitSelfFlush = Bool()
    val replayInst      = Bool()
    val redirect        = Valid(new Redirect(params))
    val fpFlags         = UInt(5.W)
    val writeFpFlags    = Bool()
    val exceptionBits   = UInt(params.exceptionBits.W)
    val hasException    = Bool()
    val ftqPtr          = new FtqPtr(params)

    def fromExuOutput(source: ExuOutput): Unit = {
      intWen          := source.intWen
      fpWen           := source.fpWen
      pdest           := source.pdest
      data            := source.data(dataIndex)
      robIdx          := source.robIdx
      commitSelfFlush := source.commitSelfFlush
      replayInst      := source.replayInst
      redirect        := source.redirect
      fpFlags         := source.fpFlags
      writeFpFlags    := source.writeFpFlags
      exceptionBits   := source.exceptionBits
      hasException    := source.hasException
      ftqPtr          := source.ftqPtr
    }

    def asIntRfWriteBundle(fire: Bool): RegisterWriteBundle = {
      val write = Wire(new RegisterWriteBundle(params.physicalRegWidth, exuParams.destDataWidth))
      write.wen  := intWen && fire
      write.addr := pdest
      write.data := data
      write
    }

    def asFpRfWriteBundle(fire: Bool): RegisterWriteBundle = {
      val write = Wire(new RegisterWriteBundle(params.physicalRegWidth, exuParams.destDataWidth))
      write.wen  := fpWen && fire
      write.addr := pdest
      write.data := data
      write
    }
  }

  /** Bypass result from an execution unit to a datapath input. */
  class ExuBypassBundle(
    val params:    BundleParams        = BundleParams(),
    val exuParams: ExecutionUnitParams = ExecutionUnitParams(),
  ) extends Bundle {
    val intWen = Bool()
    val data   = UInt(exuParams.destDataWidth.W)
    val pdest  = UInt(params.physicalRegWidth.W)
  }

  /** Commit-time exception information / 提交阶段异常信息. */
  class ExceptionInfo(val params: BundleParams = BundleParams()) extends Bundle {
    val pc            = UInt(params.pcWidth.W)
    val instr         = UInt(params.instructionWidth.W)
    val commitType    = CommitType()
    val exceptionBits = UInt(params.exceptionBits.W)
    val isBreakpoint  = Bool()
    val isFetchFault  = Bool()
    val causeAddress  = UInt(params.xlen.W)
    val singleStep    = Bool()
    val isInterrupt   = Bool()
    val isMemory      = Bool()
    val wasRedirected = Bool()
  }

  object FuLatency {
    def apply(params: BundleParams = BundleParams()): UInt =
      UInt(widthFor(params.maxLatency + 1).W)

    def width(params: BundleParams = BundleParams()): Int =
      widthFor(params.maxLatency + 1)
  }

  /** Encoded source execution-unit selector / 编码后的执行单元源选择. */
  class ExuSource(val sourceCount: Int) extends Bundle {
    require(sourceCount > 0, "sourceCount must be positive")

    val value = UInt(widthFor(sourceCount + 1).W)

    /** Convert the local encoded source index to one-hot form. 将局部源编号转换成 one-hot 形式。 */
    def toExuOH: Vec[Bool] = {
      val result = Wire(Vec(sourceCount, Bool()))
      for (index <- 0 until sourceCount) {
        result(index) := value === index.U
      }
      result
    }

    /** Expand only the ordered subset accepted by a consumer. 只展开消费者接受的、有固定顺序的执行单元子集。 */
    def toExuOH(filter: Seq[Int]): Vec[Bool] = {
      require(filter.nonEmpty, "filter must contain at least one source")
      require(
        filter.forall(index => index >= 0 && index < sourceCount),
        "filter contains an invalid source index",
      )
      val result = Wire(Vec(filter.size, Bool()))
      for ((_, localIndex) <- filter.zipWithIndex) {
        result(localIndex) := value === localIndex.U
      }
      result
    }

    def fromExuOH(exuOH: UInt): UInt =
      OHToUInt(exuOH)
  }

  object ExuSource {
    def apply(sourceCount: Int): ExuSource = new ExuSource(sourceCount)
  }

  /** Cancel a pending register wakeup / 取消尚未完成的寄存器唤醒. */
  class CancelSignal(val params: BundleParams = BundleParams()) extends Bundle {
    val intWen = Bool()
    val fpWen  = Bool()
    val pdest  = UInt(params.physicalRegWidth.W)
  }

  /** Scalar memory execution input / 标量访存执行输入. */
  class MemExuInput(val params: BundleParams = BundleParams()) extends Bundle {
    val uop          = new DynInst(params)
    val src          = Vec(3, UInt(params.xlen.W))
    val iqIdx        = UInt(params.queueIndexWidth.W)
    val isFirstIssue = Bool()

    def srcRs1: UInt = src(0)

    def srcRs2: UInt = src(1)

    def srcStride: UInt = src(1)

    def srcStoreData: UInt = src(2)
  }

  /** Scalar memory execution output / 标量访存执行输出. */
  class MemExuOutput(val params: BundleParams = BundleParams()) extends Bundle {
    val uop            = new DynInst(params)
    val data           = UInt(params.xlen.W)
    val isFromLoadUnit = Bool()
    val exceptionBits  = UInt(params.exceptionBits.W)
  }

  /** Extra ROB state for a memory micro-operation / 访存微操作的 ROB 扩展状态. */
  class MemMicroOpRbExt(val params: BundleParams = BundleParams()) extends Bundle {
    val uop  = new DynInst(params)
    val flag = Bool()
  }

  /** Per-load cancellation inputs / 每个 load 的取消输入. */
  class LoadCancel extends Bundle {
    val first  = Bool()
    val second = Bool()
  }

  object LoadShouldCancel {

    /** Combine dependency bits with cancellation reasons. 将依赖位与两个取消原因合并，生成当前 load 是否必须取消的信号。 */
    def apply(loadDependency: Seq[UInt], ldCancel: Seq[LoadCancel]): Bool = {
      require(loadDependency.size == ldCancel.size, "dependency and cancel dimensions differ")
      val cancelSignals = loadDependency.zip(ldCancel).map { case (dependency, cancel) =>
        (cancel.first && dependency(0)) || (cancel.second && dependency(1))
      }
      cancelSignals.fold(false.B)(_ || _)
    }
  }
}
