/* 雁栖湖 Issue 域的静态参数。
 *
 * 这里集中描述 Scheduler / IssueQueue / ExeUnit 的硬件形状：
 *   - 三类 Scheduler：Int / Fp / Mem；
 *   - 每个 IQ 的条目数、入队口、出队口（每个出队口对应一个 ExeUnit）；
 *   - 每个 ExeUnit 支持的 FuType、固定/不确定延迟、PRF 读写口和写回口。
 *
 * 与香山相比：
 *   - 去掉向量 / 虚拟化 / SSIT / 访存 set 预测；
 *   - 每个 IQ 的 OthersEntry 全部是 ComplexEntry，不做 SimpleEntry 及其迁移；
 *   - Scheduler 类型只保留 Int / Fp / Mem，每个 IQ 有 2 个入队口；
 *     STD0 由 MemScheduler 从 STA0 同拍复制，不占 Dispatch 端口。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

sealed trait AnsSchedulerType
case object AnsIntScheduler extends AnsSchedulerType
case object AnsFpScheduler  extends AnsSchedulerType
case object AnsMemScheduler extends AnsSchedulerType

/** FU 延迟模型：固定延迟进入 FuBusyTable / 推测唤醒；不确定延迟只靠 WB 唤醒。 */
sealed trait AnsFuLatency {
  def value: Option[Int]
}

final case class AnsCertainLatency(cycles: Int) extends AnsFuLatency {
  require(cycles >= 0, "fixed latency must be non-negative")
  def value: Option[Int] = Some(cycles)
}

case object AnsUncertainLatency extends AnsFuLatency {
  def value: Option[Int] = None
}

final case class AnsFuConfig(
  name:        String,
  fuType:      Int,
  latency:     AnsFuLatency,
  writesIntRf: Boolean = true,
  writesFpRf:  Boolean = false,
) {
  require(
    fuType > 0 && fuType < (1 << FuType.width) && Integer.bitCount(fuType) == 1,
    s"$name FU type must be a supported one-hot encoding",
  )
}

/** 一个 ExeUnit 的静态属性。
  *
  * `intRdPorts` / `fpRdPorts` 按源槽给出 PRF 读口编号（-1 表示该槽不读对应寄存器堆）。 `intWbPort` / `fpWbPort` 是写回端口编号，-1 表示不写回。
  */
