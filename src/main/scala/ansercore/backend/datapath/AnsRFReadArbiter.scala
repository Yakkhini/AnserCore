/* PRF 读口仲裁。
 *
 * 同一物理读口可能被多个 EXU 的源槽共享；本模块按组成员顺序（exuIdx、srcIdx）
 * 做优先级仲裁。读地址和 grant 使用同一个 winnerOH，避免出现“获胜者地址被
 * 按位或”的问题。未请求的源槽不阻塞，未获授权的请求由 DataPath 产生 data0 block。
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._

class AnsRFReadArbiterIO(
  val nPorts:   Int,
  val nExu:     Int,
  val numSrc:   Int,
  val addrWidth: Int,
) extends Bundle {
  val reqValid = Input(Vec(nExu, Vec(numSrc, Bool())))
  val reqAddr  = Input(Vec(nExu, Vec(numSrc, UInt(addrWidth.W))))

  val portAddr = Output(Vec(nPorts, UInt(addrWidth.W)))
  val grant    = Output(Vec(nExu, Vec(numSrc, Bool())))
}

class AnsRFReadArbiter(
  val groups:    Map[Int, Seq[AnsReadPortMember]],
  val nPorts:    Int,
  val nExu:      Int,
  val numSrc:    Int,
  val addrWidth: Int,
) extends Module {
  val io = IO(new AnsRFReadArbiterIO(nPorts, nExu, numSrc, addrWidth))

  for (e <- 0 until nExu; s <- 0 until numSrc) {
    io.grant(e)(s) := true.B
  }
  io.portAddr.foreach(_ := 0.U)

  for ((port, members) <- groups.toSeq.sortBy(_._1)) {
    require(port >= 0 && port < nPorts, s"read port $port out of range")
    val requests = VecInit(members.map(m => io.reqValid(m.exuIdx)(m.srcIdx)))
    val winnerOH = PriorityEncoderOH(requests.asUInt)
    val addresses = VecInit(members.map(m => io.reqAddr(m.exuIdx)(m.srcIdx)))

    io.portAddr(port) := Mux1H(winnerOH, addresses)
    members.zipWithIndex.foreach { case (member, i) =>
      io.grant(member.exuIdx)(member.srcIdx) := !requests(i) || winnerOH(i)
    }
  }
}
