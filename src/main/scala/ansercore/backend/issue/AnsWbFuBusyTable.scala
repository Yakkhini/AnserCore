/* 中央写回忙表：把共享同一 PRF 写口的所有 EXU 预约合并。
 *
 * 每个 IQ 已经按自己的 EXU 维护一份写回槽表；这里按物理写口 OR 合并，
 * 并把同一拍多个 EXU 预约同一槽的冲突回送给 DataPath。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

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

  // int domain
  for (p <- 0 until AnsIssueParams.nIntWb) {
    val members = AnsIssueParams.allExus.filter(_.intWbPort == p)
    if (members.nonEmpty) {
      val merged   = members.map(e => io.write(e.exuIdx).intWbBusyTable).reduce(_ | _)
      val conflict = VecInit((0 until tableSize).map { bit =>
        PopCount(VecInit(members.map(e => io.write(e.exuIdx).intDeqRespSet(bit)))) > 1.U
      }).asUInt.orR
      members.foreach { e =>
        io.read(e.exuIdx).intWbBusyTable  := merged
        io.conflict(e.exuIdx).intConflict := conflict
      }
    }
  }

  // fp domain
  for (p <- 0 until AnsIssueParams.nFpWb) {
    val members = AnsIssueParams.allExus.filter(_.fpWbPort == p)
    if (members.nonEmpty) {
      val merged   = members.map(e => io.write(e.exuIdx).fpWbBusyTable).reduce(_ | _)
      val conflict = VecInit((0 until tableSize).map { bit =>
        PopCount(VecInit(members.map(e => io.write(e.exuIdx).fpDeqRespSet(bit)))) > 1.U
      }).asUInt.orR
      members.foreach { e =>
        io.read(e.exuIdx).fpWbBusyTable  := merged
        io.conflict(e.exuIdx).fpConflict := conflict
      }
    }
  }
}