final case class AnsExuParams(
  name:               String,
  exuIdx:             Int,
  schdType:           AnsSchedulerType,
  fuConfigs:          Seq[AnsFuConfig],
  intRdPorts:         Seq[Int],
  intRdPriority:      Seq[Int] = Seq.fill(3)(0),
  fpRdPorts:          Seq[Int],
  fpRdPriority:       Seq[Int] = Seq.fill(3)(0),
  intWbPort:          Int      = -1,
  intWbPriority:      Int      = 0,
  uncertainIntWbPort: Int      = -1,
  fpWbPort:           Int      = -1,
  fpWbPriority:       Int      = 0,
  numRegSrc:          Int      = 3,
  isLoad:             Boolean  = false,
  isStore:            Boolean  = false,
  hasRedirect:        Boolean  = false,
  needPc:             Boolean  = false,
) {
  require(fuConfigs.nonEmpty, s"$name must contain at least one FU")
  require(exuIdx >= 0, s"$name EXU index must be non-negative")
  require(numRegSrc > 0, s"$name must have at least one source slot")
  require(fuConfigs.map(_.fuType).distinct.size == fuConfigs.size, s"$name FU types must be unique")
  require(intRdPorts.length == numRegSrc && fpRdPorts.length == numRegSrc, s"$name read-port count mismatch")
  require(intRdPriority.length == numRegSrc && fpRdPriority.length == numRegSrc, s"$name read-priority count mismatch")
  require((intRdPorts ++ fpRdPorts).forall(_ >= -1), s"$name read ports must be -1 or non-negative")
  require((intRdPriority ++ fpRdPriority).forall(_ >= 0), s"$name read priorities must be non-negative")
  require(uncertainIntWbPort >= -1, s"$name uncertain completion port must be -1 or non-negative")
  require(intWbPort >= -1 && fpWbPort >= -1, s"$name writeback ports must be -1 or non-negative")
  require(intWbPriority >= 0 && fpWbPriority >= 0, s"$name writeback priorities must be non-negative")

  if (splitUncertain) {
    val grouped = uncertainGroups.flatten
    require(uncertainGroups.size == 2 && uncertainGroups.forall(_.nonEmpty), s"$name requires DIV and CSR/FENCE groups")
    require(grouped.distinct.size == grouped.size, s"$name uncertain groups must be disjoint")
    require(
      grouped.toSet == fuConfigs.filter(_.latency.value.isEmpty).map(_.fuType).toSet,
      s"$name uncertain groups must exactly cover uncertain FUs and exclude fixed FUs",
    )
    require(
      fuConfigs.filter(c => grouped.contains(c.fuType)).forall(!_.writesFpRf),
      s"$name uncertain completion cannot write the FP register file",
    )
  }

  def validate(params: BundleParams): Unit = {
    require(exuIdx < params.maxExuCount, s"$name EXU index exceeds source encoding capacity")
    require(numRegSrc == params.numSrc, s"$name source count differs from BundleParams")
    require(fuLatencyMap.values.forall(_ <= params.maxLatency), s"$name latency exceeds busy-table capacity")
    require(
      !(hasUncertainFu && fuLatencyMap.values.exists(_ == 0)),
      s"$name cannot mix an uncertain FU with a fixed lat0 FU",
    )
    require(uncertainIntWbPort < AnsIssueParams.nIntWb, s"$name uncertain completion port out of range")
    require(!splitUncertain || uncertainIntWbPort != intWbPort, s"$name completion paths must use separate write ports")
    require(intWbPort < AnsIssueParams.nIntWb && fpWbPort < AnsIssueParams.nFpWb, s"$name writeback port out of range")
    require(
      intRdPorts.forall(_ < AnsIssueParams.nIntRd) && fpRdPorts.forall(_ < AnsIssueParams.nFpRd),
      s"$name read port out of range",
    )
  }

  /** Shared issue owner with independent DIV and CSR/FENCE completion paths. */
  def splitUncertain: Boolean        = uncertainIntWbPort >= 0
  def uncertainGroups: Seq[Seq[Int]] =
    if (splitUncertain) Seq(Seq(FuType.div), Seq(FuType.csr, FuType.fence)) else Seq.empty
  def uncertainFuMask: BigInt        =
    fuConfigs.filter(_.latency.value.isEmpty).map(c => BigInt(c.fuType)).foldLeft(BigInt(0))(_ | _)

  def fuTypes: Seq[Int] = fuConfigs.map(_.fuType).distinct

  /** 固定延迟 FUs；不确定延迟 FUs 不进推测唤醒和固定延迟忙表。 */
  def fuLatencyMap: Map[Int, Int] =
    fixedLatencyMap(fuConfigs)

  private def fixedLatencyMap(configs: Seq[AnsFuConfig]): Map[Int, Int] =
    configs.flatMap(cfg => cfg.latency.value.map(cycles => cfg.fuType -> cycles)).toMap

  def wakeupFuLatencySet: Set[Int] = fuLatencyMap.values.toSet

  def hasCertainFu: Boolean = fuLatencyMap.nonEmpty

  def hasUncertainFu: Boolean = fuConfigs.exists(_.latency.value.isEmpty)

  /** 该 EXU 是否能作为 IQ 推测唤醒源。 */
  def isIQWakeUpSource: Boolean = hasCertainFu && (intWbPort >= 0 || fpWbPort >= 0)

  def writesInt: Boolean = intWbPort >= 0
  def writesFp: Boolean  = fpWbPort >= 0

  def readIntRf: Boolean = intRdPorts.exists(_ >= 0)
  def readFpRf: Boolean  = fpRdPorts.exists(_ >= 0)

  /** IssueQueueIssueBundle 只需要端口形状与 exuIdx。 */
  def bundleParams: ExecutionUnitParams =
    ExecutionUnitParams(
      exuIdx         = exuIdx,
      numRegSrc      = numRegSrc,
      wbPathNum      = 1,
      writesInteger  = writesInt,
      writesFloating = writesFp,
      hasBranch      = fuTypes.exists(t => t == FuType.jmp || t == FuType.brh),
      hasLoad        = isLoad,
      hasStore       = isStore,
      hasRedirect    = hasRedirect,
      intLatency     = math.max(1, fuLatencyMap.values.foldLeft(0)(_ max _)),
      fpLatency      = math.max(1, fuLatencyMap.values.foldLeft(0)(_ max _)),
    )

  /** 该 EXU 的写回域/端口（用于 DataPath 和 WbFuBusyTable）。
    *
    * 统计可能写对应 PRF 的 FU：falu 比较/分类写 int、算术写 FP；fcvt 也可能写两种域，fmac 只写 FP。 每条指令是否预约由实际 intWen/fpWen 决定。
    */
  def intWbLatencyMap: Map[Int, Int] =
    if (writesInt) fixedLatencyMap(fuConfigs.filter(_.writesIntRf)) else Map.empty

  def fpWbLatencyMap: Map[Int, Int] =
    if (writesFp) fixedLatencyMap(fuConfigs.filter(_.writesFpRf)) else Map.empty

  def isIntLatencyCertain: Boolean =
    !writesInt || splitUncertain || fuConfigs.filter(_.writesIntRf).forall(_.latency.value.nonEmpty)

  def isFpLatencyCertain: Boolean =
    !writesFp || fuConfigs.filter(_.writesFpRf).forall(_.latency.value.nonEmpty)

  def isIntExeUnit: Boolean = schdType == AnsIntScheduler
  def isFpExeUnit: Boolean  = schdType == AnsFpScheduler
  def isMemExeUnit: Boolean = schdType == AnsMemScheduler

  /** 该 fuType 是否属于不确定延迟操作。 */
  def isUncertainFu(fuType: UInt): Bool = {
    val bits = fuConfigs.filter(_.latency.value.isEmpty).map(cfg => fuType === cfg.fuType.U)
    bits.reduceOption(_ || _).getOrElse(false.B)
  }

  /** 该 fuType 是否属于某个固定延迟 FU。 */
  def isFixedFu(fuType: UInt): Bool = {
    val bits = fuConfigs.filter(_.latency.value.nonEmpty).map(cfg => fuType === cfg.fuType.U)
    bits.reduceOption(_ || _).getOrElse(false.B)
  }
}

