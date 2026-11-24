/* 发射队列条目。
 *
 * 雁栖湖只有 EnqEntry 和 ComplexEntry 两类，二者使用同一套实现，区别仅在于：
 *   - EnqEntry 直接接收 Dispatch 的指令，并带入队后一拍的唤醒补查；
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
  val data1Cancel  = Input(Vec(AnsIssueParams.nExu, Bool()))
  val ldCancel     = Vec(params.loadPipelineWidth, Flipped(new LoadCancel))

  // 入队第一拍补查：唤醒和取消都来自前一拍
  val enqWakeUpWB1    = Vec(iq.wbWakeUpCount, Flipped(Valid(new AnsWbWakeUp(params))))
  val enqWakeUpIQ1    = Vec(iq.wakeupSourceExuIdx.size, Flipped(Valid(new AnsIqWakeUp(params))))
  val enqLdCancel1    = Input(Vec(params.loadPipelineWidth, new LoadCancel))
  val enqData0Cancel1 = Input(Vec(AnsIssueParams.nExu, Bool()))

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

/** 组合命中：原始 IQ 命中用于选择，过滤后的命中用于更新状态。 */
class AnsSrcWakeupInfo(val params: BundleParams) extends Bundle {
  val iqRaw      = Bool()
  val iqValid    = Bool()
  val wb         = Bool()
  val dependency = Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W))
  val exuSource  = UInt(log2Ceil(params.maxExuCount + 1).W)
}

