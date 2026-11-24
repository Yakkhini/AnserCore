/* data0 同拍写回口冲突检查。
 *
 * 多个 EXU 可能共享同一个 PRF 写口。本模块对每个写口的候选做固定优先级仲裁：
 * 编号最小的 EXU 获胜，其余候选在 data0 被 block。它不是跨周期预约（那是
 * AnsWbFuBusyTable 的职责），只解决同拍多个出队候选抢同一写口的问题。
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.issue.AnsIssueParams

class AnsWBCollideCheckerIO(
  val nExu:   Int,
  val nIntWb: Int,
  val nFpWb:  Int,
) extends Bundle {
  val valid  = Input(Vec(nExu, Bool()))
  val fuType = Input(Vec(nExu, FuType()))
  val intWen = Input(Vec(nExu, Bool()))
  val fpWen  = Input(Vec(nExu, Bool()))

  val intGrant    = Output(Vec(nExu, Bool()))
  val fpGrant     = Output(Vec(nExu, Bool()))
  val intConflict = Output(Vec(nExu, Bool()))
  val fpConflict  = Output(Vec(nExu, Bool()))
}

class AnsWBCollideChecker(
  val nExu:   Int = AnsIssueParams.nExu,
  val nIntWb: Int = AnsIssueParams.nIntWb,
  val nFpWb:  Int = AnsIssueParams.nFpWb,
) extends Module {
  val io = IO(new AnsWBCollideCheckerIO(nExu, nIntWb, nFpWb))

  io.intGrant.foreach(_ := true.B)
  io.fpGrant.foreach(_ := true.B)
  io.intConflict.foreach(_ := false.B)
  io.fpConflict.foreach(_ := false.B)

  def connectPorts(
    port:     Int,
    members:  Seq[ansercore.backend.issue.AnsExuParams],
    enable:   Vec[Bool],
    grant:    Vec[Bool],
    conflict: Vec[Bool],
  ): Unit = {
    // 只对固定延迟写回做 data0 同拍检查。FEX 的 F2I 现在是固定写回，也参与对应
    // int 写口检查；DIV/CSR/FENCE/FDIV/load 等保持结果仍在实际 WB 端口仲裁。
    val requests = VecInit(members.map { e =>
      io.valid(e.exuIdx) && enable(e.exuIdx) && e.isFixedFu(io.fuType(e.exuIdx))
    })
    val winnerOH = PriorityEncoderOH(requests.asUInt)
    val anyConflict = PopCount(requests) > 1.U
    members.zipWithIndex.foreach { case (exu, i) =>
      grant(exu.exuIdx) := !requests(i) || winnerOH(i)
      conflict(exu.exuIdx) := anyConflict
    }
  }

  for (port <- 0 until nIntWb) {
    AnsDataPathParams.intWbGroups.get(port).foreach { members =>
      connectPorts(port, members, io.intWen, io.intGrant, io.intConflict)
    }
  }
  for (port <- 0 until nFpWb) {
    AnsDataPathParams.fpWbGroups.get(port).foreach { members =>
      connectPorts(port, members, io.fpWen, io.fpGrant, io.fpConflict)
    }
  }
}