/** 单个 IQ 的静态属性；每个出队口对应一个 ExeUnit。 */
final case class AnsIssueQueueParams(
  name:               String,
  iqIdx:              Int,
  schdType:           AnsSchedulerType,
  exus:               Seq[AnsExuParams],
  numEntries:         Int,
  numEnq:             Int,
  wakeupSourceExuIdx: Seq[Int],
  isFp:               Boolean,
  isMem:              Boolean,
) {
  require(exus.nonEmpty, s"$name must have at least one dequeue port")
  require(numEntries > numEnq, s"$name needs at least one OthersEntry")
  require(numEnq >= 1 && numEnq <= 2, s"$name enq width is 1 or 2")
  require(exus.size <= 2, s"$name supports at most two dequeue ports")
  require(iqIdx >= 0, s"$name IQ index must be non-negative")
  require(exus.map(_.exuIdx).distinct.size == exus.size, s"$name EXU indices must be unique")
  require(exus.forall(_.schdType == schdType), s"$name EXUs must belong to the same scheduler type")
  require(
    isFp == (schdType == AnsFpScheduler) && isMem == (schdType == AnsMemScheduler),
    s"$name domain flags disagree with scheduler type",
  )
  require(
    wakeupSourceExuIdx.distinct.size == wakeupSourceExuIdx.size && wakeupSourceExuIdx.forall(_ >= 0),
    s"$name wakeup source indices must be unique and non-negative",
  )
  // 多出口 IQ 的 FU 集合必须互斥，否则同一 entry 可能被两个出口同时选中。
  require(
    exus.combinations(2).forall { case Seq(a, b) => a.fuTypes.toSet.intersect(b.fuTypes.toSet).isEmpty },
    s"$name dequeue ports must accept disjoint FU sets",
  )
  // 同一个 IQ 的两个出口不得共享 PRF 读/写口；跨 IQ 共享是允许的。
  require(
    exus.combinations(2).forall { case Seq(a, b) =>
      val aIntRd = a.intRdPorts.filter(_ >= 0).toSet
      val bIntRd = b.intRdPorts.filter(_ >= 0).toSet
      val aFpRd  = a.fpRdPorts.filter(_ >= 0).toSet
      val bFpRd  = b.fpRdPorts.filter(_ >= 0).toSet
      (aIntRd & bIntRd).isEmpty &&
      (aFpRd & bFpRd).isEmpty &&
      (a.intWbPort < 0 || a.intWbPort != b.intWbPort) &&
      (a.fpWbPort < 0 || a.fpWbPort != b.fpWbPort)
    },
    s"$name dequeue ports must use disjoint PRF ports",
  )

  def numDeq: Int = exus.size

  def validate(params: BundleParams): Unit = {
    exus.foreach(_.validate(params))
    require(numEntries <= AnsIssueParams.maxEntries, s"$name entry count exceeds issue-bundle capacity")
    require(
      wakeupSourceExuIdx.forall(idx =>
        idx < params.maxExuCount && AnsIssueParams.allExus.exists(e => e.exuIdx == idx && e.isIQWakeUpSource)
      ),
      s"$name references an unavailable IQ wakeup source",
    )
  }

  def fuTypes: Seq[Int] = exus.flatMap(_.fuTypes).distinct

  def numOthers: Int = numEntries - numEnq

  def intRdPorts: Seq[Int] = exus.flatMap(_.intRdPorts).filter(_ >= 0).distinct.sorted

  def fpRdPorts: Seq[Int] = exus.flatMap(_.fpRdPorts).filter(_ >= 0).distinct.sorted

  /** IssueQueueIssueBundle 的维度参数。addrOH 统一用最大条目数，便于展平连接。 */
  def issueBundleParams: IssueQueueParams =
    IssueQueueParams(numEntries = AnsIssueParams.maxEntries, wakeupSourceExuIdx = wakeupSourceExuIdx)

  def needsReadRegCache: Boolean = !isFp

  /** 每个 IQ 都观察全部整数/浮点写回口，靠 srcType 过滤域。 */
  def wbWakeUpCount: Int = AnsIssueParams.nIntWb + AnsIssueParams.nFpWb

  /** 某出队口是否接收某 fuType。 */
  def portAccepts(deqIdx: Int, fuType: UInt): Bool = {
    val bits = exus(deqIdx).fuTypes.map(t => fuType === t.U)
    bits.reduce(_ || _)
  }
}

