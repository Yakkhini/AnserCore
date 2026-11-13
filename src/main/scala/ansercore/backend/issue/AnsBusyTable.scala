/* FU / WB 固定延迟忙表。
 *
 * 只跟踪确定延迟 FU：表项 k 表示"k 拍后该 FU（或该写回口）仍被占用"。
 * 每个出队口 / 写回口按端口自动裁剪：
 *   - 一个端口只有一种固定延迟（或全是非确定延迟）时不生成忙表；
 *   - 多种固定延迟共享端口时才保留。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

/** 写侧：出队时预约延迟槽，S0/S1 block 时释放。 */
class AnsFuBusyTableWrite(
  val fuLatencyMap: Map[Int, Int],
  val tableSize:    Int,
) extends Module {
  require(tableSize >= 2)

  val io = IO(new Bundle {
    val deqValid  = Input(Bool())
    val deqFuType = Input(FuType())
    val og0Resp   = Flipped(Valid(new IssueEntryResponse()))
    val og1Resp   = Flipped(Valid(new IssueEntryResponse()))
    val busyTable = Output(UInt(tableSize.W))
    val deqSet    = Output(UInt(tableSize.W))
  })

  val latMapped: Map[Int, Seq[Int]] =
    fuLatencyMap.toSeq.groupBy(_._2).view.mapValues(_.map(_._1)).toMap

  val table = RegInit(0.U(tableSize.W))

  def matchVec(fuType: UInt): UInt =
    VecInit((0 until tableSize).map { lat =>
      latMapped.getOrElse(lat, Seq.empty).map(f => fuType === f.U).foldLeft(false.B)(_ || _)
    }).asUInt

  val deqMatch = matchVec(io.deqFuType)
  val og0Match = matchVec(io.og0Resp.bits.fuType)
  val og1Match = matchVec(io.og1Resp.bits.fuType)

  val og0Fail = io.og0Resp.valid && !io.og0Resp.bits.ready
  val og1Fail = io.og1Resp.valid && !io.og1Resp.bits.ready

  val shift         = (table >> 1).asUInt
  val deqSetShift   = Mux(io.deqValid, deqMatch >> 1, 0.U(tableSize.W))
  val og0ClearShift = Mux(og0Fail, og0Match >> 2, 0.U(tableSize.W))
  val og1ClearShift = Mux(og1Fail, og1Match >> 3, 0.U(tableSize.W))

  table := shift & (~og0ClearShift).asUInt & (~og1ClearShift).asUInt | deqSetShift

  io.busyTable := table
  io.deqSet    := Mux(io.deqValid, deqMatch, 0.U(tableSize.W))
}

/** 读侧：把忙表翻译成"哪些条目不能发射"的掩码。 */
class AnsFuBusyTableRead(
  val fuLatencyMap: Map[Int, Int],
  val numEntries:   Int,
  val tableSize:    Int,
) extends Module {
  val io = IO(new Bundle {
    val fuBusyTable = Input(UInt(tableSize.W))
    val fuTypeVec   = Input(Vec(numEntries, FuType()))
    val mask        = Output(UInt(numEntries.W))
  })

  val latMapped: Map[Int, Seq[Int]] =
    fuLatencyMap.toSeq.groupBy(_._2).view.mapValues(_.map(_._1)).toMap

  val busyVec = io.fuBusyTable.asBools

  io.mask := (0 until tableSize)
    .map { lat =>
      val hitFuTypes = latMapped.getOrElse(lat, Seq.empty)
      val hitVec     = VecInit(io.fuTypeVec.map { fuType =>
        hitFuTypes.map(t => fuType === t.U).foldLeft(false.B)(_ || _)
      }).asUInt
      Mux(busyVec(lat), hitVec, 0.U(numEntries.W))
    }
    .foldLeft(0.U(numEntries.W))(_ | _)
}
