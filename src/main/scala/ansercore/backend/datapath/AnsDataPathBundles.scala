/* DataPath 域的接口 bundle 与静态端口映射。
 *
 * DataPath 从 issue 包独立出来后，只通过本文件的 bundle 与 Scheduler / EXU /
 * WbDataPath 交互。端口分组全部由 `AnsIssueParams` 的 exu 配置派生，不新增硬件。
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.issue.{AnsExuParams, AnsIssueParams}
import ansercore.backend.rename.AnsRenameRedirect

/** 一个 EXU 的某个源槽使用哪个物理 PRF 读口，以及该源在该口上的优先级。 */
final case class AnsReadPortMember(exuIdx: Int, srcIdx: Int, portIdx: Int, priority: Int)

object AnsDataPathParams {

  /** 按 port 分组的读口成员；组内顺序为 (priority, exuIdx, srcIdx)。 */
  def readPortGroups(
    portsOf:    AnsExuParams => Seq[Int],
    priorityOf: AnsExuParams => Seq[Int],
  ): Map[Int, Seq[AnsReadPortMember]] =
    AnsIssueParams.allExus
      .flatMap { exu =>
        portsOf(exu).zip(priorityOf(exu)).zipWithIndex.collect {
          case ((port, priority), src) if port >= 0 =>
            AnsReadPortMember(exu.exuIdx, src, port, priority)
        }
      }
      .groupBy(_.portIdx)
      .view
      .mapValues(_.sortBy(m => (m.priority, m.exuIdx, m.srcIdx)))
      .toMap

  val intRdGroups: Map[Int, Seq[AnsReadPortMember]] = readPortGroups(_.intRdPorts, _.intRdPriority)
  val fpRdGroups: Map[Int, Seq[AnsReadPortMember]]  = readPortGroups(_.fpRdPorts, _.fpRdPriority)

  /** 按物理写回口分组的 EXU；组内按 (priority, exuIdx) 固定优先级。 */
  def wbGroups(isInt: Boolean): Map[Int, Seq[AnsExuParams]] =
    AnsIssueParams.allExus
      .filter(exu => (if (isInt) exu.intWbPort else exu.fpWbPort) >= 0)
      .groupBy(exu => if (isInt) exu.intWbPort else exu.fpWbPort)
      .view
      .mapValues(_.sortBy(exu => (if (isInt) exu.intWbPriority else exu.fpWbPriority, exu.exuIdx)))
      .toMap

  val intWbGroups: Map[Int, Seq[AnsExuParams]] = wbGroups(isInt = true)
  val fpWbGroups: Map[Int, Seq[AnsExuParams]]  = wbGroups(isInt = false)

  val nIntRd: Int = AnsIssueParams.nIntRd
  val nFpRd: Int  = AnsIssueParams.nFpRd
  val nIntWb: Int = AnsIssueParams.nIntWb
  val nFpWb: Int  = AnsIssueParams.nFpWb
}

/** LSU 返回的 load 结果预留接口；V0.3 只 tie-off，不接真实 LSU。 */
class AnsLsuResult(val params: BundleParams = BundleParams()) extends Bundle {
  val robIdx        = UInt(params.robIndexWidth.W)
  val pdest         = UInt(params.physicalRegWidth.W)
  val data          = UInt(params.xlen.W)
  val intWen        = Bool()
  val fpWen         = Bool()
  val exceptionBits = UInt(params.exceptionBits.W)
  val replayInst    = Bool()
}

/** DataPath 的只读性能计数器；仅用于 bring-up/调参，不参与功能握手。 */
class AnsDataPathPerf(val params: BundleParams = BundleParams()) extends Bundle {
  val intRdConflict  = Vec(AnsIssueParams.nIntRd, UInt(32.W))
  val fpRdConflict   = Vec(AnsIssueParams.nFpRd, UInt(32.W))
  val data0BlockRead = UInt(32.W)
  val data0BlockWb   = UInt(32.W)
  val intWbConflict  = Vec(AnsIssueParams.nIntWb, UInt(32.W))
  val fpWbConflict   = Vec(AnsIssueParams.nFpWb, UInt(32.W))
}

