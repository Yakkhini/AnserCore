/* 发射队列条目。
 *
 * 雁栖湖只有 EnqEntry 和 ComplexEntry 两类，二者使用同一套实现，区别仅在于：
 *   - EnqEntry 直接接收 Dispatch 的指令，并带 1~2 拍延迟唤醒补查；
 *   - ComplexEntry 由 EnqEntry 整条转移（不经过 SimpleEntry）。
 * 所有条目都支持 IQ 推测唤醒和同拍 fast-forward 的数据源衰减。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.AnsRenameRedirect

class AnsEntryIO(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
  val isEnq:  Boolean,
) extends Bundle {
  val enq      = Flipped(Valid(new AnsEntryBundle(params, iq)))
  val transSel = Input(Bool())

  val flush = Input(Valid(new AnsRenameRedirect(params)))

  val wakeUpFromWB = Vec(iq.wbWakeUpCount, Flipped(Valid(new AnsWbWakeUp(params))))
  val wakeUpFromIQ = Vec(iq.wakeupSourceExuIdx.size, Flipped(Valid(new AnsIqWakeUp(params))))
  val data0Cancel  = Input(Vec(AnsIssueParams.nExu, Bool()))
  val ldCancel     = Vec(params.loadPipelineWidth, Flipped(new LoadCancel))

  // 入队延迟补查（相对当前周期已打 1/2 拍）
  val enqWakeUpWB1    = Vec(iq.wbWakeUpCount, Flipped(Valid(new AnsWbWakeUp(params))))
  val enqWakeUpIQ1    = Vec(iq.wakeupSourceExuIdx.size, Flipped(Valid(new AnsIqWakeUp(params))))
  val enqWakeUpWB2    = Vec(iq.wbWakeUpCount, Flipped(Valid(new AnsWbWakeUp(params))))
  val enqWakeUpIQ2    = Vec(iq.wakeupSourceExuIdx.size, Flipped(Valid(new AnsIqWakeUp(params))))
  val enqData0Cancel1 = Input(Vec(AnsIssueParams.nExu, Bool()))
  val enqData0Cancel2 = Input(Vec(AnsIssueParams.nExu, Bool()))

  val data0Resp = Vec(iq.numDeq, Flipped(Valid(new IssueEntryResponse(params))))
  val data1Resp = Vec(iq.numDeq, Flipped(Valid(new IssueEntryResponse(params))))

  val deqSel          = Input(Bool())
  val deqPortIdxWrite = Input(UInt(log2Ceil(2).W))

  val valid          = Output(Bool())
  val issued         = Output(Bool())
  val canIssue       = Output(Bool())
  val fuType         = Output(FuType())
  val robIdx         = Output(UInt(params.robIndexWidth.W))
  val dataSources    = Vec(params.numSrc, Output(DataSource()))
  val exuSources     = Vec(params.numSrc, Output(UInt(log2Ceil(params.maxExuCount + 1).W)))
  val loadDependency = Vec(params.loadPipelineWidth, Output(UInt(params.loadDependencyWidth.W)))

  val entry          = Valid(new AnsEntryBundle(params, iq))
  val transEntry     = Valid(new AnsEntryBundle(params, iq))
  val enqReady       = Output(Bool())
  val cancelBypass   = Output(Bool())
  val isFirstIssue   = Output(Bool())
  val issueTimerRead = Output(UInt(AnsIssueTimer.width.W))
  val deqPortIdxRead = Output(UInt(log2Ceil(2).W))
}

object AnsEntry {

  /** Elaborating one candidate keeps its hit, data path and producer metadata together. */
  final case class WakeupCandidate(
    hit:        Bool,
    dataSource: UInt,
    dependency: Vec[UInt],
    exuSource:  UInt,
  )

  /** 本拍 IQ 唤醒命中后该源采用的数据源档位。
    *
    * 与 AnsBackend 的 `registerWakeupBroadcast` 绑定：唤醒在 Backend 合流后打一拍才到达，
    * 消费者进入 data1 时生产者的结果正好落在 bypass2。
    * 若将来关闭 `registerWakeupBroadcast`（香山式组合广播），这里必须同步改成
    * `DataSource.bypass`，并按设计文档 §5.5 补齐 DataPath forward 与取消回滚。
    */
  val iqWakeupHitDataSource: Int = DataSource.bypass2

}

