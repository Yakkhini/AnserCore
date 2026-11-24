/* Dispatch 静态参数：IQ 组成、fuType 路由、LSQ 深度与唤醒/写回端口数。
 *
 * 位置见设计文档 §4.1/§5.3；IQ 数量按项目裁决收敛为 Int3 + Fp2 + Mem3，
 * STD0 由 MemScheduler 同拍复制，因此不占 Dispatch 入队口。
 * 每个 IQ 有 2 个入队口：IQ 数量（IQNum）与入队端口总数（IQEnqSum）是两回事，
 * 负载均衡的候选是出队口（exuIdx），先选出 EXU 再映射回它所属的 IQ。
 */
package ansercore.backend.dispatch

import chisel3.util.log2Up

import ansercore.backend.Bundles.FuType
import ansercore.backend.issue.AnsIssueParams

/** 单个 Issue Queue 的静态属性。 */
final case class AnsIQParams(
  name:       String,
  fuTypes:    Seq[Int],
  numEntries: Int,
  numEnq:     Int     = 2,
  numDeq:     Int     = 1,
  isFp:       Boolean = false,
  isMem:      Boolean = false,
) {
  require(fuTypes.nonEmpty, s"$name must accept at least one fuType")
  require(numEntries > 0 && numEnq > 0 && numDeq > 0, s"$name has invalid dimensions")
}

/** 一组共享同一候选出队口（EXU）集合的 fuType；用于负载均衡。 */
final case class AnsIQGroup(fuTypes: Seq[Int], exuIdx: Seq[Int]) {
  require(fuTypes.nonEmpty && exuIdx.nonEmpty, "IQ group must not be empty")
  require(exuIdx.distinct.size == exuIdx.size, "IQ group exu indices must be unique")
}

object AnsDispatchParams {
  // IQ 端口顺序固定：Int IQ0~2、Fp IQ0~1、LDU0/LDU1/STA0。
  val intIQ0 = 0
  val intIQ1 = 1
  val intIQ2 = 2
  val fpIQ0  = 3
  val fpIQ1  = 4
  val ldu0   = 5
  val ldu1   = 6
  val sta0   = 7

  // IQ 拓扑、容量和端口数以 Issue 参数为唯一来源，避免两份配置漂移。
  val iqList: Seq[AnsIQParams] = AnsIssueParams.dispatchIQs.map { iq =>
    AnsIQParams(
      name       = iq.name,
      fuTypes    = iq.fuTypes,
      numEntries = iq.numEntries,
      numEnq     = iq.numEnq,
      numDeq     = iq.numDeq,
      isFp       = iq.isFp,
      isMem      = iq.isMem,
    )
  }

  // 全局 EXU 编号；以 AnsIssueParams 为唯一来源。
  val exuALU0: Int = AnsIssueParams.exuIdxOf("ALU0")
  val exuMUL0: Int = AnsIssueParams.exuIdxOf("MUL0")
  val exuALU1: Int = AnsIssueParams.exuIdxOf("ALU1")
  val exuMUL1: Int = AnsIssueParams.exuIdxOf("MUL1")
  val exuALU2: Int = AnsIssueParams.exuIdxOf("ALU2")
  val exuBJU0: Int = AnsIssueParams.exuIdxOf("BJU0")
  val exuFEX0: Int = AnsIssueParams.exuIdxOf("FEX0")
  val exuFEX1: Int = AnsIssueParams.exuIdxOf("FEX1")
  val exuFEX2: Int = AnsIssueParams.exuIdxOf("FEX2")
  val exuLDU0: Int = AnsIssueParams.exuIdxOf("LDU0")
  val exuLDU1: Int = AnsIssueParams.exuIdxOf("LDU1")
  val exuSTA0: Int = AnsIssueParams.exuIdxOf("STA0")

  /** IQ 个数；与入队端口总数 IQEnqSum 区分开。 */
  val IQNum: Int          = iqList.size
  val IQEnqSum: Int       = iqList.map(_.numEnq).sum
  val iqEnqBase: Seq[Int] = iqList.scanLeft(0)(_ + _.numEnq).init

  def iqEnqPort(iq: Int, slot: Int = 0): Int = iqEnqBase(iq) + slot

  /** exuIdx -> Dispatch 视角的 IQ 索引（与 AnsIssueParams.dispatchIQs 顺序一致）。 */
  val exuToIQ: Map[Int, Int] = AnsIssueParams.dispatchIQs.zipWithIndex.flatMap { case (iq, i) =>
    iq.exus.map(_.exuIdx -> i)
  }.toMap

  require(
    iqList.map(_.name) == AnsIssueParams.dispatchIQs.map(_.name),
    "AnsDispatchParams.iqList must match AnsIssueParams.dispatchIQs",
  )
  require(
    iqList.map(_.numEnq) == AnsIssueParams.dispatchIQs.map(_.numEnq),
    "AnsDispatchParams.iqList enq widths must match AnsIssueParams.dispatchIQs",
  )

  val iqValidNumWidth: Int = log2Up(iqList.map(_.numEntries).max + 1)

  /** fuType 可落到多个出队口时，按出队口负载做均衡。 */
  val multiIQGroups: Seq[AnsIQGroup] = Seq(
    AnsIQGroup(Seq(FuType.alu), Seq(exuALU0, exuALU1, exuALU2)),
    AnsIQGroup(Seq(FuType.mul, FuType.bku), Seq(exuMUL0, exuMUL1)),
    AnsIQGroup(Seq(FuType.falu, FuType.fmac), Seq(exuFEX0, exuFEX2)),
    AnsIQGroup(Seq(FuType.ldu), Seq(exuLDU0, exuLDU1)),
  )

  /** 仅有一个合法出队口的 fuType。 */
  val singleIQ: Seq[(Int, Int)] = Seq(
    FuType.brh   -> exuBJU0,
    FuType.jmp   -> exuBJU0,
    FuType.div   -> exuMUL0,
    FuType.csr   -> exuMUL0,
    FuType.fence -> exuMUL0,
    FuType.fcvt  -> exuFEX0,
    FuType.fdiv  -> exuFEX1,
    FuType.stu   -> exuSTA0,
    FuType.mou   -> exuSTA0,
  )

  for {
    (group, g) <- multiIQGroups.zipWithIndex
    e          <- group.exuIdx
  } {
    require(
      AnsIssueParams.exuByIndex(e).fuTypes.toSet.intersect(group.fuTypes.toSet).nonEmpty,
      s"IQ group $g candidate EXU $e does not accept ${group.fuTypes}",
    )
  }
  for ((fu, e) <- singleIQ) {
    require(
      AnsIssueParams.exuByIndex(e).fuTypes.contains(fu),
      s"single-IQ mapping for fuType $fu points to an incompatible EXU $e",
    )
  }

  // LSQ shadow 深度；MemBlock 文档确定后在此同步。
  val lqEntries: Int = 72
  val sqEntries: Int = 64

  // 写回与唤醒端口数。拓扑以 AnsIssueParams 为唯一来源。
  val nIntWb: Int         = AnsIssueParams.nIntWb
  val nFpWb: Int          = AnsIssueParams.nFpWb
  val nIntWakeUp: Int     = AnsIssueParams.nIntWakeUp
  val nFpWakeUp: Int      = AnsIssueParams.nFpWakeUp
  val nMemWakeUp: Int     = AnsIssueParams.nMemWakeUp
  val nRegCacheWrite: Int = nIntWakeUp + nMemWakeUp

  // 执行单元总数：Int 6 + Fp 3 + Mem 4（LDU0/LDU1/STA0/STD0）。
  val nExu: Int = AnsIssueParams.nExu
}
