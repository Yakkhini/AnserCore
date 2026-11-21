/* FU / WB 固定延迟忙表。
 *
 * 只跟踪确定延迟 FU：表项 k 表示"k 拍后该 FU（或该写回口）仍被占用"。
 * 每个出队口 / 写回口按端口自动裁剪：
 *   - FU 端口有多种固定延迟时才生成本地忙表；
 *   - WB 端口只要有固定延迟写回就输出预约向量，供中央忙表处理跨 EXU 冲突。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

/** 写侧：出队时预约延迟槽，data0/data1 block 时释放。 */
class AnsFuBusyTableWrite(
  val fuLatencyMap: Map[Int, Int],
  val tableSize:    Int,
) extends Module {
  require(tableSize >= 2)
  require(fuLatencyMap.values.forall(lat => lat >= 0 && lat < tableSize), "FU latency exceeds busy-table capacity")

  val io = IO(new Bundle {
    val deqValid  = Input(Bool())
    val deqFuType = Input(FuType())
    val data0Resp = Flipped(Valid(new IssueEntryResponse()))
    val data1Resp = Flipped(Valid(new IssueEntryResponse()))
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

  val deqMatch   = matchVec(io.deqFuType)
  val data0Match = matchVec(io.data0Resp.bits.fuType)
  val data1Match = matchVec(io.data1Resp.bits.fuType)

  val data0Fail = io.data0Resp.valid && IssueRespType.isBlocked(io.data0Resp.bits.resp)
  val data1Fail = io.data1Resp.valid && IssueRespType.isBlocked(io.data1Resp.bits.resp)

  val shift           = (table >> 1).asUInt
  val deqSetShift     = Mux(io.deqValid, deqMatch >> 1, 0.U(tableSize.W))
  val data0ClearShift = Mux(data0Fail, data0Match >> 2, 0.U(tableSize.W))
  val data1ClearShift = Mux(data1Fail, data1Match >> 3, 0.U(tableSize.W))

  table := shift & (~data0ClearShift).asUInt & (~data1ClearShift).asUInt | deqSetShift

  io.busyTable := table
  io.deqSet    := Mux(io.deqValid, deqMatch, 0.U(tableSize.W))
}

/** 读侧：把忙表翻译成"哪些条目不能发射"的掩码。 */
class AnsFuBusyTableRead(
  val fuLatencyMap: Map[Int, Int],
  val numEntries:   Int,
  val tableSize:    Int,
) extends Module {
  require(numEntries > 0 && tableSize >= 2)
  require(fuLatencyMap.values.forall(lat => lat >= 0 && lat < tableSize), "FU latency exceeds busy-table capacity")
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
