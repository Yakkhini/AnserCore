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

final case class AnsUncertainLatency() extends AnsFuLatency {
  def value: Option[Int] = None
}

final case class AnsFuConfig(
  name:       String,
  fuType:     Int,
  latency:    AnsFuLatency,
  writesData: Boolean = true,
)

/** 一个 ExeUnit 的静态属性。
  *
  * `intRdPorts` / `fpRdPorts` 按源槽给出 PRF 读口编号（-1 表示该槽不读对应寄存器堆）。 `intWbPort` / `fpWbPort` 是写回端口编号，-1 表示不写回。
  */
final case class AnsExuParams(
  name:        String,
  exuIdx:      Int,
  schdType:    AnsSchedulerType,
  fuConfigs:   Seq[AnsFuConfig],
  intRdPorts:  Seq[Int],
  fpRdPorts:   Seq[Int],
  intWbPort:   Int     = -1,
  fpWbPort:    Int     = -1,
  numRegSrc:   Int     = 3,
  isLoad:      Boolean = false,
  isStore:     Boolean = false,
  hasRedirect: Boolean = false,
  needPc:      Boolean = false,
) {
  require(fuConfigs.nonEmpty, s"$name must contain at least one FU")
  require(intRdPorts.length == numRegSrc && fpRdPorts.length == numRegSrc, s"$name read-port count mismatch")

  def fuTypes: Seq[Int] = fuConfigs.map(_.fuType).distinct

  /** 固定延迟 FUs；不确定延迟 FUs 不进推测唤醒和固定延迟忙表。 */
  def fuLatencyMap: Map[Int, Int] =
    fuConfigs.collect { case cfg if cfg.latency.value.nonEmpty => cfg.fuType -> cfg.latency.value.get }.toMap

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

  /** 该 EXU 的写回域/端口（用于 DataPath 和 WbFuBusyTable）。 */
  def intWbLatencyMap: Map[Int, Int] =
    if (writesInt)
      fuConfigs
        .filter(_.writesData)
        .collect { case c if c.latency.value.nonEmpty => c.fuType -> c.latency.value.get }
        .toMap
    else Map()

  def fpWbLatencyMap: Map[Int, Int] =
    if (writesFp)
      fuConfigs
        .filter(_.writesData)
        .collect { case c if c.latency.value.nonEmpty => c.fuType -> c.latency.value.get }
        .toMap
    else Map()

  def isIntLatencyCertain: Boolean =
    !writesInt || fuConfigs.filter(_.writesData).forall(_.latency.value.nonEmpty)

  def isFpLatencyCertain: Boolean =
    !writesFp || fuConfigs.filter(_.writesData).forall(_.latency.value.nonEmpty)
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
  require(exus.size == numEnq || exus.size <= 2, s"$name supports at most two dequeue ports")
  // 多出口 IQ 的 FU 集合必须互斥，否则同一 entry 可能被两个出口同时选中。
  require(
    exus.combinations(2).forall { case Seq(a, b) => a.fuTypes.toSet.intersect(b.fuTypes.toSet).isEmpty },
    s"$name dequeue ports must accept disjoint FU sets",
  )

  def numDeq: Int = exus.size

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

  /** Dispatch 可见的入队口数量；STD0 由 MemScheduler 复制，不占 Dispatch 端口。 */
  def dispatchEnqPorts: Int = iqs.filterNot(_.name == "STD0").map(_.numEnq).sum

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
  private def fixed(fuType: Int, cycles: Int, name: String): AnsFuConfig =
    AnsFuConfig(name, fuType, AnsCertainLatency(cycles))

  private def uncertain(fuType: Int, name: String): AnsFuConfig =
    AnsFuConfig(name, fuType, AnsUncertainLatency())

  private def exu(
    name:               String,
    exuIdx:             Int,
    schdType:           AnsSchedulerType,
    fuConfigs:          Seq[AnsFuConfig],
    intRdPorts:         Seq[Int] = Seq(-1, -1, -1),
    fpRdPorts:          Seq[Int] = Seq(-1, -1, -1),
    intWbPort:          Int      = -1,
    fpWbPort:           Int      = -1,
    isLoad:             Boolean  = false,
    isStore:            Boolean  = false,
    hasRedirect:        Boolean  = false,
    needPc:             Boolean  = false,
  ): AnsExuParams =
    AnsExuParams(
      name        = name,
      exuIdx      = exuIdx,
      schdType    = schdType,
      fuConfigs   = fuConfigs,
      intRdPorts  = intRdPorts,
      fpRdPorts   = fpRdPorts,
      intWbPort   = intWbPort,
      fpWbPort    = fpWbPort,
      isLoad      = isLoad,
      isStore     = isStore,
      hasRedirect = hasRedirect,
      needPc      = needPc,
    )

  // Global ExeUnit order follows the issue-port order below.  Keep it stable:
  // Scheduler wakeup vectors and DataPath bypass data are indexed by exuIdx.
  val intExus: Seq[AnsExuParams] = Seq(
    exu(
      "ALU0",
      0,
      AnsIntScheduler,
      Seq(fixed(FuType.alu, 0, "alu"), fixed(FuType.mul, 2, "mul"), fixed(FuType.bku, 2, "bku")),
      intRdPorts  = Seq(0, 1, -1),
      intWbPort   = 0,
    ),
    exu(
      "BJU0",
      1,
      AnsIntScheduler,
      Seq(fixed(FuType.brh, 0, "brh"), fixed(FuType.jmp, 0, "jmp")),
      intRdPorts  = Seq(2, 3, -1),
      intWbPort   = 0,
      hasRedirect = true,
      needPc      = true,
    ),
    exu(
      "ALU1",
      2,
      AnsIntScheduler,
      Seq(fixed(FuType.alu, 0, "alu"), fixed(FuType.mul, 2, "mul"), fixed(FuType.bku, 2, "bku")),
      intRdPorts  = Seq(4, 5, -1),
      intWbPort   = 1,
    ),
    exu(
      "BJU1",
      3,
      AnsIntScheduler,
      Seq(fixed(FuType.brh, 0, "brh"), fixed(FuType.jmp, 0, "jmp")),
      intRdPorts  = Seq(6, 7, -1),
      intWbPort   = 1,
      hasRedirect = true,
      needPc      = true,
    ),
    exu(
      "ALU2",
      4,
      AnsIntScheduler,
      Seq(fixed(FuType.alu, 0, "alu")),
      intRdPorts  = Seq(8, 9, -1),
      intWbPort   = 2,
    ),
    exu(
      "CSR0",
      5,
      AnsIntScheduler,
      Seq(
        fixed(FuType.brh, 0, "brh"),
        fixed(FuType.jmp, 0, "jmp"),
        uncertain(FuType.csr, "csr"),
        uncertain(FuType.fence, "fence"),
        uncertain(FuType.div, "div"),
      ),
      intRdPorts  = Seq(0, 1, -1),
      intWbPort   = 3,
      hasRedirect = true,
      needPc      = true,
    ),
  )

  val fpExus: Seq[AnsExuParams] = Seq(
    exu(
      "FEX0",
      6,
      AnsFpScheduler,
      Seq(fixed(FuType.falu, 1, "falu"), fixed(FuType.fmac, 3, "fmac"), fixed(FuType.fcvt, 2, "fcvt")),
      intRdPorts = Seq(0, 1, -1),
      fpRdPorts  = Seq(0, 1, 2),
      fpWbPort   = 0,
    ),
    exu(
      "FEX1",
      7,
      AnsFpScheduler,
      Seq(uncertain(FuType.fdiv, "fdiv")),
      fpRdPorts  = Seq(3, 4, -1),
      fpWbPort   = 1,
    ),
    exu(
      "FEX2",
      8,
      AnsFpScheduler,
      Seq(fixed(FuType.falu, 1, "falu"), fixed(FuType.fmac, 3, "fmac")),
      fpRdPorts  = Seq(0, 1, 2),
      fpWbPort   = 2,
    ),
  )

  // Load/store units live in MemBlock.  Their result path is the LSU feedback,
  // so they do not enter the PRF write ports of DataPath in this bring-up.
  val memExus: Seq[AnsExuParams] = Seq(
    exu("LDU0", 9, AnsMemScheduler, Seq(uncertain(FuType.ldu, "ldu")), intRdPorts  = Seq(8, -1, -1), isLoad = true),
    exu("LDU1", 10, AnsMemScheduler, Seq(uncertain(FuType.ldu, "ldu")), intRdPorts = Seq(9, -1, -1), isLoad = true),
    exu(
      "STA0",
      11,
      AnsMemScheduler,
      Seq(uncertain(FuType.stu, "stu"), uncertain(FuType.mou, "mou")),
      intRdPorts                                                                   = Seq(6, 7, -1),
      isStore                                                                      = true,
    ),
    exu(
      "STD0",
      12,
      AnsMemScheduler,
      Seq(uncertain(FuType.stu, "stu"), uncertain(FuType.mou, "mou")),
      intRdPorts                                                                   = Seq(5, -1, -1),
      fpRdPorts                                                                    = Seq(5, -1, -1),
      isStore                                                                      = true,
    ),
  )

  val allExus: Seq[AnsExuParams] = intExus ++ fpExus ++ memExus

  require(allExus.map(_.exuIdx).distinct.size == allExus.size, "ExeUnit indices must be unique")

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
  private val intWakeSrc: Seq[Int] = intExus.filter(_.isIQWakeUpSource).map(_.exuIdx)
  private val fpWakeSrc: Seq[Int]  = fpExus.filter(_.isIQWakeUpSource).map(_.exuIdx)
  private val memWakeSrc: Seq[Int] = Seq.empty // load wakeup (LSU) not yet wired

  // 整数目的寄存器只会被 Int ExeUnit 或 load 写；浮点同理。
  private val intWakeAll: Seq[Int] = (intWakeSrc ++ memWakeSrc).distinct.sorted
  private val fpWakeAll: Seq[Int]  = (fpWakeSrc ++ memWakeSrc).distinct.sorted
  // 访存指令的源可以是整数或浮点，必须接收两类唤醒。
  private val memWakeAll: Seq[Int] = (intWakeSrc ++ fpWakeSrc ++ memWakeSrc).distinct.sorted

  val intScheduler: AnsSchedulerParams = AnsSchedulerParams(
    AnsIntScheduler,
    Seq(
      AnsIssueQueueParams(
        "Int-IQ0",
        0,
        AnsIntScheduler,
        Seq(intExus(0), intExus(1)),
        24,
        2,
        intWakeAll,
        isFp  = false,
        isMem = false,
      ),
      AnsIssueQueueParams(
        "Int-IQ1",
        1,
        AnsIntScheduler,
        Seq(intExus(2), intExus(3)),
        24,
        2,
        intWakeAll,
        isFp  = false,
        isMem = false,
      ),
      AnsIssueQueueParams(
        "Int-IQ2",
        2,
        AnsIntScheduler,
        Seq(intExus(4), intExus(5)),
        24,
        2,
        intWakeAll,
        isFp  = false,
        isMem = false,
      ),
    ),
  )

  val fpScheduler: AnsSchedulerParams = AnsSchedulerParams(
    AnsFpScheduler,
    Seq(
      AnsIssueQueueParams(
        "FP-IQ0",
        3,
        AnsFpScheduler,
        Seq(fpExus(0), fpExus(1)),
        18,
        2,
        fpWakeAll,
        isFp                                                                                  = true,
        isMem                                                                                 = false,
      ),
      AnsIssueQueueParams("FP-IQ1", 4, AnsFpScheduler, Seq(fpExus(2)), 18, 2, fpWakeAll, isFp = true, isMem = false),
    ),
  )

  val memScheduler: AnsSchedulerParams = AnsSchedulerParams(
    AnsMemScheduler,
    Seq(
      AnsIssueQueueParams("LDU0", 5, AnsMemScheduler, Seq(memExus(0)), 16, 2, memWakeAll, isFp = false, isMem = true),
      AnsIssueQueueParams("LDU1", 6, AnsMemScheduler, Seq(memExus(1)), 16, 2, memWakeAll, isFp = false, isMem = true),
      AnsIssueQueueParams("STA0", 7, AnsMemScheduler, Seq(memExus(2)), 16, 2, memWakeAll, isFp = false, isMem = true),
      // STD0 is fed by the MemScheduler, not by Dispatch.
      AnsIssueQueueParams("STD0", 8, AnsMemScheduler, Seq(memExus(3)), 16, 2, memWakeAll, isFp = false, isMem = true),
    ),
  )

  val allSchedulers: Seq[AnsSchedulerParams] = Seq(intScheduler, fpScheduler, memScheduler)

  val allIQs: Seq[AnsIssueQueueParams] = allSchedulers.flatMap(_.iqs)

  /** 由 Dispatch 直接入队的 IQ（STD0 除外），顺序即 AnsDispatchParams.iqList。 */
  val dispatchIQs: Seq[AnsIssueQueueParams] = allIQs.filterNot(_.name == "STD0")

  require(dispatchIQs.size == 8, "Dispatch expects 8 issue queues")
  require(
    dispatchIQs.map(_.name).mkString(",") == "Int-IQ0,Int-IQ1,Int-IQ2,FP-IQ0,FP-IQ1,LDU0,LDU1,STA0",
    "IQ order must match AnsDispatchParams.iqList",
  )

  // ---- global counts ----
  val nExu: Int                 = allExus.size
  val nIntWb: Int               = 4
  val nFpWb: Int                = 4
  val intWakeUpExuIdx: Seq[Int] = intWakeSrc
  val fpWakeUpExuIdx: Seq[Int]  = fpWakeSrc
  val memWakeUpExuIdx: Seq[Int] = memWakeSrc
  val nIntWakeUp: Int           = intWakeUpExuIdx.size
  val nFpWakeUp: Int            = fpWakeUpExuIdx.size
  val nMemWakeUp: Int           = memWakeUpExuIdx.size
  val loadPipelineWidth: Int    = memExus.count(_.isLoad)

  val maxEntries: Int      = allIQs.map(_.numEntries).max
  val iqValidNumWidth: Int = log2Ceil(maxEntries + 1)

  // data-path exus: everyone except pure memory units (they go to MemBlock).
  val datapathExus: Seq[AnsExuParams] = allExus.filterNot(e => e.isLoad || e.isStore)
  val memExuParams: Seq[AnsExuParams] = allExus.filter(e => e.isLoad || e.isStore)

  def iqByName(name: String): AnsIssueQueueParams = allIQs
    .find(_.name == name)
    .getOrElse(
      throw new IllegalArgumentException(s"unknown IQ $name")
    )
}
