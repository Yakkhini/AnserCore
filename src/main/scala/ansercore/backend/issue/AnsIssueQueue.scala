/* IssueQueue：发射队列顶层。
 *
 * 结构（对应设计文档 §5.2）：
 *   Entries（EnqEntry + ComplexEntry）
 *   ├─ FuBusyTableWrite/Read × numDeq（按端口自动裁剪）
 *   ├─ WbFuBusyTableWrite/Read × numDeq（按端口自动裁剪）
 *   ├─ AgeDetector（EnqEntry 池 / ComplexEntry 池）
 *   └─ MultiWakeupQueue × numDeq（固定延迟推测唤醒）
 *
 * 简化：
 *   - 不区分 SimpleEntry；
 *   - 非确定延迟 FU（div/csr/fence/fdiv/ldu/stu/mou）不产生推测唤醒，只由 WB 唤醒；
 *   - IQ 唤醒广播前在 Backend 统一打一拍，避免 IQ 内部组合环。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.AnsRenameRedirect

class AnsIssueQueue(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
) extends Module {
  iq.validate(params)
  val io = IO(new AnsIssueQueueIO(params, iq))

  val numEnq     = iq.numEnq
  val numEntries = iq.numEntries
  val numOthers  = iq.numOthers
  val numDeq     = iq.numDeq
  val latWidth   = params.maxLatency + 1

  val entries = Module(new AnsEntries(params, iq))

  // ------------------------------------------------------------------
  // 入队：Dispatch DynInst -> EntryBundle
  // ------------------------------------------------------------------
  def makeEntryBundle(uop: DynInst): AnsEntryBundle = {
    val entry = Wire(new AnsEntryBundle(params, iq))
    entry                   := 0.U.asTypeOf(entry)
    entry.payload           := uop
    entry.imm               := uop.imm
    entry.status.robIdx     := uop.robIdx
    entry.status.fuType     := uop.fuType
    entry.status.blocked    := false.B
    entry.status.issued     := false.B
    entry.status.hasIssued  := false.B
    entry.status.issueTimer := AnsIssueTimer.saturated.U(AnsIssueTimer.width.W)
    entry.status.deqPortIdx := 0.U
    for (j <- 0 until params.numSrc) {
      val srcType = uop.srcType(j)
      val isReg   = SrcType.isRegister(srcType)
      entry.status.srcStatus(j).psrc              := uop.psrc(j)
      entry.status.srcStatus(j).srcType           := srcType
      entry.status.srcStatus(j).srcState          := Mux(isReg, uop.srcState(j), SourceState.ready.U(SourceState.width.W))
      entry.status.srcStatus(j).dataSources       := MuxCase(
        DataSource.registerFile.U(DataSource.width.W),
        Seq(
          (!isReg)                                            -> DataSource.immediate.U(DataSource.width.W),
          (SrcType.isInteger(srcType) && uop.psrc(j) === 0.U) -> DataSource.zero.U(DataSource.width.W),
        ),
      )
      entry.status.srcStatus(j).srcLoadDependency := VecInit(
        uop.srcLoadDependency(j).map(x => (x << 1)(params.loadDependencyWidth - 1, 0))
      )
      entry.status.srcStatus(j).exuSources        := 0.U
      entry.status.srcStatus(j).useRegCache       := false.B
      entry.status.srcStatus(j).regCacheIdx       := uop.regCacheIdx(j)
    }
    entry
  }

  val enqNotFlush     = !io.flush.valid
  val enqEntryBundles = Wire(Vec(numEnq, new AnsEntryBundle(params, iq)))
  for (i <- 0 until numEnq) {
    enqEntryBundles(i) := makeEntryBundle(io.enq(i).bits)
    io.enq(i).ready    := entries.io.enqEntryEnqReady(i) && enqNotFlush
  }

  // ------------------------------------------------------------------
  // 唤醒输入：延迟 1/2 拍副本用于 EnqEntry 补查
  // ------------------------------------------------------------------
  val wakeUpWBD1    = RegNext(io.wakeUpFromWB)
  val wakeUpIQD1    = RegNext(io.wakeUpFromIQ)
  val wakeUpWBD2    = RegNext(wakeUpWBD1)
  val wakeUpIQD2    = RegNext(wakeUpIQD1)
  val data0CancelD1 = RegNext(io.data0Cancel)
  val data0CancelD2 = RegNext(data0CancelD1)

  entries.io.flush           := io.flush
  entries.io.wakeUpFromWB    := io.wakeUpFromWB
  entries.io.wakeUpFromIQ    := io.wakeUpFromIQ
  entries.io.data0Cancel     := io.data0Cancel
  entries.io.ldCancel        := io.ldCancel
  entries.io.enqWakeUpWB1    := wakeUpWBD1
  entries.io.enqWakeUpIQ1    := wakeUpIQD1
  entries.io.enqWakeUpWB2    := wakeUpWBD2
  entries.io.enqWakeUpIQ2    := wakeUpIQD2
  entries.io.enqData0Cancel1 := data0CancelD1
  entries.io.enqData0Cancel2 := data0CancelD2
  entries.io.data0Resp       := io.data0Resp
  entries.io.data1Resp       := io.data1Resp
  for (i <- 0 until numEnq) {
    entries.io.enq(i).valid := io.enq(i).fire
    entries.io.enq(i).bits  := enqEntryBundles(i)
  }

  // ------------------------------------------------------------------
  // 发射选择
  // ------------------------------------------------------------------
  val validVec    = VecInit(entries.io.valid.asBools)
  val issuedVec   = VecInit(entries.io.issued.asBools)
  val canIssueVec = VecInit(entries.io.canIssue.asBools)
  val fuTypeVec   = entries.io.fuType
  val enqFireVec  = VecInit((0 until numEnq).map(i => io.enq(i).fire))

  val fuBusyMaskVec  = Wire(Vec(numDeq, UInt(numEntries.W)))
  val intWbMaskVec   = Wire(Vec(numDeq, UInt(numEntries.W)))
  val fpWbMaskVec    = Wire(Vec(numDeq, UInt(numEntries.W)))
  val deqCanIssueVec = Wire(Vec(numDeq, UInt(numEntries.W)))

  val deqBeforeDly = Wire(
    Vec(
      numDeq,
      Valid(
        new IssueQueueIssueBundle(
          iq.issueBundleParams,
          ExecutionUnitParams(numRegSrc = params.numSrc),
          params,
        )
      ),
    )
  )

  /** 每次调用创建独立写侧；预约有效条件由 FU / WB 调用方给出。 */
  private def makeBusyTableWrite(latencies: Map[Int, Int], deqPort: Int, deqValid: Bool): AnsFuBusyTableWrite = {
    val write = Module(new AnsFuBusyTableWrite(latencies, latWidth))
    write.io.deqValid  := deqValid
    write.io.deqFuType := deqBeforeDly(deqPort).bits.common.fuType
    write.io.data0Resp := io.data0Resp(deqPort)
    write.io.data1Resp := io.data1Resp(deqPort)
    write
  }

  /** 每次调用创建独立读侧；FU 使用本地忙表，WB 使用中央汇总后的忙表。 */
  private def makeBusyMask(latencies: Map[Int, Int], busyTable: UInt): UInt = {
    val read = Module(new AnsFuBusyTableRead(latencies, numEntries, latWidth))
    read.io.fuBusyTable := busyTable
    read.io.fuTypeVec   := fuTypeVec
    read.io.mask
  }

  for (d <- 0 until numDeq) {
    val exu = iq.exus(d)

    // ---- FuBusyTable ----
    val needFuBusy = exu.fuLatencyMap.values.toSet.size > 1
    if (needFuBusy) {
      val write = makeBusyTableWrite(exu.fuLatencyMap, d, deqBeforeDly(d).valid)
      fuBusyMaskVec(d) := makeBusyMask(exu.fuLatencyMap, write.io.busyTable)
    } else {
      fuBusyMaskVec(d) := 0.U
    }

    // ---- WbFuBusyTable：int ----
    // 写回口可能被多个 EXU 共享；即使本 EXU 只有一种延迟，也要输出预约向量，
    // 供中央忙表做同拍冲突检测。
    val intWbTableEn = exu.writesInt && exu.intWbLatencyMap.nonEmpty
    val intTable     = Wire(UInt(latWidth.W))
    val intSet       = Wire(UInt(latWidth.W))
    if (intWbTableEn) {
      val write = makeBusyTableWrite(
        exu.intWbLatencyMap,
        d,
        deqBeforeDly(d).valid && deqBeforeDly(d).bits.common.intWen,
      )
      intTable        := write.io.busyTable
      intSet          := write.io.deqSet
      intWbMaskVec(d) := makeBusyMask(exu.intWbLatencyMap, io.wbBusyTableRead(d).intWbBusyTable)
    } else {
      intTable        := 0.U
      intSet          := 0.U
      intWbMaskVec(d) := 0.U
    }

    // ---- WbFuBusyTable：fp ----
    val fpWbTableEn = exu.writesFp && exu.fpWbLatencyMap.nonEmpty
    val fpTable     = Wire(UInt(latWidth.W))
    val fpSet       = Wire(UInt(latWidth.W))
    if (fpWbTableEn) {
      val write = makeBusyTableWrite(
        exu.fpWbLatencyMap,
        d,
        deqBeforeDly(d).valid && deqBeforeDly(d).bits.common.fpWen,
      )
      fpTable        := write.io.busyTable
      fpSet          := write.io.deqSet
      fpWbMaskVec(d) := makeBusyMask(exu.fpWbLatencyMap, io.wbBusyTableRead(d).fpWbBusyTable)
    } else {
      fpTable        := 0.U
      fpSet          := 0.U
      fpWbMaskVec(d) := 0.U
    }

    io.wbBusyTableWrite(d).intWbBusyTable := intTable
    io.wbBusyTableWrite(d).intDeqRespSet  := intSet
    io.wbBusyTableWrite(d).fpWbBusyTable  := fpTable
    io.wbBusyTableWrite(d).fpDeqRespSet   := fpSet

    // ---- 合并忙表 + 端口 fuType 过滤 ----
    val acceptVec = VecInit((0 until numEntries).map(e => iq.portAccepts(d, fuTypeVec(e))))
    deqCanIssueVec(d) := canIssueVec.asUInt & (~fuBusyMaskVec(d)).asUInt &
      (~intWbMaskVec(d)).asUInt & (~fpWbMaskVec(d)).asUInt & acceptVec.asUInt
  }

  // ---- 年龄选择 ----
  // 入队口 i 固定写 EnqEntry i；年龄矩阵需要的是"哪个 entry 入队"的 one-hot。
  val enqAgeEnq = VecInit((0 until numEnq).map { i =>
    Mux(enqFireVec(i), UIntToOH(i.U, numEnq), 0.U(numEnq.W))
  })
  for (d <- 0 until numDeq) {
    val enqCanIssue    = deqCanIssueVec(d)(numEnq - 1, 0)
    val othersCanIssue = deqCanIssueVec(d)(numEntries - 1, numEnq)

    val enqOldest    = AnsAgeDetector(numEnq, enqAgeEnq, VecInit(Seq(enqCanIssue)))(0)
    val othersOldest = AnsAgeDetector(numOthers, entries.io.othersEntryEnqSelVec, VecInit(Seq(othersCanIssue)))(0)

    entries.io.enqEntryOldestSel(d)    := enqOldest
    entries.io.othersEntryOldestSel(d) := othersOldest
    val selValid = othersOldest.valid || enqOldest.valid
    val selOH    = Cat(othersOldest.bits, Fill(numEnq, !othersOldest.valid) & enqOldest.bits)
    entries.io.deqSelOH(d).valid := selValid
    entries.io.deqSelOH(d).bits  := selOH
    entries.io.deqReady(d)       := true.B
  }

  // ------------------------------------------------------------------
  // 出队 bundle
  // ------------------------------------------------------------------
  val deqDlyValid = RegInit(VecInit(Seq.fill(numDeq)(false.B)))
  val deqDlyBits  = Reg(
    Vec(
      numDeq,
      new IssueQueueIssueBundle(
        iq.issueBundleParams,
        ExecutionUnitParams(numRegSrc = params.numSrc),
        params,
      ),
    )
  )

  for (d <- 0 until numDeq) {
    val entry     = entries.io.deqEntry(d)
    val selected  = entry.valid
    val cancelled = entry.bits.cancel

    deqBeforeDly(d).valid := selected && !cancelled
    deqBeforeDly(d).bits  := 0.U.asTypeOf(deqBeforeDly(d).bits)

    val payload = entry.bits.entry.payload
    val status  = entry.bits.entry.status

    deqBeforeDly(d).bits.srcType     := payload.srcType
    deqBeforeDly(d).bits.psrc        := VecInit(status.srcStatus.map(_.psrc))
    deqBeforeDly(d).bits.regCacheIdx := VecInit(status.srcStatus.map(_.regCacheIdx))
    deqBeforeDly(d).bits.immType     := payload.immType
    deqBeforeDly(d).bits.addrOH      := entries.io.deqSelOH(d).bits
    deqBeforeDly(d).bits.issueValid  := deqBeforeDly(d).valid

    val common = deqBeforeDly(d).bits.common
    common.fuType            := status.fuType
    common.fuOpType          := payload.fuOpType
    common.immType           := payload.immType
    common.srcType           := payload.srcType
    common.src               := 0.U.asTypeOf(common.src)
    common.imm               := entry.bits.entry.imm
    common.nextPcOffset      := 4.U
    common.robIdx            := status.robIdx
    common.iqIdx             := OHToUInt(entries.io.deqSelOH(d).bits)
    common.isFirstIssue      := entry.bits.isFirstIssue
    common.pdest             := payload.pdest
    common.intWen            := payload.intWen
    common.fpWen             := payload.fpWen
    common.needsRoundingMode := payload.needsRoundingMode
    common.commitSelfFlush   := payload.commitSelfFlush
    common.pc                := payload.pc
    common.ftqPtr            := payload.ftqPtr
    common.ftqOffset         := payload.ftqOffset
    common.predictTarget     := payload.pc + 4.U
    common.predictTaken      := payload.predictedTaken
    common.predictValid      := false.B
    common.lqIdx             := payload.lqIdx
    common.sqIdx             := payload.sqIdx
    common.dataSources       := entry.bits.dataSources
    common.exuSources        := entry.bits.exuSources
    common.srcTimer          := 0.U.asTypeOf(common.srcTimer)
    common.loadDependency    := entry.bits.loadDependency
    common.copyValid         := false.B
    common.copyDest          := 0.U.asTypeOf(common.copyDest)
    common.copyIntWen        := 0.U.asTypeOf(common.copyIntWen)
    common.copyFpWen         := 0.U.asTypeOf(common.copyFpWen)

    deqDlyValid(d) := deqBeforeDly(d).valid
    when(deqBeforeDly(d).valid) {
      deqDlyBits(d) := deqBeforeDly(d).bits
    }

    io.deq(d).valid := deqDlyValid(d)
    io.deq(d).bits  := deqDlyBits(d)
    when(deqDlyValid(d) && !io.deq(d).ready) {
      assert(false.B, "IQ dequeue is expected to be accepted by DataPath every cycle")
    }
  }

  // ------------------------------------------------------------------
  // 推测唤醒
  // ------------------------------------------------------------------
  for (d <- 0 until numDeq) {
    val exu = iq.exus(d)
    if (exu.isIQWakeUpSource) {
      val latPairs = exu.fuLatencyMap.toSeq
      val latSel   = latPairs.map { case (fu, lat) => (deqBeforeDly(d).bits.common.fuType === fu.U, lat.U) }
      val hasLat   = VecInit(latSel.map(_._1)).asUInt.orR
      val lat      = Mux1H(latSel)

      val wq    = Module(new AnsMultiWakeupQueue(exu.wakeupFuLatencySet, params))
      val flush = Wire(new AnsWakeupFlush(params))
      flush.redirect                    := io.flush
      flush.ldCancel                    := io.ldCancel
      flush.data0Fail                   := io.data0Resp(d).valid && !io.data0Resp(d).bits.ready
      flush.data1Fail                   := io.data1Resp(d).valid && !io.data1Resp(d).bits.ready
      wq.io.flush                       := flush
      wq.io.enq.valid                   := deqBeforeDly(d).valid && hasLat
      wq.io.enq.bits.lat                := lat
      wq.io.enq.bits.uop.robIdx         := deqBeforeDly(d).bits.common.robIdx
      wq.io.enq.bits.uop.intWen         := deqBeforeDly(d).bits.common.intWen
      wq.io.enq.bits.uop.fpWen          := deqBeforeDly(d).bits.common.fpWen
      wq.io.enq.bits.uop.pdest          := deqBeforeDly(d).bits.common.pdest
      wq.io.enq.bits.uop.loadDependency := deqBeforeDly(d).bits.common.loadDependency
      wq.io.enq.bits.uop.is0Lat         := lat === 0.U
      wq.io.enq.bits.uop.rcDest         := 0.U

      io.wakeupToIQ(d).valid := wq.io.deq.valid
      io.wakeupToIQ(d).bits  := wq.io.deq.bits
    } else {
      io.wakeupToIQ(d).valid := false.B
      io.wakeupToIQ(d).bits  := 0.U.asTypeOf(io.wakeupToIQ(d).bits)
    }
  }

  // ------------------------------------------------------------------
  // 状态
  // ------------------------------------------------------------------
  val othersValidCnt = PopCount(validVec.drop(numEnq))
  val othersFull     = VecInit(validVec.drop(numEnq)).asUInt.andR

  io.status.empty    := !validVec.asUInt.orR
  io.status.full     := othersFull
  io.status.validCnt := PopCount(validVec)
  for (i <- 0 to numEnq) {
    io.status.leftVec(i) := othersValidCnt >= (numOthers - i).U
  }

  for (d <- 0 until numDeq) {
    io.validCntDeqVec(d) := PopCount((0 until numEntries).map { e =>
      validVec(e) && iq.portAccepts(d, fuTypeVec(e))
    })
  }
}
