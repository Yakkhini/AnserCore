/* Issue 域的接口 bundle。
 *
 * 全部 bundle 只依赖 chisel 与 backend/Bundles.scala，Scheduler/DataPath 的
 * 形状由 AnsIssueParams 在 elaboration 阶段决定。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.AnsRenameRedirect

/** 写回唤醒：来自某个 PRF 写端口。 */
class AnsWbWakeUp(val params: BundleParams = BundleParams()) extends Bundle {
  val intWen = Bool()
  val fpWen  = Bool()
  val pdest  = UInt(params.physicalRegWidth.W)
}

/** IQ 推测唤醒 / 跨 IQ 唤醒。 */
class AnsIqWakeUp(val params: BundleParams = BundleParams()) extends Bundle {
  val robIdx         = UInt(params.robIndexWidth.W)
  val intWen         = Bool()
  val fpWen          = Bool()
  val pdest          = UInt(params.physicalRegWidth.W)
  val loadDependency = Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W))
  val is0Lat         = Bool()
  val rcDest         = UInt(params.regCacheIndexWidth.W)
}

/** 单个源的跟踪状态。 */
class AnsSrcStatus(val params: BundleParams = BundleParams()) extends Bundle {
  val psrc              = UInt(params.physicalRegWidth.W)
  val srcType           = SrcType()
  val srcState          = SourceState()
  val dataSources       = DataSource()
  val srcLoadDependency = Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W))

  /** 生产者 ExeUnit 编号 + 1；0 表示已就绪但非 IQ 唤醒。 */
  val exuSources  = UInt(log2Ceil(params.maxExuCount + 1).W)
  val useRegCache = Bool()
  val regCacheIdx = UInt(params.regCacheIndexWidth.W)
}

/** 发射后的 2 位饱和计数器编码；仅 data0/data1 选择响应。
  *
  * 计数仍经过 2（已超过响应窗口），到 saturated 停止；未发射时也使用 saturated。
  */
object AnsIssueTimer {
  final val width     = 2
  final val data0     = 0
  final val data1     = 1
  final val saturated = 3
}

/** 条目状态。 */
class AnsEntryStatus(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
) extends Bundle {
  val robIdx    = UInt(params.robIndexWidth.W)
  val fuType    = FuType()
  val srcStatus = Vec(params.numSrc, new AnsSrcStatus(params))
  val blocked   = Bool()
  val issued    = Bool()

  /** 曾经发射过；重试时仍为 true，与当前 issued 状态不同。 */
  val hasIssued  = Bool()
  val issueTimer = UInt(AnsIssueTimer.width.W)
  val deqPortIdx = UInt(log2Ceil(2).W)

  def srcReady: Bool = VecInit(srcStatus.map { src =>
    !SrcType.isRegister(src.srcType) || SourceState.isReady(src.srcState)
  }).asUInt.andR

  def canIssue: Bool = srcReady && !issued && !blocked
}

class AnsEntryBundle(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
) extends Bundle {
  val status  = new AnsEntryStatus(params, iq)
  val imm     = UInt(ImmPacking.width.W)
  val payload = new DynInst(params)
}

/** 一次发射选择的完整结果；条目与操作数元数据必须经过同一个选择器。
  *
  * 操作数元数据保持选择当拍的寄存值；entry 中的依赖还包含用于条目转移的下一状态。
  */
class AnsSelectedEntry(val params: BundleParams, val iq: AnsIssueQueueParams) extends Bundle {
  val entry          = new AnsEntryBundle(params, iq)
  val dataSources    = Vec(params.numSrc, DataSource())
  val exuSources     = Vec(params.numSrc, UInt(log2Ceil(params.maxExuCount + 1).W))
  val loadDependency = Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W))
  val isFirstIssue   = Bool()
  val cancel         = Bool()
}

/** 访存 final resp 与 Issue 响应共用同一套三值编码。 */
object AnsRespType {
  val width     = IssueRespType.width
  val block     = IssueRespType.block
  val uncertain = IssueRespType.uncertain
  val success   = IssueRespType.success
}

/** 出队响应（用于 memory 的 final resp，本版只用到 robIdx）。 */
class AnsEntryDeqResp(val params: BundleParams = BundleParams()) extends Bundle {
  val robIdx = UInt(params.robIndexWidth.W)
  val fuType = FuType()
  val resp   = UInt(AnsRespType.width.W)
}

