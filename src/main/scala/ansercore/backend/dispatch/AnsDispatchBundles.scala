/* Dispatch 与 ROB / LSQ / Scheduler 之间的接口 Bundle。
 *
 * Enq 类接口按对端视角声明方向（与香山 RobEnqIO/LsqEnqIO 一致），
 * Dispatch/CtrlBlock 例化时使用 Flipped；其余为无方向数据 Bundle。
 */
package ansercore.backend.dispatch

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

/** LQ / SQ 条目号，同拍由 LsqEnqCtrl 返回。 */
class AnsLSIdx(val params: BundleParams = BundleParams()) extends Bundle {
  val lqIdx = UInt(params.queueIndexWidth.W)
  val sqIdx = UInt(params.queueIndexWidth.W)
}

/** ROB 入队接口（ROB 视角）。 */
class AnsRobEnqIO(val params: BundleParams = BundleParams()) extends Bundle {
  val canAccept            = Output(Bool())
  val canAcceptForDispatch = Output(Bool())
  val isEmpty              = Output(Bool())
  val needAlloc            = Input(Vec(params.renameWidth, Bool()))
  val req                  = Input(Vec(params.renameWidth, Valid(new DynInst(params))))
  val resp                 = Output(Vec(params.renameWidth, UInt(params.robIndexWidth.W)))
}

/** LSQ 入队接口（LSQ 视角）。 */
class AnsLsqEnqIO(val params: BundleParams = BundleParams()) extends Bundle {
  val canAccept = Output(Bool())
  val needAlloc = Input(Vec(params.renameWidth, UInt(2.W)))
  val req       = Input(Vec(params.renameWidth, Valid(new DynInst(params))))
  val iqAccept  = Input(Vec(params.renameWidth, Bool()))
  val resp      = Output(Vec(params.renameWidth, new AnsLSIdx(params)))
}

/** LsqEnqCtrl -> LSQ：打一拍后的入队请求（不含 LSQ 返回的 canAccept/resp）。 */
class AnsLsqEnqOut(val params: BundleParams = BundleParams()) extends Bundle {
  val needAlloc = Vec(params.renameWidth, UInt(2.W))
  val req       = Vec(params.renameWidth, Valid(new DynInst(params)))
  val iqAccept  = Vec(params.renameWidth, Bool())
}

/** MemBlock -> Dispatch：提交释放与取消释放计数。 */
class AnsMemToDispatchBundle(val params: BundleParams = BundleParams()) extends Bundle {
  private val countWidth = log2Up(params.renameWidth + 1)
  val lcommit            = UInt(countWidth.W)
  val scommit            = UInt(countWidth.W)
  val lqDeqPtr           = UInt(params.queueIndexWidth.W)
  val sqDeqPtr           = UInt(params.queueIndexWidth.W)
  val lqCancelCnt        = UInt(countWidth.W)
  val sqCancelCnt        = UInt(countWidth.W)
}

/** Scheduler 唤醒向量中的单个唤醒事件。 */
class AnsIQWakeUpBundle(val params: BundleParams = BundleParams()) extends Bundle {
  val rfWen  = Bool()
  val fpWen  = Bool()
  val pdest  = UInt(params.physicalRegWidth.W)
  val rcDest = UInt(params.regCacheIndexWidth.W)
  val is0Lat = Bool()
}

/** 三类 Scheduler 的唤醒向量。 */
class AnsIQWakeUpVec(val params: BundleParams = BundleParams()) extends Bundle {
  val wakeUpInt = Vec(AnsDispatchParams.nIntWakeUp, Valid(new AnsIQWakeUpBundle(params)))
  val wakeUpFp  = Vec(AnsDispatchParams.nFpWakeUp, Valid(new AnsIQWakeUpBundle(params)))
  val wakeUpMem = Vec(AnsDispatchParams.nMemWakeUp, Valid(new AnsIQWakeUpBundle(params)))
}

/** Dispatch 调试镜像。 */
class AnsDispatchDebugIO(val params: BundleParams = BundleParams()) extends Bundle {
  private val rankWidth = log2Up(params.renameWidth + 1)

  val allowDispatch         = Vec(params.renameWidth, Bool())
  val blockedByWaitForward  = Vec(params.renameWidth, Bool())
  val notBlockedByPrevious  = Vec(params.renameWidth, Bool())
  val uopBlockByIQ          = Vec(params.renameWidth, Bool())
  val lsqCanAccept          = Bool()
  val lqFreeCount           = UInt(log2Up(AnsDispatchParams.lqEntries + 1).W)
  val sqFreeCount           = UInt(log2Up(AnsDispatchParams.sqEntries + 1).W)
  val uopSelIQ              = Vec(params.renameWidth, Vec(AnsDispatchParams.IQNum, Bool()))
  val uopSelIQMatrix        = Vec(params.renameWidth, Vec(AnsDispatchParams.IQNum, UInt(rankWidth.W)))
  val fromRenameUpdateValid = Vec(params.renameWidth, Bool())
  val enqRobReqValid        = Vec(params.renameWidth, Bool())
  val toIssueQueuesValid    = Vec(AnsDispatchParams.IQEnqSum, Bool())
}