/** 一组 IQ 构成一个 Scheduler。 */
final case class AnsSchedulerParams(
  schdType: AnsSchedulerType,
  iqs:      Seq[AnsIssueQueueParams],
) {
  require(iqs.nonEmpty, "scheduler must contain at least one IQ")
  require(iqs.map(_.iqIdx).distinct.size == iqs.size, "IQ indices must be unique")
  require(iqs.map(_.name).distinct.size == iqs.size, "IQ names must be unique")
  require(iqs.forall(_.schdType == schdType), "IQ scheduler types must match their scheduler")
  require(
    iqs.flatMap(_.exus.map(_.exuIdx)).distinct.size == iqs.map(_.numDeq).sum,
    "an EXU must belong to exactly one IQ within its scheduler",
  )

  def validate(params: BundleParams): Unit = iqs.foreach(_.validate(params))

  /** Dispatch 直接喂入的 IQ；STD0 由 MemScheduler 从 STA0 复制，不占 Dispatch 端口。 */
  val dispatchIQs: Seq[AnsIssueQueueParams] = iqs.filterNot(_.name == "STD0")

  /** Dispatch 可见的入队口数量；复制目的 IQ 不占端口。 */
  def dispatchEnqPorts: Int = dispatchIQs.map(_.numEnq).sum

  /** (IQ, 入队槽) 对应的 `fromDispatch` 端口号。 */
  def dispatchPortOf(iq: String, slot: Int): Int = {
    val pos = dispatchIQs.indexWhere(_.name == iq)
    require(pos >= 0, s"$iq is not dispatch-fed in $schdType")
    require(slot >= 0 && slot < dispatchIQs(pos).numEnq, s"$iq enqueue slot $slot out of range")
    dispatchIQs.take(pos).map(_.numEnq).sum + slot
  }

  def numEnqPorts: Int = iqs.map(_.numEnq).sum

  def numDeqPorts: Int = iqs.map(_.numDeq).sum

  /** 出队口对应的全局 ExeUnit 编号，顺序与 IO.deq 一致。 */
  def deqExuIdx: Seq[Int] = iqs.flatMap(_.exus.map(_.exuIdx))

  def exus: Seq[AnsExuParams] = iqs.flatMap(_.exus)

  /** 本 Scheduler 收到的 IQ 唤醒源（按全局 exuIdx 排序）。 */
  def wakeupInExuIdx: Seq[Int] = iqs.flatMap(_.wakeupSourceExuIdx).distinct.sorted

  /** 本 Scheduler 能发出的 IQ 唤醒源。 */
  def wakeupOutExuIdx: Seq[Int] = exus.filter(_.isIQWakeUpSource).map(_.exuIdx).sorted

  def iqIdxInScheduler(iqIdx: Int): Int = iqs.indexWhere(_.iqIdx == iqIdx)
}

/** 雁栖湖后端 Issue 域的唯一拓扑来源。 */
object AnsIssueParams {
  private def fixed(
    fuType:    Int,
    cycles:    Int,
    name:      String,
    writesInt: Boolean = true,
    writesFp:  Boolean = false,
  ): AnsFuConfig =
    AnsFuConfig(name, fuType, AnsCertainLatency(cycles), writesIntRf = writesInt, writesFpRf = writesFp)

