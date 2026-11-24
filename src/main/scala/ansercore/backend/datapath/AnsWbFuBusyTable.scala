/* 中央写回忙表：把共享同一 PRF 写口的所有 EXU 预约合并。
 *
 * 每个 IQ 已经按自己的 EXU 维护一份写回槽表；这里按物理写口 OR 合并，
 * 并把同一拍多个 EXU 预约同一槽的冲突回送给 DataPath。
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.issue.AnsIssueParams

class AnsWbFuBusyTable(val params: BundleParams = BundleParams()) extends Module {
  val nExu      = AnsIssueParams.nExu
  val tableSize = params.maxLatency + 1

  val io = IO(new Bundle {
    val write    = Vec(nExu, Input(new WbFuBusyTableWriteBundle(ExecutionUnitParams(), params)))
    val read     = Vec(nExu, Output(new WbFuBusyTableReadBundle(ExecutionUnitParams(), params)))
    val conflict = Vec(nExu, Output(new WbConflictBundle(ExecutionUnitParams(), params)))
  })

  for (e <- 0 until nExu) {
    io.read(e).intWbBusyTable  := 0.U
    io.read(e).fpWbBusyTable   := 0.U
    io.conflict(e).intConflict := false.B
    io.conflict(e).fpConflict  := false.B
  }

  /** 输入同一物理写口的各 EXU 预约，返回合并忙表与本拍新增预约冲突。 */
  def mergeReservations(tables: Seq[UInt], reservations: Seq[UInt]): (UInt, Bool) = {
    require(tables.nonEmpty && tables.size == reservations.size)
    val merged   = tables.reduce(_ | _)
    val conflict = (0 until tableSize)
      .map { bit =>
        PopCount(VecInit(reservations.map(_(bit)))) > 1.U
      }
      .reduce(_ || _)
    (merged, conflict)
  }

  // ALU lat0 与 MUL/F2I lat2 的跨周期写口冲突；同拍冲突仍由 data0 仲裁。
  val intWbReservePorts: Seq[Int] = Seq(1, 2)

  for (p <- intWbReservePorts) {
    val members = AnsIssueParams.allExus.filter(_.intWbPort == p)
    if (members.nonEmpty) {
      val (merged, conflict) = mergeReservations(
        members.map(e => io.write(e.exuIdx).intWbBusyTable),
        members.map(e => io.write(e.exuIdx).intDeqRespSet),
      )
      members.foreach { e =>
        io.read(e.exuIdx).intWbBusyTable  := merged
        io.conflict(e.exuIdx).intConflict := conflict
      }
    }
  }

  // FP 三个 FEX 各有独立 FpWB；load 口预留给 LSU，不在这里合并。
}