class AnsIssueQueueStatus(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
) extends Bundle {
  val empty    = Bool()
  val full     = Bool()
  val validCnt = UInt(AnsIssueParams.iqValidNumWidth.W)
  val leftVec  = Vec(iq.numEnq + 1, Bool())
}

/** Entries 与 IssueQueue 控制逻辑之间的接口。 */
class AnsEntriesIO(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
) extends Bundle {
  val flush = Input(Valid(new AnsRenameRedirect(params)))

  val enq = Vec(iq.numEnq, Flipped(Valid(new AnsEntryBundle(params, iq))))

  val wakeUpFromWB = Vec(iq.wbWakeUpCount, Flipped(Valid(new AnsWbWakeUp(params))))
  val wakeUpFromIQ = Vec(iq.wakeupSourceExuIdx.size, Flipped(Valid(new AnsIqWakeUp(params))))
  val data0Cancel  = Input(Vec(AnsIssueParams.nExu, Bool()))
  val ldCancel     = Vec(params.loadPipelineWidth, Flipped(new LoadCancel))

  val enqWakeUpWB1    = Vec(iq.wbWakeUpCount, Flipped(Valid(new AnsWbWakeUp(params))))
  val enqWakeUpIQ1    = Vec(iq.wakeupSourceExuIdx.size, Flipped(Valid(new AnsIqWakeUp(params))))
  val enqWakeUpWB2    = Vec(iq.wbWakeUpCount, Flipped(Valid(new AnsWbWakeUp(params))))
  val enqWakeUpIQ2    = Vec(iq.wakeupSourceExuIdx.size, Flipped(Valid(new AnsIqWakeUp(params))))
  val enqData0Cancel1 = Input(Vec(AnsIssueParams.nExu, Bool()))
  val enqData0Cancel2 = Input(Vec(AnsIssueParams.nExu, Bool()))

  val data0Resp = Vec(iq.numDeq, Flipped(Valid(new IssueEntryResponse(params))))
  val data1Resp = Vec(iq.numDeq, Flipped(Valid(new IssueEntryResponse(params))))

  val deqReady             = Vec(iq.numDeq, Input(Bool()))
  val deqSelOH             = Vec(iq.numDeq, Flipped(Valid(UInt(iq.numEntries.W))))
  val enqEntryOldestSel    = Vec(iq.numDeq, Flipped(Valid(UInt(iq.numEnq.W))))
  val othersEntryOldestSel = Vec(iq.numDeq, Flipped(Valid(UInt(iq.numOthers.W))))

  val enqEntryEnqReady = Vec(iq.numEnq, Output(Bool()))

  val valid                = Output(UInt(iq.numEntries.W))
  val issued               = Output(UInt(iq.numEntries.W))
  val canIssue             = Output(UInt(iq.numEntries.W))
  val fuType               = Vec(iq.numEntries, Output(FuType()))
  val deqEntry             = Vec(iq.numDeq, Valid(new AnsSelectedEntry(params, iq)))
  val othersEntryEnqSelVec = Vec(iq.numEnq, Output(UInt(iq.numOthers.W)))
}

/** IssueQueue 对外接口。 */
class AnsIssueQueueIO(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
) extends Bundle {
  val flush = Flipped(Valid(new AnsRenameRedirect(params)))
  val enq   = Vec(iq.numEnq, Flipped(Decoupled(new DynInst(params))))

  val wakeUpFromWB = Vec(iq.wbWakeUpCount, Flipped(Valid(new AnsWbWakeUp(params))))
  val wakeUpFromIQ = Vec(iq.wakeupSourceExuIdx.size, Flipped(Valid(new AnsIqWakeUp(params))))
  val data0Cancel  = Input(Vec(AnsIssueParams.nExu, Bool()))
  val ldCancel     = Vec(params.loadPipelineWidth, Flipped(new LoadCancel))

  val data0Resp = Vec(iq.numDeq, Flipped(Valid(new IssueEntryResponse(params))))
  val data1Resp = Vec(iq.numDeq, Flipped(Valid(new IssueEntryResponse(params))))

  val wbBusyTableRead  = Vec(iq.numDeq, Input(new WbFuBusyTableReadBundle(ExecutionUnitParams(), params)))
  val wbBusyTableWrite = Vec(iq.numDeq, Output(new WbFuBusyTableWriteBundle(ExecutionUnitParams(), params)))

  val status         = Output(new AnsIssueQueueStatus(params, iq))
  val validCntDeqVec = Output(Vec(iq.numDeq, UInt(AnsIssueParams.iqValidNumWidth.W)))

  val wakeupToIQ = Vec(iq.numDeq, Output(Valid(new AnsIqWakeUp(params))))

  /** DataPath 必须逐拍接收；读口冲突/EXU 拒绝通过 data0/data1Resp 通知 IQ 重试，不在此停顿保持。 */
  val deq = Vec(iq.numDeq, Decoupled(new IssueQueueIssueBundle(iq.issueBundleParams, ExecutionUnitParams(), params)))
}

