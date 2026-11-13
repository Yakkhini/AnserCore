/* Entries：EnqEntry 池 + ComplexEntry 池。
 *
 * 不实现 SimpleEntry：
 *   - EnqEntry 直接接收 Dispatch 指令，可被发射；
 *   - 未被发射的 EnqEntry 以"全转或全不转"的方式迁移到 ComplexEntry；
 *   - ComplexEntry 是最终条目，不可迁移。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

class AnsEntries(
  val params: BundleParams = BundleParams(),
  val iq:     AnsIssueQueueParams,
) extends Module {
  val io = IO(new AnsEntriesIO(params, iq))

  val numEnq    = iq.numEnq
  val numOthers = iq.numOthers

  val enqEntries    = Seq.fill(numEnq)(Module(new AnsEntry(params, iq, isEnq = true)))
  val othersEntries = Seq.fill(numOthers)(Module(new AnsEntry(params, iq, isEnq = false)))
  val allEntries    = enqEntries ++ othersEntries

  // ---- 广播输入 ----
  def broadcastIn(): Unit = {
    allEntries.zipWithIndex.foreach { case (entry, idx) =>
      entry.io.transSel        := false.B
      entry.io.flush           := io.flush
      entry.io.wakeUpFromWB    := io.wakeUpFromWB
      entry.io.wakeUpFromIQ    := io.wakeUpFromIQ
      entry.io.data0Cancel     := io.data0Cancel
      entry.io.ldCancel        := io.ldCancel
      entry.io.enqWakeUpWB1    := io.enqWakeUpWB1
      entry.io.enqWakeUpIQ1    := io.enqWakeUpIQ1
      entry.io.enqWakeUpWB2    := io.enqWakeUpWB2
      entry.io.enqWakeUpIQ2    := io.enqWakeUpIQ2
      entry.io.enqData0Cancel1 := io.enqData0Cancel1
      entry.io.enqData0Cancel2 := io.enqData0Cancel2
      entry.io.data0Resp       := io.data0Resp
      entry.io.data1Resp       := io.data1Resp
      entry.io.deqSel          := false.B
      entry.io.deqPortIdxWrite := 0.U
      if (idx < numEnq) {
        entry.io.enq := io.enq(idx)
      } else {
        entry.io.enq := 0.U.asTypeOf(Valid(new AnsEntryBundle(params, iq)))
      }
    }
  }
  broadcastIn()

  // ---- 转移：EnqEntry -> ComplexEntry，全转或全不转 ----
  val enqValidForTrans = VecInit(enqEntries.map(e => e.io.valid && !e.io.issued))
  val othersFree       = VecInit(othersEntries.map(e => !e.io.valid))
  val enqCanTrans      = PopCount(enqValidForTrans) <= PopCount(othersFree)

  def selectSlots(free: UInt, n: Int): Seq[UInt] = {
    if (n == 0) Seq.empty
    else {
      val sel = PriorityEncoderOH(free)
      sel +: selectSlots(free & ~sel, n - 1)
    }
  }
  val slotSels                                   = selectSlots(othersFree.asUInt, numEnq)
  val transSelOH                                 = Wire(Vec(numEnq, UInt(numOthers.W)))
  for (i <- 0 until numEnq) {
    transSelOH(i) := slotSels(i)
  }

  val enqTransSel = VecInit((0 until numEnq).map { i =>
    enqCanTrans && enqValidForTrans(i) && transSelOH(i).orR
  })

  for (i <- 0 until numEnq) {
    enqEntries(i).io.transSel  := enqTransSel(i)
    io.othersEntryEnqSelVec(i) := Fill(numOthers, enqTransSel(i)) & transSelOH(i)
    io.enqEntryEnqReady(i)     := enqEntries(i).io.enqReady
  }

  for (j <- 0 until numOthers) {
    val enqOH = VecInit((0 until numEnq).map(i => enqTransSel(i) && transSelOH(i)(j)))
    val bits  = Mux1H(enqOH, enqEntries.map(_.io.transEntry.bits))
    othersEntries(j).io.enq.valid := enqOH.asUInt.orR
    othersEntries(j).io.enq.bits  := bits
  }

  // ---- 发射选择 ----
  val deqSelVec  = Wire(Vec(numEnq + numOthers, Bool()))
  val deqPortIdx = Wire(Vec(numEnq + numOthers, UInt(log2Ceil(2).W)))
  for (e <- 0 until numEnq + numOthers) {
    val hitVec = (0 until iq.numDeq).map(d => io.deqSelOH(d).valid && io.deqSelOH(d).bits(e) && io.deqReady(d))
    deqSelVec(e)  := VecInit(hitVec).asUInt.orR
    deqPortIdx(e) := OHToUInt(VecInit(hitVec))
  }
  allEntries.zipWithIndex.foreach { case (entry, e) =>
    entry.io.deqSel          := deqSelVec(e)
    entry.io.deqPortIdxWrite := deqPortIdx(e)
  }

  val enqEntryOut    = enqEntries.map(_.io.entry)
  val othersEntryOut = othersEntries.map(_.io.entry)
  val enqCancel      = enqEntries.map(_.io.cancelBypass)
  val othersCancel   = othersEntries.map(_.io.cancelBypass)

  for (d <- 0 until iq.numDeq) {
    val othersSel = io.othersEntryOldestSel(d)
    val enqSel    = io.enqEntryOldestSel(d)
    io.deqEntry(d).valid := othersSel.valid || enqSel.valid
    io.deqEntry(d).bits  := Mux(
      othersSel.valid,
      Mux1H(othersSel.bits, othersEntryOut.map(_.bits)),
      Mux1H(enqSel.bits, enqEntryOut.map(_.bits)),
    )
    io.cancelDeqVec(d)   := Mux(
      othersSel.valid,
      Mux1H(othersSel.bits, othersCancel),
      Mux1H(enqSel.bits, enqCancel),
    )
    io.isFirstIssue(d)   := Mux(
      othersSel.valid,
      Mux1H(othersSel.bits, othersEntries.map(_.io.isFirstIssue)),
      Mux1H(enqSel.bits, enqEntries.map(_.io.isFirstIssue)),
    )
  }

  // ---- 汇总状态 ----
  io.valid          := VecInit(allEntries.map(_.io.valid)).asUInt
  io.issued         := VecInit(allEntries.map(_.io.issued)).asUInt
  io.canIssue       := VecInit(allEntries.map(_.io.canIssue)).asUInt
  io.fuType         := VecInit(allEntries.map(_.io.fuType))
  io.dataSources    := VecInit(allEntries.map(_.io.dataSources))
  io.exuSources     := VecInit(allEntries.map(_.io.exuSources))
  io.loadDependency := VecInit(allEntries.map(_.io.loadDependency))
}
