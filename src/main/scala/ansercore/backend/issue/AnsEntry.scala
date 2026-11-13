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
  val issueTimerRead = Output(UInt(2.W))
  val deqPortIdxRead = Output(UInt(log2Ceil(2).W))
}

class AnsEntry(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
  val isEnq:  Boolean,
) extends Module {
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

  val wbHits: Seq[Seq[Bool]]    = (0 until params.numSrc).map { s =>
    io.wakeUpFromWB.map(w => w.valid && srcMatch(status.srcStatus(s), w.bits.pdest, w.bits.intWen, w.bits.fpWen))
  }
  val iqHitsNow: Seq[Seq[Bool]] = (0 until params.numSrc).map { s =>
    io.wakeUpFromIQ.zipWithIndex.map { case (w, k) =>
      val cancel   = io.data0Cancel(iq.wakeupSourceExuIdx(k)) && w.bits.is0Lat
      val ldCancel = AnsIssueUtils.isLoadCancelled(w.bits.loadDependency, io.ldCancel)
      w.valid && srcMatch(status.srcStatus(s), w.bits.pdest, w.bits.intWen, w.bits.fpWen) && !cancel && !ldCancel
    }
  }
  val iqHits1: Seq[Seq[Bool]]   = (0 until params.numSrc).map { s =>
    io.enqWakeUpIQ1.zipWithIndex.map { case (w, k) =>
      val cancel   = io.enqData0Cancel1(iq.wakeupSourceExuIdx(k)) && w.bits.is0Lat
      val ldCancel = AnsIssueUtils.isLoadCancelled(w.bits.loadDependency, io.ldCancel)
      enqDelayActive && w.valid && srcMatch(
        status.srcStatus(s),
        w.bits.pdest,
        w.bits.intWen,
        w.bits.fpWen,
      ) && !cancel && !ldCancel
    }
  }
  val iqHits2: Seq[Seq[Bool]]   = (0 until params.numSrc).map { s =>
    io.enqWakeUpIQ2.zipWithIndex.map { case (w, k) =>
      val cancel   = io.enqData0Cancel2(iq.wakeupSourceExuIdx(k)) && w.bits.is0Lat
      val ldCancel = AnsIssueUtils.isLoadCancelled(w.bits.loadDependency, io.ldCancel)
      enqDelayActive && w.valid && srcMatch(
        status.srcStatus(s),
        w.bits.pdest,
        w.bits.intWen,
        w.bits.fpWen,
      ) && !cancel && !ldCancel
    }
  }
  val wbHits1: Seq[Seq[Bool]]   = (0 until params.numSrc).map { s =>
    io.enqWakeUpWB1.map(w =>
      enqDelayActive && w.valid && srcMatch(status.srcStatus(s), w.bits.pdest, w.bits.intWen, w.bits.fpWen)
    )
  }
  val wbHits2: Seq[Seq[Bool]]   = (0 until params.numSrc).map { s =>
    io.enqWakeUpWB2.map(w =>
      enqDelayActive && w.valid && srcMatch(status.srcStatus(s), w.bits.pdest, w.bits.intWen, w.bits.fpWen)
    )
  }

  val allIqHits = (0 until params.numSrc).map(s => iqHitsNow(s) ++ iqHits1(s) ++ iqHits2(s))
  val allWbHits = (0 until params.numSrc).map(s => wbHits(s) ++ wbHits1(s) ++ wbHits2(s))

  val iqDepVec: Seq[Seq[Vec[UInt]]] = (0 until params.numSrc).map { _ =>
    io.wakeUpFromIQ.map(_.bits.loadDependency) ++ io.enqWakeUpIQ1.map(_.bits.loadDependency) ++ io.enqWakeUpIQ2.map(
      _.bits.loadDependency
    )
  }

  val selResp = WireDefault(0.U.asTypeOf(Valid(new IssueEntryResponse(params))))
  when(validReg && status.issued) {
    when(status.issueTimer === 0.U) {
      selResp := io.data0Resp(status.deqPortIdx)
    }.elsewhen(status.issueTimer === 1.U) {
      selResp := io.data1Resp(status.deqPortIdx)
    }
  }

  val respSuccess = selResp.valid && selResp.bits.ready
  val respBlock   = selResp.valid && !selResp.bits.ready

  // 入队延迟补查后的当前状态
  val currentStatus = WireInit(entryReg.status)
  val srcStateNext  = Wire(Vec(params.numSrc, SourceState()))
  val dataSrcNext   = Wire(Vec(params.numSrc, DataSource()))
  val depNext       = Wire(Vec(params.numSrc, Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W))))
  val exuSrcNext    = Wire(Vec(params.numSrc, UInt(log2Ceil(params.maxExuCount + 1).W)))

  val srcLoadCancel = Wire(Vec(params.numSrc, Bool()))
  val anyLoadCancel = srcLoadCancel.asUInt.orR

  for (s <- 0 until params.numSrc) {
    val src        = entryReg.status.srcStatus(s)
    val iqHitAny   = VecInit(allIqHits(s)).asUInt.orR
    val wbHitAny   = VecInit(allWbHits(s)).asUInt.orR
    val iqHitNow   = VecInit(iqHitsNow(s)).asUInt.orR
    val delayedHit = VecInit(iqHits1(s) ++ iqHits2(s) ++ wbHits1(s) ++ wbHits2(s)).asUInt.orR

    val ldCanc = AnsIssueUtils.isLoadCancelled(src.srcLoadDependency, io.ldCancel)
    srcLoadCancel(s) := ldCanc

    val sourceIsReg = SrcType.isRegister(src.srcType)
    srcStateNext(s) := Mux(
      !sourceIsReg || !validReg,
      SourceState.ready.U(SourceState.width.W),
      Mux(
        iqHitAny || wbHitAny,
        SourceState.ready.U(SourceState.width.W),
        Mux(ldCanc, SourceState.waiting.U(SourceState.width.W), src.srcState),
      ),
    )

    // IQ 唤醒在 Scheduler 已打一拍：消费者出队到 S1 时生产者结果已在 bypass2，
    // 因此这里选 bypass2；下一拍仍未发射则改读 PRF。
    dataSrcNext(s) := MuxCase(
      src.dataSources,
      Seq(
        (delayedHit && !iqHitNow)               -> DataSource.registerFile.U(DataSource.width.W),
        iqHitNow                                -> DataSource.bypass2.U(DataSource.width.W),
        wbHitAny                                -> DataSource.registerFile.U(DataSource.width.W),
        DataSource.readBypass(src.dataSources)  -> DataSource.registerFile.U(DataSource.width.W),
        DataSource.readBypass2(src.dataSources) -> DataSource.registerFile.U(DataSource.width.W),
        DataSource.readForward(src.dataSources) -> DataSource.registerFile.U(DataSource.width.W),
      ),
    )

    val allHits = allIqHits(s) ++ allWbHits(s)
    val allDeps = iqDepVec(s) ++ Seq.fill(iq.wbWakeUpCount)(
      0.U.asTypeOf(Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W)))
    )
    depNext(s) := Mux(
      VecInit(allHits).asUInt.orR,
      Mux1H(allHits, allDeps),
      VecInit(src.srcLoadDependency.map(x => (x << 1)(params.loadDependencyWidth - 1, 0))),
    )
    val allExuIdx =
      iq.wakeupSourceExuIdx ++ iq.wakeupSourceExuIdx ++ iq.wakeupSourceExuIdx ++ Seq.fill(iq.wbWakeUpCount)(-1)
    exuSrcNext(s) := Mux(
      VecInit(allHits).asUInt.orR,
      Mux1H(allHits, allExuIdx.map(e => (e + 1).U(log2Ceil(params.maxExuCount + 1).W))),
      src.exuSources,
    )
  }

  val issuedNext = Mux(
    io.deqSel,
    true.B,
    Mux(respBlock || anyLoadCancel, false.B, entryReg.status.issued),
  )
  val timerNext  = Mux(
    io.deqSel,
    0.U,
    Mux(
      issuedNext && entryReg.status.issued,
      Mux(entryReg.status.issueTimer === 3.U, 3.U, entryReg.status.issueTimer + 1.U),
      3.U,
    ),
  )
  val portNext   = Mux(
    io.deqSel,
    io.deqPortIdxWrite,
    Mux(issuedNext, entryReg.status.deqPortIdx, 0.U),
  )

  val clear = flushed || respSuccess || io.transSel

  val entryUpdate = Wire(new AnsEntryBundle(params, iq))
  entryUpdate                   := entryReg
  entryUpdate.status.robIdx     := entryReg.status.robIdx
  entryUpdate.status.fuType     := entryReg.status.fuType
  entryUpdate.status.blocked    := false.B
  entryUpdate.status.issued     := issuedNext
  entryUpdate.status.firstIssue := Mux(io.deqSel, true.B, entryReg.status.firstIssue)
  entryUpdate.status.issueTimer := timerNext
  entryUpdate.status.deqPortIdx := portNext
  for (s <- 0 until params.numSrc) {
    entryUpdate.status.srcStatus(s).psrc              := entryReg.status.srcStatus(s).psrc
    entryUpdate.status.srcStatus(s).srcType           := entryReg.status.srcStatus(s).srcType
    entryUpdate.status.srcStatus(s).srcState          := srcStateNext(s)
    entryUpdate.status.srcStatus(s).dataSources       := dataSrcNext(s)
    entryUpdate.status.srcStatus(s).srcLoadDependency := depNext(s)
    entryUpdate.status.srcStatus(s).exuSources        := exuSrcNext(s)
    entryUpdate.status.srcStatus(s).useRegCache       := false.B
    entryUpdate.status.srcStatus(s).regCacheIdx       := entryReg.status.srcStatus(s).regCacheIdx
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

  // 入队延迟窗口内，用延迟唤醒结果覆盖当前可发射状态
  when(enqDelayActive) {
    for (s <- 0 until params.numSrc) {
      val delayedIq = VecInit(iqHits1(s) ++ iqHits2(s)).asUInt.orR
      val delayedWb = VecInit(wbHits1(s) ++ wbHits2(s)).asUInt.orR
      when(delayedIq || delayedWb) {
        currentStatus.srcStatus(s).srcState    := SourceState.ready.U(SourceState.width.W)
        currentStatus.srcStatus(s).dataSources := DataSource.registerFile.U(DataSource.width.W)
      }
    }
  }

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
  io.isFirstIssue   := !entryReg.status.firstIssue
  io.issueTimerRead := entryReg.status.issueTimer
  io.deqPortIdxRead := entryReg.status.deqPortIdx
}