class AnsEntry(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
  val isEnq:  Boolean,
) extends Module {
  import AnsEntry.WakeupCandidate

  iq.validate(params)
  val io = IO(new AnsEntryIO(params, iq, isEnq))

  val validReg    = RegInit(false.B)
  val entryReg    = Reg(new AnsEntryBundle(params, iq))
  val enqDelayCnt = RegInit(0.U(2.W))

  val status = entryReg.status

  val enqFire = io.enq.valid && io.enqReady
  val flushed = AnsIssueUtils.needFlush(status.robIdx, io.flush)

  // 入队当拍/前一拍可能已经发出唤醒，EnqEntry 用延迟副本补查。
  val enqDelayActive = isEnq.B && enqDelayCnt =/= 0.U
  when(enqFire) {
    enqDelayCnt := 2.U
  }.elsewhen(enqDelayCnt =/= 0.U) {
    enqDelayCnt := enqDelayCnt - 1.U
  }

  def srcMatch(src: AnsSrcStatus, pdest: UInt, intWen: Bool, fpWen: Bool): Bool =
    pdest === src.psrc &&
      ((SrcType.isInteger(src.srcType) && intWen) || (SrcType.isFloating(src.srcType) && fpWen))

  def iqCandidates(
    src:        AnsSrcStatus,
    wakeups:    Vec[Valid[AnsIqWakeUp]],
    cancel:     Vec[Bool],
    enabled:    Bool,
    dataSource: Int,
  ): Seq[WakeupCandidate] = wakeups.zip(iq.wakeupSourceExuIdx).map { case (w, exuIdx) =>
    // 0-lat 唤醒(推测唤醒)在生产者的 data0 结果出来之前就已广播，因此必须允许撤销：
    // 当前广播在 Backend 打了一拍，生产者的 data0Cancel 与本拍唤醒同拍到达，用
    // `!cancelled` 挡掉即可，不需要回滚已经置 ready 的源。
    // TODO(0-lat 同拍发射): 若改为组合广播或 entry 级 canIssueBypass，取消会晚于源被
    //   置 ready 一拍，必须增加“已发射回滚/重发”路径（参考香山
    //   srcWakeupByIQButCancel / cancelBypass）。详见设计文档 §5.5。
    val cancelled = (cancel(exuIdx) && w.bits.is0Lat) ||
      AnsIssueUtils.isLoadCancelled(w.bits.loadDependency, io.ldCancel)
    WakeupCandidate(
      enabled && w.valid && srcMatch(src, w.bits.pdest, w.bits.intWen, w.bits.fpWen) && !cancelled,
      dataSource.U(DataSource.width.W),
      w.bits.loadDependency,
      (exuIdx + 1).U(log2Ceil(params.maxExuCount + 1).W),
    )
  }

  def wbCandidates(
    src:     AnsSrcStatus,
    wakeups: Vec[Valid[AnsWbWakeUp]],
    enabled: Bool,
  ): Seq[WakeupCandidate] = wakeups.map { w =>
    WakeupCandidate(
      enabled && w.valid && srcMatch(src, w.bits.pdest, w.bits.intWen, w.bits.fpWen),
      DataSource.registerFile.U(DataSource.width.W),
      0.U.asTypeOf(Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W))),
      0.U(log2Ceil(params.maxExuCount + 1).W),
    )
  }

  val selResp = WireDefault(0.U.asTypeOf(Valid(new IssueEntryResponse(params))))
  when(validReg && status.issued) {
    when(status.issueTimer === AnsIssueTimer.data0.U) {
      selResp := (if (iq.numDeq == 1) io.data0Resp(0) else io.data0Resp(status.deqPortIdx))
    }.elsewhen(status.issueTimer === AnsIssueTimer.data1.U) {
      selResp := (if (iq.numDeq == 1) io.data1Resp(0) else io.data1Resp(status.deqPortIdx))
    }
  }

  val respSuccess = selResp.valid && IssueRespType.isSuccess(selResp.bits.resp)
  val respBlock   = selResp.valid && IssueRespType.isBlocked(selResp.bits.resp)
  // uncertain：既不清除也不重发，timer 自然推进到下一阶段

  // 入队延迟补查后的当前状态
  val currentStatus = WireInit(entryReg.status)
  val srcStateNext  = Wire(Vec(params.numSrc, SourceState()))
  val dataSrcNext   = Wire(Vec(params.numSrc, DataSource()))
  val depNext       = Wire(Vec(params.numSrc, Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W))))
  val exuSrcNext    = Wire(Vec(params.numSrc, UInt(log2Ceil(params.maxExuCount + 1).W)))

  val srcLoadCancel = Wire(Vec(params.numSrc, Bool()))
  val anyLoadCancel = srcLoadCancel.asUInt.orR

  for (s <- 0 until params.numSrc) {
    val src   = entryReg.status.srcStatus(s)
    val iqNow = iqCandidates(src, io.wakeUpFromIQ, io.data0Cancel, true.B, AnsEntry.iqWakeupHitDataSource)
    val iqD1  = iqCandidates(src, io.enqWakeUpIQ1, io.enqData0Cancel1, enqDelayActive, DataSource.registerFile)
    val iqD2  = iqCandidates(src, io.enqWakeUpIQ2, io.enqData0Cancel2, enqDelayActive, DataSource.registerFile)
    val wbNow = wbCandidates(src, io.wakeUpFromWB, true.B)
    val wbD1  = wbCandidates(src, io.enqWakeUpWB1, enqDelayActive)
    val wbD2  = wbCandidates(src, io.enqWakeUpWB2, enqDelayActive)

    // 当前 IQ 优先以保留 bypass2；否则确认写回优先于延迟 IQ，清除推测依赖。
    // 同组按输入端口顺序选择；延迟 IQ 按较新的一拍副本优先。
    val candidates = iqNow ++ wbNow ++ wbD1 ++ wbD2 ++ iqD1 ++ iqD2
    val hits       = VecInit(candidates.map(_.hit))
    val hitAny     = hits.asUInt.orR
    val winnerOH   = PriorityEncoderOH(hits.asUInt)
    val delayedHit = (iqD1 ++ iqD2 ++ wbD1 ++ wbD2).map(_.hit).foldLeft(false.B)(_ || _)

    val ldCanc = AnsIssueUtils.isLoadCancelled(src.srcLoadDependency, io.ldCancel)
    srcLoadCancel(s) := ldCanc
    srcStateNext(s)  := Mux(
      !SrcType.isRegister(src.srcType) || !validReg || hitAny,
      SourceState.ready.U(SourceState.width.W),
      Mux(ldCanc, SourceState.waiting.U(SourceState.width.W), src.srcState),
    )

    // Backend 已将 IQ 唤醒打一拍；消费者进入 data1 时使用 bypass2
    // （档位定义在 AnsEntry.iqWakeupHitDataSource，与广播打拍开关绑定）。
    // 没有新唤醒时，旁路来源下一拍衰减为 PRF。
    val bypassExpires = DataSource.readBypass(src.dataSources) ||
      DataSource.readBypass2(src.dataSources) || DataSource.readForward(src.dataSources)
    dataSrcNext(s) := Mux(
      hitAny,
      Mux1H(winnerOH, candidates.map(_.dataSource)),
      Mux(bypassExpires, DataSource.registerFile.U(DataSource.width.W), src.dataSources),
    )
    depNext(s)     := Mux(
      hitAny,
      Mux1H(winnerOH, candidates.map(_.dependency)),
      VecInit(src.srcLoadDependency.map(x => (x << 1)(params.loadDependencyWidth - 1, 0))),
    )
    exuSrcNext(s)  := Mux(hitAny, Mux1H(winnerOH, candidates.map(_.exuSource)), src.exuSources)

    // 入队补查允许当拍发射，历史结果从 PRF 读取。
    when(delayedHit) {
      currentStatus.srcStatus(s).srcState    := SourceState.ready.U(SourceState.width.W)
      currentStatus.srcStatus(s).dataSources := DataSource.registerFile.U(DataSource.width.W)
    }
  }

  val issuedNext = Mux(
    io.deqSel && !anyLoadCancel,
    true.B,
    Mux(respBlock || anyLoadCancel, false.B, entryReg.status.issued),
  )
  val timerNext  = Mux(
    io.deqSel,
    AnsIssueTimer.data0.U(AnsIssueTimer.width.W),
    Mux(
      issuedNext && entryReg.status.issued,
      Mux(
        entryReg.status.issueTimer === AnsIssueTimer.saturated.U,
        AnsIssueTimer.saturated.U(AnsIssueTimer.width.W),
        entryReg.status.issueTimer + 1.U,
      ),
      AnsIssueTimer.saturated.U(AnsIssueTimer.width.W),
    ),
  )
  val portNext   = Mux(
    io.deqSel,
    io.deqPortIdxWrite,
    Mux(issuedNext, entryReg.status.deqPortIdx, 0.U),
  )

  val clear = flushed || respSuccess || io.transSel

  val entryUpdate = WireInit(entryReg)
  entryUpdate.status.blocked    := false.B
  entryUpdate.status.issued     := issuedNext
  entryUpdate.status.hasIssued  := Mux(io.deqSel, true.B, entryReg.status.hasIssued)
  entryUpdate.status.issueTimer := timerNext
  entryUpdate.status.deqPortIdx := portNext
  for (s <- 0 until params.numSrc) {
    entryUpdate.status.srcStatus(s).srcState          := srcStateNext(s)
    entryUpdate.status.srcStatus(s).dataSources       := dataSrcNext(s)
    entryUpdate.status.srcStatus(s).srcLoadDependency := depNext(s)
    entryUpdate.status.srcStatus(s).exuSources        := exuSrcNext(s)
    entryUpdate.status.srcStatus(s).useRegCache       := false.B
  }

  val validNext =
    if (isEnq) Mux(enqFire, true.B, Mux(clear, false.B, validReg))
    else Mux(io.enq.valid, true.B, Mux(clear, false.B, validReg))

  when(io.enq.valid && io.enqReady) {
    entryReg := io.enq.bits
  }.otherwise {
    entryReg := entryUpdate
  }
  validReg := validNext

  io.valid          := validReg
  io.issued         := entryReg.status.issued
  io.canIssue       := validReg && currentStatus.canIssue && !flushed
  io.fuType         := entryReg.status.fuType
  io.robIdx         := entryReg.status.robIdx
  io.dataSources    := VecInit(entryReg.status.srcStatus.map(_.dataSources))
  io.exuSources     := VecInit(entryReg.status.srcStatus.map(_.exuSources))
  io.loadDependency := AnsIssueUtils.mergeLoadDependency(entryReg.status.srcStatus.map(_.srcLoadDependency))

  val entryOut = Wire(new AnsEntryBundle(params, iq))
  entryOut        := entryReg
  entryOut.status := currentStatus
  for (s <- 0 until params.numSrc) {
    entryOut.status.srcStatus(s).srcLoadDependency := depNext(s)
  }

  io.entry.valid := validReg
  io.entry.bits  := entryOut

  io.transEntry.valid := validReg && !flushed && !entryReg.status.issued
  io.transEntry.bits  := entryUpdate

  io.enqReady       := !validReg || io.transSel
  io.cancelBypass   := validReg && anyLoadCancel
  io.isFirstIssue   := !entryReg.status.hasIssued
  io.issueTimerRead := entryReg.status.issueTimer
  io.deqPortIdxRead := entryReg.status.deqPortIdx
}
