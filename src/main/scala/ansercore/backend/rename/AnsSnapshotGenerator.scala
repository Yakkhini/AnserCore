/* 雁栖湖 rename 的通用快照缓冲区。
 *
 * 语义与昆明湖 Snapshot.scala 一致：一个 renameSnapshotNum 槽的环形缓冲，支持
 *   - 正常入队/出队(redirect 周期冻结)；
 *   - flushVec 批量作废指定槽，并把 enqPtr 回退到第一个可写槽。
 *
 * 与昆明湖的差异：用自包含的 AnsCircularPtr 取代 utility.CircularQueuePtr，
 * 并把「回退 enqPtr」的候选扫描写成普通 for 循环，便于对照 RTL 阅读。
 */
package ansercore.backend.rename

import chisel3._
import chisel3.util._

class AnsSnapshotGeneratorIO[T <: Data](dataType: T) extends Bundle {
  val enq       = Input(Bool())
  val enqData   = Input(dataType.cloneType)
  val deq       = Input(Bool())
  val redirect  = Input(Bool())
  val flushVec  = Input(Vec(AnsRenameParams.renameSnapshotNum, Bool()))
  val snapshots = Output(Vec(AnsRenameParams.renameSnapshotNum, dataType.cloneType))

  // 队列状态，供断言与调试使用。
  val full     = Output(Bool())
  val empty    = Output(Bool())
  val enqIndex = Output(UInt(AnsRenameParams.snapshotSelectWidth.W))
  val valids   = Output(Vec(AnsRenameParams.renameSnapshotNum, Bool()))
}

class AnsSnapshotGenerator[T <: Data](dataType: T) extends Module {
  val numSnapshots = AnsRenameParams.renameSnapshotNum

  val io = IO(new AnsSnapshotGeneratorIO(dataType))

  val snapshots = Reg(Vec(numSnapshots, dataType.cloneType))
  val enqPtr    = RegInit(AnsCircularPtr(numSnapshots, false, 0))
  val deqPtr    = RegInit(AnsCircularPtr(numSnapshots, false, 0))
  val valids    = RegInit(VecInit(Seq.fill(numSnapshots)(false.B)))

  io.snapshots := snapshots
  io.full      := AnsRenameUtil.isFull(enqPtr, deqPtr)
  io.empty     := AnsRenameUtil.isEmpty(enqPtr, deqPtr)
  io.enqIndex  := enqPtr.value
  io.valids    := valids

  // 正常入队/出队；redirect 周期冻结队列，避免用错误路径上的状态污染快照。
  when(!io.redirect && !AnsRenameUtil.isFull(enqPtr, deqPtr) && io.enq) {
    snapshots(enqPtr.value) := io.enqData
    valids(enqPtr.value)    := true.B
    enqPtr                  := enqPtr + 1.U
  }
  when(!io.redirect && io.deq) {
    valids(deqPtr.value) := false.B
    deqPtr               := deqPtr + 1.U
  }

  // flush：先作废被冲刷的槽，再把 enqPtr 回退到 deqPtr 起第一个可用槽。
  valids.zip(io.flushVec).foreach { case (valid, flush) =>
    when(flush)(valid := false.B)
  }
  when((Cat(io.flushVec) & Cat(valids)).orR) {
    val candidates = (0 until numSnapshots).map(offset => deqPtr + offset.U)
    val qualified  = Wire(Vec(numSnapshots, Bool()))
    // 槽 0：deqPtr 处本身空闲或刚被冲刷。
    qualified.head := !valids(candidates.head.value) || io.flushVec(candidates.head.value)
    // 槽 k：前一个槽有效(说明队列还没释放到这里)，且当前槽空闲或被冲刷。
    for (k <- 1 until numSnapshots) {
      qualified(k) := valids(candidates(k - 1).value) &&
        (!valids(candidates(k).value) || io.flushVec(candidates(k).value))
    }
    enqPtr         := MuxCase(
      candidates.last,
      qualified.dropRight(1).zip(candidates).map { case (ok, candidate) => ok -> candidate },
    )
  }
}