  private def uncertain(
    fuType:    Int,
    name:      String,
    writesInt: Boolean = true,
    writesFp:  Boolean = false,
  ): AnsFuConfig =
    AnsFuConfig(name, fuType, AnsUncertainLatency, writesIntRf = writesInt, writesFpRf = writesFp)

  private def exu(
    name:                             String,
    exuIdx:                           Int,
    schdType:                         AnsSchedulerType,
    fuConfigs:                        Seq[AnsFuConfig],
    intRdPorts:                       Seq[Int] = Seq(-1, -1, -1),
    intRdPriority:                    Seq[Int] = Seq(0, 0, 0),
    fpRdPorts:                        Seq[Int] = Seq(-1, -1, -1),
    fpRdPriority:                     Seq[Int] = Seq(0, 0, 0),
    intWbPort:                        Int      = -1,
    intWbPriority:                    Int      = 0,
    uncertainIntWbPort:               Int      = -1,
    fpWbPort:                         Int      = -1,
    fpWbPriority:                     Int      = 0,
    isLoad:                           Boolean  = false,
    isStore:                          Boolean  = false,
    hasRedirect:                      Boolean  = false,
    needPc:                           Boolean  = false,
  ): AnsExuParams =
    AnsExuParams(
      name               = name,
      exuIdx             = exuIdx,
      schdType           = schdType,
      fuConfigs          = fuConfigs,
      intRdPorts         = intRdPorts,
      intRdPriority      = intRdPriority,
      fpRdPorts          = fpRdPorts,
      fpRdPriority       = fpRdPriority,
      intWbPort          = intWbPort,
      intWbPriority      = intWbPriority,
      uncertainIntWbPort = uncertainIntWbPort,
      fpWbPort           = fpWbPort,
      fpWbPriority       = fpWbPriority,
      isLoad             = isLoad,
      isStore            = isStore,
      hasRedirect        = hasRedirect,
      needPc             = needPc,
    )

  // Global ExeUnit order follows the issue-port order below.  Keep it stable:
  // Scheduler wakeup vectors and DataPath bypass data are indexed by exuIdx.
  val intExus: Seq[AnsExuParams] = Seq(
    exu(
      "ALU0",
      0,
      AnsIntScheduler,
      Seq(fixed(FuType.alu, 0, "alu")),
      intRdPorts         = Seq(0, 1, -1),
      intRdPriority      = Seq(0, 0, 0),
      intWbPort          = 0,
      intWbPriority      = 0,
    ),
    exu(
      "MUL0",
      1,
      AnsIntScheduler,
      Seq(
        fixed(FuType.mul, 2, "mul"),
        fixed(FuType.bku, 2, "bku"),
        uncertain(FuType.div, "div"),
        uncertain(FuType.csr, "csr"),
        uncertain(FuType.fence, "fence", writesInt = false),
      ),
      intRdPorts         = Seq(4, 5, -1),
      intRdPriority      = Seq(1, 1, 0),
      intWbPort          = 1,
      intWbPriority      = 1,
      uncertainIntWbPort = 3,
    ),
    exu(
      "ALU1",
      2,
      AnsIntScheduler,
      Seq(fixed(FuType.alu, 0, "alu")),
      intRdPorts         = Seq(2, 3, -1),
      intRdPriority      = Seq(0, 0, 0),
      intWbPort          = 1,
      intWbPriority      = 0,
    ),
    exu(
      "MUL1",
      3,
      AnsIntScheduler,
      Seq(
        fixed(FuType.mul, 2, "mul"),
        fixed(FuType.bku, 2, "bku"),
      ),
      intRdPorts         = Seq(0, 1, -1),
      intRdPriority      = Seq(1, 1, 0),
      intWbPort          = 2,
      intWbPriority      = 1,
    ),
    exu(
      "ALU2",
      4,
      AnsIntScheduler,
      Seq(fixed(FuType.alu, 0, "alu")),
      intRdPorts         = Seq(4, 5, -1),
      intRdPriority      = Seq(0, 0, 0),
      intWbPort          = 2,
      intWbPriority      = 0,
    ),
    exu(
      "BJU0",
      5,
      AnsIntScheduler,
      Seq(fixed(FuType.brh, 0, "brh"), fixed(FuType.jmp, 0, "jmp")),
      intRdPorts         = Seq(2, 3, -1),
      intRdPriority      = Seq(1, 1, 0),
      intWbPort          = 0,
      intWbPriority      = 1,
      hasRedirect        = true,
      needPc             = true,
    ),
  )

