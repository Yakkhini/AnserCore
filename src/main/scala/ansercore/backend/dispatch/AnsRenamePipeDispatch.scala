/* Rename -> Dispatch 管道（设计文档 §4.2.1，对应香山 PipeGroupConnect）。
 *
 * 整组寄存：只有 Dispatch 全部通道完成握手（outAllFire）或管道为空时才能接收新组；
 * redirect 优先冲刷。组内 valid 独立推进，但由 Rename 的整组 canOut 语义保证同进同出。
 */
package ansercore.backend.dispatch

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

class AnsRenamePipeDispatch(val params: BundleParams = BundleParams()) extends Module {
  val width = params.renameWidth

  val io = IO(new Bundle {
    val in         = Vec(width, Flipped(Decoupled(new DynInst(params))))
    val out        = Vec(width, Decoupled(new DynInst(params)))
    val flush      = Input(Bool())
    val outAllFire = Input(Bool())
  })

  val validVec = RegInit(VecInit(Seq.fill(width)(false.B)))
  val dataVec  = Reg(Vec(width, new DynInst(params)))

  val canAcc = io.outAllFire || !validVec.asUInt.orR

  for (i <- 0 until width) {
    validVec(i) := MuxCase(
      validVec(i),
      Seq(
        io.flush                   -> false.B,
        (io.in(i).valid && canAcc) -> true.B,
        io.out(i).ready            -> false.B,
      ),
    )
    when(io.in(i).valid && canAcc) {
      dataVec(i) := io.in(i).bits
    }

    io.in(i).ready  := canAcc
    io.out(i).valid := validVec(i)
    io.out(i).bits  := dataVec(i)
  }
}
