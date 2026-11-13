/* 简化版 RegCache。
 *
 * 本版先提供完整的读写口与时序骨架，数据写入使用每个写口的轮转替换索引；
 * IQ 侧暂时关闭 useRegCache，读请求不会真正发出（保留端口便于后续打开）。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

class AnsRegCache(
  val params:   BundleParams = BundleParams(),
  val numRead:  Int,
  val numWrite: Int,
) extends Module {
  val entries   = params.regCacheEntries
  val addrWidth = params.regCacheIndexWidth

  val io = IO(new Bundle {
    val read       = Vec(
      numRead,
      new Bundle {
        val ren  = Input(Bool())
        val addr = Input(UInt(addrWidth.W))
        val data = Output(UInt(params.xlen.W))
      },
    )
    val write      = Vec(
      numWrite,
      new Bundle {
        val wen  = Input(Bool())
        val addr = Input(UInt(addrWidth.W))
        val data = Input(UInt(params.xlen.W))
      },
    )
    val replaceIdx = Vec(numWrite, Output(UInt(addrWidth.W)))
  })

  val data = Reg(Vec(entries, UInt(params.xlen.W)))

  io.read.foreach { r =>
    r.data := RegEnable(data(r.addr), r.ren)
  }

  val ptrs = RegInit(VecInit(Seq.fill(numWrite)(0.U(addrWidth.W))))
  io.replaceIdx := ptrs

  for (w <- 0 until numWrite) {
    when(io.write(w).wen) {
      data(io.write(w).addr) := io.write(w).data
      ptrs(w)                := Mux(ptrs(w) === (entries - 1).U, 0.U, ptrs(w) + 1.U)
    }
  }
}