  val fpExus: Seq[AnsExuParams] = Seq(
    exu(
      "FEX0",
      6,
      AnsFpScheduler,
      Seq(
        fixed(FuType.falu, 1, "falu", writesInt = true, writesFp  = true),
        fixed(FuType.fmac, 3, "fmac", writesInt = false, writesFp = true),
        fixed(FuType.fcvt, 2, "fcvt", writesInt = true, writesFp  = true),
      ),
      intRdPorts    = Seq(0, -1, -1),
      intRdPriority = Seq(2, 0, 0),
      fpRdPorts     = Seq(0, 1, 2),
      fpRdPriority  = Seq(0, 0, 0),
      intWbPort     = 1,
      intWbPriority = 2,
      fpWbPort      = 0,
      fpWbPriority  = 0,
    ),
    exu(
      "FEX1",
      7,
      AnsFpScheduler,
      Seq(uncertain(FuType.fdiv, "fdiv", writesInt = false, writesFp = true)),
      fpRdPorts     = Seq(3, 4, -1),
      fpRdPriority  = Seq(0, 0, 0),
      fpWbPort      = 1,
      fpWbPriority  = 0,
    ),
    exu(
      "FEX2",
      8,
      AnsFpScheduler,
      Seq(
        fixed(FuType.falu, 1, "falu", writesInt = true, writesFp  = true),
        fixed(FuType.fmac, 3, "fmac", writesInt = false, writesFp = true),
      ),
      fpRdPorts     = Seq(0, 1, 2),
      fpRdPriority  = Seq(1, 1, 1),
      intWbPort     = 2,
      intWbPriority = 3,
      fpWbPort      = 2,
      fpWbPriority  = 0,
    ),
  )

  // Load/store units live in MemBlock.  Their result path is the LSU feedback,
  // so they do not enter the PRF write ports of DataPath in this bring-up.
  val memExus: Seq[AnsExuParams] = Seq(
    exu(
      "LDU0",
      9,
      AnsMemScheduler,
      Seq(uncertain(FuType.ldu, "ldu", writesInt = true, writesFp = true)),
      intRdPorts    = Seq(6, -1, -1),
      intRdPriority = Seq(0, 0, 0),
      isLoad        = true,
    ),
    exu(
      "LDU1",
      10,
      AnsMemScheduler,
      Seq(uncertain(FuType.ldu, "ldu", writesInt = true, writesFp = true)),
      intRdPorts    = Seq(7, -1, -1),
      intRdPriority = Seq(0, 0, 0),
      isLoad        = true,
    ),
    exu(
      "STA0",
      11,
      AnsMemScheduler,
      Seq(
        uncertain(FuType.stu, "stu", writesInt = false, writesFp = false),
        uncertain(FuType.mou, "mou", writesInt = false, writesFp = false),
      ),
      intRdPorts    = Seq(3, -1, -1),
      intRdPriority = Seq(2, 0, 0),
      isStore       = true,
    ),
    exu(
      "STD0",
      12,
      AnsMemScheduler,
      Seq(
        uncertain(FuType.stu, "stu", writesInt = false, writesFp = false),
        uncertain(FuType.mou, "mou", writesInt = false, writesFp = false),
      ),
      intRdPorts    = Seq(5, -1, -1),
      intRdPriority = Seq(2, 0, 0),
      fpRdPorts     = Seq(5, -1, -1),
      fpRdPriority  = Seq(0, 0, 0),
      isStore       = true,
    ),
  )

  val allExus: Seq[AnsExuParams] = intExus ++ fpExus ++ memExus

  require(allExus.map(_.exuIdx).distinct.size == allExus.size, "ExeUnit indices must be unique")
  require(allExus.map(_.exuIdx) == allExus.indices, "ExeUnit indices must be contiguous and in port order")
  require(allExus.map(_.name).distinct.size == allExus.size, "ExeUnit names must be unique")

  def exuByIndex(idx: Int): AnsExuParams = allExus
    .find(_.exuIdx == idx)
    .getOrElse(
      throw new IllegalArgumentException(s"unknown exuIdx $idx")
    )

  def exuIdxOf(name: String): Int = allExus
    .find(_.name == name)
    .getOrElse(
      throw new IllegalArgumentException(s"unknown exu name $name")
    )
    .exuIdx

  // ---- IQ composition (order matches AnsDispatchParams.iqList) ----
  // 整数 IQ 推测唤醒只由整数执行单元产生。FEX0/FEX2 的比较/分类及 F2I 整数结果按固定时刻
  // 写回 IntWB1/IntWB2，但只产生实际 WB 唤醒，不进入整数 IQ 的推测唤醒；
  // FEX0/FEX2 写 FP PRF 的结果继续作为 FP IQ 的推测唤醒源。
  private val intWakeSrc: Seq[Int] =
    allExus.filter(e => e.isIQWakeUpSource && e.writesInt && e.isIntExeUnit).map(_.exuIdx).sorted
  private val fpWakeSrc: Seq[Int]  =
    allExus.filter(e => e.isIQWakeUpSource && e.writesFp && e.isFpExeUnit).map(_.exuIdx).sorted
  private val memWakeSrc: Seq[Int] = Seq.empty // load wakeup (LSU) not yet wired

