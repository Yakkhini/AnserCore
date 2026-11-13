/* RegCacheTagTable：整数 RegCache 的标签表（设计文档 §5.3）。
 *
 * 只维护"哪个物理寄存器已被缓存"，不实现数据阵列：全局 28 项（Int 16 + Mem 12），
 * 索引 5 bit。读端口按 tag 做 CAM 查询；写端口来自 Int/Mem Scheduler 唤醒。
 * 与香山一致，读命中要排除同拍分配的物理寄存器。
 */
package ansercore.backend.dispatch

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

class AnsRegCacheTagTable(
  val params:        BundleParams = BundleParams(),
  val numReadPorts:  Int,
  val numWritePorts: Int,
  val nAlloc:        Int,
  val nCancel:       Int,
) extends Module {
  require(numReadPorts > 0 && numWritePorts > 0, "RegCacheTagTable needs read and write ports")
  require(nAlloc > 0 && nCancel > 0, "RegCacheTagTable needs alloc and cancel ports")

  val numEntries = params.regCacheEntries
  val addrWidth  = params.regCacheIndexWidth
  val tagWidth   = params.physicalRegWidth

  val io = IO(new Bundle {
    val read       = Vec(
      numReadPorts,
      new Bundle {
        val ren   = Input(Bool())
        val tag   = Input(UInt(tagWidth.W))
        val valid = Output(Bool())
        val addr  = Output(UInt(addrWidth.W))
      },
    )
    val allocPregs = Input(Vec(nAlloc, Valid(UInt(tagWidth.W))))
    val write      = Vec(
      numWritePorts,
      new Bundle {
        val wen  = Input(Bool())
        val addr = Input(UInt(addrWidth.W))
        val tag  = Input(UInt(tagWidth.W))
      },
    )
    val cancel     = Input(Vec(nCancel, new CancelSignal(params)))
  })

  val validVec = RegInit(VecInit(Seq.fill(numEntries)(false.B)))
  val tagVec   = Reg(Vec(numEntries, UInt(tagWidth.W)))

  def matchesAlloc(tag: UInt): Bool =
    io.allocPregs.map(a => a.valid && a.bits === tag).reduce(_ || _)

  for (port <- io.read) {
    val matchOH = VecInit(validVec.zip(tagVec).map { case (v, t) => v && t === port.tag })
    port.valid := Mux(port.ren, matchOH.asUInt.orR, false.B) && !matchesAlloc(port.tag)
    port.addr  := OHToUInt(matchOH)
  }

  for (idx <- 0 until numEntries) {
    val writeThis = io.write.map(w => w.wen && w.addr === idx.U)
    val writeTag  = Mux1H(writeThis, io.write.map(_.tag))

    // 同一 tag 被写到另一个槽位时，旧槽位失效（香山的 replace 语义）。
    val replaceHit = io.write.map(w => w.wen && w.tag === tagVec(idx)).reduce(_ || _)
    val allocHit   = io.allocPregs.map(a => a.valid && a.bits === tagVec(idx)).reduce(_ || _)
    val cancelHit  = io.cancel.map(c => c.intWen && c.pdest === tagVec(idx)).reduce(_ || _)

    when(writeThis.reduce(_ || _)) {
      validVec(idx) := true.B
      tagVec(idx)   := writeTag
    }.elsewhen(replaceHit || allocHit || cancelHit) {
      validVec(idx) := false.B
    }
  }
}
