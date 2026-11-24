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
import ansercore.backend.issue.AnsIssueUtils

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
        val wen            = Input(Bool())
        val addr           = Input(UInt(addrWidth.W))
        val tag            = Input(UInt(tagWidth.W))
        val is0Lat         = Input(Bool())
        val loadDependency = Input(Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W)))
      },
    )
    val ldCancel   = Input(Vec(params.loadPipelineWidth, new LoadCancel))
    val wbPregs    = Input(Vec(AnsDispatchParams.nIntWb, Valid(UInt(tagWidth.W))))
    val cancel     = Input(Vec(nCancel, new CancelSignal(params)))
  })

  val validVec = RegInit(VecInit(Seq.fill(numEntries)(false.B)))
  val tagVec   = Reg(Vec(numEntries, UInt(tagWidth.W)))

  val dependencies = RegInit(
    VecInit(Seq.fill(numEntries)(0.U.asTypeOf(Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W)))))
  )
  val writeValid   = io.write.map(w =>
    w.wen && !LoadShouldCancel(w.loadDependency, io.ldCancel) &&
      !(w.is0Lat && io.cancel.map(c => c.intWen && c.pdest === w.tag).reduce(_ || _)) && !matchesAlloc(w.tag)
  )

  def matchesAlloc(tag: UInt): Bool =
    io.allocPregs.map(a => a.valid && a.bits === tag).reduce(_ || _)

  for (port <- io.read) {
    val matchOH = VecInit(
      validVec.indices.map(i =>
        validVec(i) && tagVec(i) === port.tag && !LoadShouldCancel(dependencies(i), io.ldCancel)
      )
    )
    port.valid := Mux(port.ren, matchOH.asUInt.orR, false.B) && !matchesAlloc(port.tag)
    port.addr  := OHToUInt(matchOH)
  }

  for (idx <- 0 until numEntries) {
    val writeThis      = io.write.zip(writeValid).map { case (w, v) => v && w.addr === idx.U }
    val winner         = PriorityEncoderOH(VecInit(writeThis).asUInt)
    val writeTag       = Mux1H(winner, io.write.map(_.tag))
    val writeDep       = Mux1H(winner, io.write.map(_.loadDependency))
    val writeConfirmed = io.wbPregs.map(w => w.valid && w.bits === writeTag).reduce(_ || _)
    val replaceHit     = io.write.zip(writeValid).map { case (w, v) => v && w.tag === tagVec(idx) }.reduce(_ || _)
    val allocHit       = matchesAlloc(tagVec(idx))
    val loadCancel     = LoadShouldCancel(dependencies(idx), io.ldCancel)
    val confirmed      = io.wbPregs.map(w => w.valid && w.bits === tagVec(idx)).reduce(_ || _)
    dependencies(idx) := AnsIssueUtils.shiftLoadDependency(dependencies(idx))
    when(writeThis.reduce(_ || _)) {
      validVec(idx)     := true.B
      tagVec(idx)       := writeTag
      dependencies(idx) := Mux(writeConfirmed, 0.U.asTypeOf(writeDep), AnsIssueUtils.shiftLoadDependency(writeDep))
    }.elsewhen(replaceHit || allocHit || (loadCancel && !confirmed)) {
      validVec(idx) := false.B
    }.elsewhen(confirmed) {
      dependencies(idx) := 0.U.asTypeOf(dependencies(idx))
    }
  }
}