/** DataPath 回送 Scheduler 的响应、取消与 RegCache 索引。 */
class AnsDataPathToIqIO(val params: BundleParams = BundleParams()) extends Bundle {
  val data0Resp   = Vec(AnsIssueParams.nExu, Valid(new IssueEntryResponse(params)))
  val data1Resp   = Vec(AnsIssueParams.nExu, Valid(new IssueEntryResponse(params)))
  val data0Cancel = Vec(AnsIssueParams.nExu, Bool())
  val data1Cancel = Vec(AnsIssueParams.nExu, Bool())

  /** 携带目的寄存器信息，供 Dispatch 过滤同拍 0-lat 唤醒；已有依赖由 load cancel 撤回。 */
  val data0CancelToDispatch = Vec(AnsIssueParams.nExu, new CancelSignal(params))
  val wbConflict            = Vec(AnsIssueParams.nExu, new WbConflictBundle(ExecutionUnitParams(), params))
  val rcDest                = Vec(AnsIssueParams.nExu, UInt(params.regCacheIndexWidth.W))
}

/** DataPath 的 PC/target 查询接口（PcTargetMem 由 CtrlBlock 维护副本）。 */
class AnsPcTargetRead(val params: BundleParams = BundleParams()) extends Bundle {
  val valid     = Bool()
  val ftqPtr    = new FtqPtr(params)
  val ftqOffset = UInt(params.ftqOffsetWidth.W)
  val pc        = UInt(params.pcWidth.W)
  val target    = UInt(params.pcWidth.W)
}

class AnsPcMemWrite(val params: BundleParams = BundleParams()) extends Bundle {
  val wen    = Bool()
  val ftqPtr = new FtqPtr(params)
  val pc     = UInt(params.pcWidth.W)
  val target = UInt(params.pcWidth.W)
}

class AnsDataPathIO(val params: BundleParams = BundleParams()) extends Bundle {
  val ldCancel = Input(Vec(params.loadPipelineWidth, new LoadCancel))
  val flush    = Flipped(Valid(new AnsRenameRedirect(params)))

  val fromIQ = Vec(
    AnsIssueParams.nExu,
    Flipped(
      Decoupled(
        new IssueQueueIssueBundle(
          IssueQueueParams(AnsIssueParams.maxEntries, Seq.empty),
          ExecutionUnitParams(numRegSrc = params.numSrc),
          params,
        )
      )
    ),
  )

  val intWriteBack = Vec(AnsIssueParams.nIntWb, Flipped(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))
  val fpWriteBack  = Vec(AnsIssueParams.nFpWb, Flipped(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))

  val wbConflict = Vec(AnsIssueParams.nExu, Output(new WbConflictBundle(ExecutionUnitParams(), params)))

  val wbBusyTableWrite = Vec(AnsIssueParams.nExu, Input(new WbFuBusyTableWriteBundle(ExecutionUnitParams(), params)))
  val wbBusyTableRead  = Vec(AnsIssueParams.nExu, Output(new WbFuBusyTableReadBundle(ExecutionUnitParams(), params)))

  val pcMemWrite = Input(new AnsPcMemWrite(params))

  val toIq = new AnsDataPathToIqIO(params)

  /** data1 输出：只填 PRF 数据与控制信息，数据源选择由 BypassNetwork 完成。 */
  val toBypassNetwork =
    Vec(AnsIssueParams.nExu, Decoupled(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc))))

  /** RegCache 读数据：按 exu×src 展平，供 BypassNetwork 选择。 */
  val rcData = Output(Vec(AnsIssueParams.nExu, Vec(params.numSrc, UInt(params.xlen.W))))

  val rcReplaceIdx = Output(Vec(AnsIssueParams.nExu, UInt(params.regCacheIndexWidth.W)))

  val perf = Output(new AnsDataPathPerf(params))
}