class AnsEntry(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
  val isEnq:  Boolean,
) extends Module {
  iq.validate(params)
  val io         = IO(new AnsEntryIO(params, iq, isEnq))
  val validReg   = RegInit(false.B)
  val entryReg   = Reg(new AnsEntryBundle(params, iq))
  val enqFire    = io.enq.valid && io.enqReady
  val firstCycle = RegNext(enqFire, false.B)
  val status     = entryReg.status
  val flushed    = AnsIssueUtils.needFlush(status.robIdx, io.flush)

  def shift(dep:      Vec[UInt]): Vec[UInt]                                     = AnsIssueUtils.shiftLoadDependency(dep)
  def any(xs:         Seq[Bool]): Bool                                          = xs.foldLeft(false.B)(_ || _)
  def zeroSource(src: AnsSrcStatus): Bool                                       = SrcType.isInteger(src.srcType) && src.psrc === 0.U
  def srcMatch(src: AnsSrcStatus, pdest: UInt, intWen: Bool, fpWen: Bool): Bool =
    !zeroSource(src) && pdest === src.psrc &&
      ((SrcType.isInteger(src.srcType) && intWen) || (SrcType.isFloating(src.srcType) && fpWen))

  def wakeupInfo(
    src:    AnsSrcStatus,
    ws:     Vec[Valid[AnsIqWakeUp]],
    wb:     Vec[Valid[AnsWbWakeUp]],
    cancel: Vec[Bool],
    ld:     Vec[LoadCancel],
  ): AnsSrcWakeupInfo = {
    val result = WireInit(0.U.asTypeOf(new AnsSrcWakeupInfo(params)))
    val raw    = ws.map(w => w.valid && srcMatch(src, w.bits.pdest, w.bits.intWen, w.bits.fpWen))
    if (raw.nonEmpty) {
      val effective = raw.zip(ws).zip(iq.wakeupSourceExuIdx).map { case ((hit, w), e) =>
        hit && !(cancel(e) && w.bits.is0Lat) && !LoadShouldCancel(w.bits.loadDependency, ld)
      }
      val validHit  = any(effective)
      val winner    = PriorityEncoderOH(Mux(validHit, VecInit(effective).asUInt, VecInit(raw).asUInt))
      val w         = Mux1H(winner, ws.map(_.bits))
      result.iqRaw      := any(raw)
      result.iqValid    := validHit
      result.dependency := w.loadDependency
      result.exuSource  := Mux1H(winner, iq.wakeupSourceExuIdx.map(e => (e + 1).U))
    }
    result.wb := any(wb.map(w => w.valid && srcMatch(src, w.bits.pdest, w.bits.intWen, w.bits.fpWen)))
    result
  }

  // 历史补查先形成当前状态；只对入队后的第一拍有效。
  val currentStatus = WireInit(status)
  for (s <- 0 until params.numSrc) {
    val src     = status.srcStatus(s)
    val hist    = wakeupInfo(src, io.enqWakeUpIQ1, io.enqWakeUpWB1, io.enqData0Cancel1, io.enqLdCancel1)
    val histDep = shift(hist.dependency)
    when(isEnq.B && firstCycle) {
      when(hist.wb) {
        currentStatus.srcStatus(s).srcState          := SourceState.ready.U
        currentStatus.srcStatus(s).dataSources       := DataSource.registerFile.U
        currentStatus.srcStatus(s).exuSources        := 0.U
        currentStatus.srcStatus(s).srcLoadDependency := 0.U.asTypeOf(histDep)
      }.elsewhen(hist.iqValid && !LoadShouldCancel(histDep, io.ldCancel)) {
        currentStatus.srcStatus(s).srcState          := SourceState.ready.U
        currentStatus.srcStatus(s).dataSources       := DataSource.bypass.U
        currentStatus.srcStatus(s).exuSources        := hist.exuSource
        currentStatus.srcStatus(s).srcLoadDependency := histDep
      }
    }
  }

  val selResp      = WireDefault(0.U.asTypeOf(Valid(new IssueEntryResponse(params))))
  when(validReg && status.issued) {
    when(status.issueTimer === AnsIssueTimer.data0.U) {
      selResp := (if (iq.numDeq == 1) io.data0Resp(0) else io.data0Resp(status.deqPortIdx))
    }.elsewhen(status.issueTimer === AnsIssueTimer.data1.U) {
      selResp := (if (iq.numDeq == 1) io.data1Resp(0) else io.data1Resp(status.deqPortIdx))
    }
  }
  val respSuccess  = selResp.valid && IssueRespType.isSuccess(selResp.bits.resp)
  val respBlock    = selResp.valid && IssueRespType.isBlocked(selResp.bits.resp)
  val entryUpdate  = WireInit(entryReg)
  val issueStatus  = WireInit(currentStatus)
  val sourceReady  = Wire(Vec(params.numSrc, Bool()))
  val cancelSource = Wire(Vec(params.numSrc, Bool()))

  for (s <- 0 until params.numSrc) {
    val src           = currentStatus.srcStatus(s)
    val next          = entryUpdate.status.srcStatus(s)
    val out           = issueStatus.srcStatus(s)
    val wake          = wakeupInfo(src, io.wakeUpFromIQ, io.wakeUpFromWB, io.data0Cancel, io.ldCancel)
    val inherentReady = !SrcType.isRegister(src.srcType) || zeroSource(src)
    val oldLoadCancel = LoadShouldCancel(src.srcLoadDependency, io.ldCancel)
    val issueDep      = Mux(wake.iqRaw, wake.dependency, src.srcLoadDependency)
    cancelSource(s) := !inherentReady && LoadShouldCancel(issueDep, io.ldCancel)
    sourceReady(s)  := inherentReady || SourceState.isReady(src.srcState) || wake.iqRaw

    // 当拍原始命中保留 forward 的生产者身份；0-lat 失败由消费者 data0 拦截。
    out.dataSources       := Mux(wake.iqRaw, DataSource.forward.U, src.dataSources)
    out.exuSources        := Mux(wake.iqRaw, wake.exuSource, src.exuSources)
    out.srcLoadDependency := Mux(inherentReady, 0.U.asTypeOf(issueDep), shift(issueDep))

    next                   := src
    next.useRegCache       := false.B
    next.srcState          := Mux(
      inherentReady || wake.wb || wake.iqValid,
      SourceState.ready.U,
      Mux(oldLoadCancel, SourceState.waiting.U, src.srcState),
    )
    next.dataSources       := Mux(
      wake.wb,
      DataSource.registerFile.U,
      Mux(
        wake.iqValid,
        DataSource.bypass.U,
        Mux(
          DataSource.readForward(src.dataSources),
          DataSource.bypass.U,
          Mux(DataSource.readBypass(src.dataSources), DataSource.registerFile.U, src.dataSources),
        ),
      ),
    )
    next.exuSources        := Mux(wake.wb, 0.U, Mux(wake.iqValid, wake.exuSource, src.exuSources))
    next.srcLoadDependency := Mux(
      inherentReady || wake.wb,
      0.U.asTypeOf(issueDep),
      shift(Mux(wake.iqValid, wake.dependency, src.srcLoadDependency)),
    )
  }
  val anyLoadCancel = cancelSource.asUInt.orR
  val issuedNext    = Mux(respBlock || anyLoadCancel, false.B, Mux(io.deqSel, true.B, status.issued))
  entryUpdate.status.blocked    := false.B
  entryUpdate.status.issued     := issuedNext
  entryUpdate.status.hasIssued  := status.hasIssued || io.deqSel
  entryUpdate.status.issueTimer := Mux(
    io.deqSel,
    AnsIssueTimer.data0.U,
    Mux(
      issuedNext && status.issued,
      Mux(status.issueTimer === AnsIssueTimer.saturated.U, status.issueTimer, status.issueTimer + 1.U),
      AnsIssueTimer.saturated.U,
    ),
  )
  entryUpdate.status.deqPortIdx := Mux(io.deqSel, io.deqPortIdxWrite, Mux(issuedNext, status.deqPortIdx, 0.U))

  when(enqFire) {
    entryReg := io.enq.bits
    validReg := true.B
  }.otherwise {
    entryReg := entryUpdate
    when(flushed || respSuccess || io.transSel)(validReg := false.B)
  }

  io.valid  := validReg
  io.issued := status.issued
  val canIssueBypass = sourceReady.asUInt.andR && !status.issued && !status.blocked
  io.canIssue          := validReg && canIssueBypass && !flushed
  io.fuType            := status.fuType
  io.robIdx            := status.robIdx
  io.dataSources       := VecInit(issueStatus.srcStatus.map(_.dataSources))
  io.exuSources        := VecInit(issueStatus.srcStatus.map(_.exuSources))
  io.loadDependency    := AnsIssueUtils.mergeLoadDependency(issueStatus.srcStatus.map(_.srcLoadDependency))
  io.entry.valid       := validReg
  io.entry.bits        := entryReg
  io.entry.bits.status := issueStatus
  io.transEntry.valid  := validReg && !flushed && !status.issued
  io.transEntry.bits   := entryUpdate
  io.enqReady          := !validReg || io.transSel
  io.cancelBypass      := validReg && anyLoadCancel
  io.isFirstIssue      := !status.hasIssued
  io.issueTimerRead    := status.issueTimer
  io.deqPortIdxRead    := status.deqPortIdx
}