/** 数据通路回送：按 exuIdx 展平。 */
class AnsDataPathToIqIO(val params: BundleParams = BundleParams()) extends Bundle {
  val data0Resp   = Vec(AnsIssueParams.nExu, Valid(new IssueEntryResponse(params)))
  val data1Resp   = Vec(AnsIssueParams.nExu, Valid(new IssueEntryResponse(params)))
  val data0Cancel = Vec(AnsIssueParams.nExu, Bool())
  val data1Cancel = Vec(AnsIssueParams.nExu, Bool())

  /** 携带目的寄存器信息，送至 Dispatch 撤回 BusyTable / RegCacheTag 的就绪状态。 */
  val data0CancelToDispatch = Vec(AnsIssueParams.nExu, new CancelSignal(params))
  val wbConflict            = Vec(AnsIssueParams.nExu, new WbConflictBundle(ExecutionUnitParams(), params))
  val rcDest                = Vec(AnsIssueParams.nExu, UInt(params.regCacheIndexWidth.W))
}

/** Scheduler 对外接口。所有 exu 维度都按全局 exuIdx 展平。 */
class AnsSchedulerIO(
  val params: BundleParams = BundleParams(),
  val schd:   AnsSchedulerParams,
) extends Bundle {
  val flush = Flipped(Valid(new AnsRenameRedirect(params)))

  val fromDispatch = Vec(schd.dispatchEnqPorts, Flipped(Decoupled(new DynInst(params))))

  val intWriteBack = Vec(AnsIssueParams.nIntWb, Flipped(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))
  val fpWriteBack  = Vec(AnsIssueParams.nFpWb, Flipped(new RegisterWriteBundle(params.physicalRegWidth, params.xlen)))

  /** 全局 IQ 唤醒总线（已打一拍），按 exuIdx 索引。 */
  val wakeupIn  = Vec(AnsIssueParams.nExu, Flipped(Valid(new AnsIqWakeUp(params))))
  val wakeupOut = Vec(AnsIssueParams.nExu, Valid(new AnsIqWakeUp(params)))

  val fromDataPath     = Flipped(new AnsDataPathToIqIO(params))
  val wbBusyTableRead  = Vec(AnsIssueParams.nExu, Input(new WbFuBusyTableReadBundle(ExecutionUnitParams(), params)))
  val wbBusyTableWrite = Vec(AnsIssueParams.nExu, Output(new WbFuBusyTableWriteBundle(ExecutionUnitParams(), params)))

  val ldCancel = Vec(params.loadPipelineWidth, Flipped(new LoadCancel))

  val IQValidNumVec = Output(Vec(AnsIssueParams.nExu, UInt(AnsIssueParams.iqValidNumWidth.W)))

  val deq = Vec(
    schd.numDeqPorts,
    Decoupled(
      new IssueQueueIssueBundle(
        IssueQueueParams(AnsIssueParams.maxEntries, Seq.empty),
        ExecutionUnitParams(numRegSrc = params.numSrc),
        params,
      )
    ),
  )
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
  val flush = Flipped(Valid(new AnsRenameRedirect(params)))

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

  /** EXU 组合输出：forward / bypass 数据来源。 */
  val exuOutput = Vec(AnsIssueParams.nExu, Flipped(Valid(new ExuOutput(params, ExecutionUnitParams()))))

  val wbConflict = Vec(AnsIssueParams.nExu, Output(new WbConflictBundle(ExecutionUnitParams(), params)))

  val wbBusyTableWrite = Vec(AnsIssueParams.nExu, Input(new WbFuBusyTableWriteBundle(ExecutionUnitParams(), params)))
  val wbBusyTableRead  = Vec(AnsIssueParams.nExu, Output(new WbFuBusyTableReadBundle(ExecutionUnitParams(), params)))

  val pcMemWrite = Input(new AnsPcMemWrite(params))

  val toIq         = new AnsDataPathToIqIO(params)
  val toExu        = Vec(AnsIssueParams.nExu, Decoupled(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc))))
  val rcReplaceIdx = Output(Vec(AnsIssueParams.nExu, UInt(params.regCacheIndexWidth.W)))
}