  // 整数目的寄存器只会被 Int ExeUnit 或 load 写；浮点同理。
  private val intWakeAll: Seq[Int] = (intWakeSrc ++ memWakeSrc).distinct.sorted
  private val fpWakeAll: Seq[Int]  = (fpWakeSrc ++ memWakeSrc).distinct.sorted
  // 访存指令的源可以是整数或浮点，必须接收两类唤醒。
  private val memWakeAll: Seq[Int] = (intWakeSrc ++ fpWakeSrc ++ memWakeSrc).distinct.sorted

  // 四宽分派的容量基线：Int 3×20 + Fp 2×16 + Mem 4×16 = 156 项。
  // numEntries 包含每个 IQ 的 2 个 EnqEntry；Dispatch 参数由此处派生。
  // 整数 IQ：ALU0+MUL0、ALU1+MUL1、ALU2+BJU0；同 IQ 两个出口读写口不相交。
  val intScheduler: AnsSchedulerParams = AnsSchedulerParams(
    AnsIntScheduler,
    Seq(
      AnsIssueQueueParams(
        name               = "Int-IQ0",
        iqIdx              = 0,
        schdType           = AnsIntScheduler,
        exus               = Seq(intExus(0), intExus(1)),
        numEntries         = 20,
        numEnq             = 2,
        wakeupSourceExuIdx = intWakeAll,
        isFp               = false,
        isMem              = false,
      ),
      AnsIssueQueueParams(
        name               = "Int-IQ1",
        iqIdx              = 1,
        schdType           = AnsIntScheduler,
        exus               = Seq(intExus(2), intExus(3)),
        numEntries         = 20,
        numEnq             = 2,
        wakeupSourceExuIdx = intWakeAll,
        isFp               = false,
        isMem              = false,
      ),
      AnsIssueQueueParams(
        name               = "Int-IQ2",
        iqIdx              = 2,
        schdType           = AnsIntScheduler,
        exus               = Seq(intExus(4), intExus(5)),
        numEntries         = 20,
        numEnq             = 2,
        wakeupSourceExuIdx = intWakeAll,
        isFp               = false,
        isMem              = false,
      ),
    ),
  )

  val fpScheduler: AnsSchedulerParams = AnsSchedulerParams(
    AnsFpScheduler,
    Seq(
      AnsIssueQueueParams(
        name               = "FP-IQ0",
        iqIdx              = 3,
        schdType           = AnsFpScheduler,
        exus               = Seq(fpExus(0), fpExus(1)),
        numEntries         = 16,
        numEnq             = 2,
        wakeupSourceExuIdx = fpWakeAll,
        isFp               = true,
        isMem              = false,
      ),
      AnsIssueQueueParams(
        name               = "FP-IQ1",
        iqIdx              = 4,
        schdType           = AnsFpScheduler,
        exus               = Seq(fpExus(2)),
        numEntries         = 16,
        numEnq             = 2,
        wakeupSourceExuIdx = fpWakeAll,
        isFp               = true,
        isMem              = false,
      ),
    ),
  )

  val memScheduler: AnsSchedulerParams = AnsSchedulerParams(
    AnsMemScheduler,
    Seq(
      AnsIssueQueueParams(
        name               = "LDU0",
        iqIdx              = 5,
        schdType           = AnsMemScheduler,
        exus               = Seq(memExus(0)),
        numEntries         = 16,
        numEnq             = 2,
        wakeupSourceExuIdx = memWakeAll,
        isFp               = false,
        isMem              = true,
      ),
      AnsIssueQueueParams(
        name               = "LDU1",
        iqIdx              = 6,
        schdType           = AnsMemScheduler,
        exus               = Seq(memExus(1)),
        numEntries         = 16,
        numEnq             = 2,
        wakeupSourceExuIdx = memWakeAll,
        isFp               = false,
        isMem              = true,
      ),
      AnsIssueQueueParams(
        name               = "STA0",
        iqIdx              = 7,
        schdType           = AnsMemScheduler,
        exus               = Seq(memExus(2)),
        numEntries         = 16,
        numEnq             = 2,
        wakeupSourceExuIdx = memWakeAll,
        isFp               = false,
        isMem              = true,
      ),
      // STD0 is fed by AnsSchedulerMem from STA0, not by Dispatch.
      AnsIssueQueueParams(
        name               = "STD0",
        iqIdx              = 8,
        schdType           = AnsMemScheduler,
        exus               = Seq(memExus(3)),
        numEntries         = 16,
        numEnq             = 2,
        wakeupSourceExuIdx = memWakeAll,
        isFp               = false,
        isMem              = true,
      ),
    ),
  )

