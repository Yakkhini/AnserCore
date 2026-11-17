/* 后端 PC / target 本地镜像。
 *
 * 由前端 FTQ 的写请求复制更新，DataPath 在 data0 发出查询、data1 使用结果。
 * 读 PC 用 ftqPtr + ftqOffset；读预测目标用 ftqPtr+1 的 startAddr。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

class AnsPcTargetReadPort(val params: BundleParams = BundleParams()) extends Bundle {
  val valid     = Input(Bool())
  val ftqPtr    = Input(new FtqPtr(params))
  val ftqOffset = Input(UInt(params.ftqOffsetWidth.W))
  val pc        = Output(UInt(params.pcWidth.W))
  val target    = Output(UInt(params.pcWidth.W))
}

class AnsPcTargetMem(
  val params:  BundleParams = BundleParams(),
  val numRead: Int,
) extends Module {
  val io = IO(new Bundle {
    val write = Input(new AnsPcMemWrite(params))
    val read  = Vec(numRead, new AnsPcTargetReadPort(params))
  })

  val pcMem     = Reg(Vec(params.ftqEntries, UInt(params.pcWidth.W)))
  val targetMem = Reg(Vec(params.ftqEntries, UInt(params.pcWidth.W)))

  when(io.write.wen) {
    pcMem(io.write.ftqPtr.value)     := io.write.pc
    targetMem(io.write.ftqPtr.value) := io.write.target
  }

  io.read.foreach { r =>
    val pcAddr  = r.ftqPtr.value
    val nextPtr = r.ftqPtr.value + 1.U
    val pcData  = RegEnable(pcMem(pcAddr) + (r.ftqOffset << 1), r.valid)
    val tgtData = RegEnable(targetMem(nextPtr), r.valid)
    r.pc     := pcData
    r.target := tgtData
  }
}