  val allSchedulers: Seq[AnsSchedulerParams] = Seq(intScheduler, fpScheduler, memScheduler)

  val allIQs: Seq[AnsIssueQueueParams] = allSchedulers.flatMap(_.iqs)

  require(allIQs.map(_.iqIdx).distinct.size == allIQs.size, "global IQ indices must be unique")
  require(allIQs.map(_.name).distinct.size == allIQs.size, "global IQ names must be unique")
  require(allIQs.flatMap(_.exus) == allExus, "IQ topology must cover each EXU once in port order")

  /** 由 Dispatch 直接入队的 IQ（复制目的 IQ 除外），顺序即 AnsDispatchParams.iqList。 */
  val dispatchIQs: Seq[AnsIssueQueueParams] = allSchedulers.flatMap(_.dispatchIQs)

  require(dispatchIQs.size == 8, "Dispatch expects 8 issue queues")
  require(
    dispatchIQs.map(_.name).mkString(",") == "Int-IQ0,Int-IQ1,Int-IQ2,FP-IQ0,FP-IQ1,LDU0,LDU1,STA0",
    "IQ order must match AnsDispatchParams.iqList",
  )

  // ---- global counts ----
  val nExu: Int                    = allExus.size
  require(allExus.count(_.splitUncertain) == 1, "one shared scalar issue owner is supported")
  val uncertainOwner: AnsExuParams = allExus.find(_.splitUncertain).get
  val nUncertainGroups: Int        = uncertainOwner.uncertainGroups.size
  val nCompletion: Int             = nExu + 1
  val uncertainCompletionIdx: Int  = nExu
  val uncertainIntWbPort: Int      = uncertainOwner.uncertainIntWbPort
  val nIntWb: Int                  = 6
  val nFpWb: Int                   = 5
  val nIntRd: Int                  = allExus.flatMap(_.intRdPorts).foldLeft(-1)(_ max _) + 1
  val nFpRd: Int                   = allExus.flatMap(_.fpRdPorts).foldLeft(-1)(_ max _) + 1
  val intWakeUpExuIdx: Seq[Int]    = intWakeSrc
  val fpWakeUpExuIdx: Seq[Int]     = fpWakeSrc
  val memWakeUpExuIdx: Seq[Int]    = memWakeSrc
  val nIntWakeUp: Int              = intWakeUpExuIdx.size
  val nFpWakeUp: Int               = fpWakeUpExuIdx.size
  val nMemWakeUp: Int              = memWakeUpExuIdx.size
  val loadPipelineWidth: Int       = memExus.count(_.isLoad)

  val maxEntries: Int      = allIQs.map(_.numEntries).max
  val iqValidNumWidth: Int = log2Ceil(maxEntries + 1)

  // data-path exus: everyone except pure memory units (they go to MemBlock).
  val datapathExus: Seq[AnsExuParams] = allExus.filterNot(e => e.isLoad || e.isStore)
  val memExuParams: Seq[AnsExuParams] = allExus.filter(e => e.isLoad || e.isStore)

  /** The merged completion port has no arbiter with ordinary EXU or reserved LSU writes. */
  def validateCompletionTopology(exus: Seq[AnsExuParams]): Unit = {
    val owners = exus.filter(_.splitUncertain)
    require(owners.size == 1, "one shared scalar issue owner is supported")
    val port   = owners.head.uncertainIntWbPort
    require(port >= 0 && port < nIntWb, "uncertain completion port out of range")
    require(exus.forall(_.intWbPort != port), "uncertain completion write port must be globally exclusive")
    require(!Seq(4, 5).contains(port), "uncertain completion must not use reserved load write ports")
  }
  validateCompletionTopology(allExus)

  def validate(params: BundleParams): Unit = {
    require(nExu <= params.maxExuCount, "EXU topology exceeds source encoding capacity")
    require(loadPipelineWidth == params.loadPipelineWidth, "load dependency lanes differ from load topology")
    allSchedulers.foreach(_.validate(params))
  }

  def iqByName(name: String): AnsIssueQueueParams = allIQs
    .find(_.name == name)
    .getOrElse(
      throw new IllegalArgumentException(s"unknown IQ $name")
    )
}
