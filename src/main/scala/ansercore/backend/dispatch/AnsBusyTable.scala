/* BusyTable：物理寄存器就绪状态跟踪（设计文档 §5.1）。
 *
 * 分配置忙，实际 WB 清依赖，IQ 唤醒保存下一拍年龄的依赖。
 * data0Cancel 只过滤同拍 0-lat 唤醒；load cancel 撤回已有 ready。
 * 复位全 ready；整数零寄存器恒 ready 且无依赖。
 */
package ansercore.backend.dispatch

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.issue.AnsIssueUtils

class AnsBusyTable(
  val params:       BundleParams = BundleParams(),
  val numReadPorts: Int,
  val numEntries:   Int,
  val nWb:          Int,
  val nWakeUp:      Int,
  val nAlloc:       Int,
  val nCancel:      Int,
  val isInt:        Boolean,
) extends Module {
  require(numReadPorts > 0 && numEntries > 0, "BusyTable needs ports and entries")
  require(nAlloc > 0 && nCancel > 0, "BusyTable needs allocation and cancel ports")

  val addrWidth = params.physicalRegWidth

  val busy  = true.B
  val ready = false.B

  val io = IO(new Bundle {
    val read       = Vec(
      numReadPorts,
      new Bundle {
        val req            = Input(UInt(addrWidth.W))
        val busy           = Output(Bool())
        val loadDependency = Output(Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W)))
      },
    )
    val allocPregs = Input(Vec(nAlloc, Valid(UInt(addrWidth.W))))
    val wbPregs    = Input(Vec(nWb, Valid(UInt(addrWidth.W))))
    val wakeUp     = Input(Vec(nWakeUp, Valid(new AnsIQWakeUpBundle(params))))
    val ldCancel   = Input(Vec(params.loadPipelineWidth, new LoadCancel))
    val cancel     = Input(Vec(nCancel, new CancelSignal(params)))
  })

  def toMask(ports: Vec[Valid[UInt]]): UInt =
    VecInit(ports.map(p => Mux(p.valid, UIntToOH(p.bits, numEntries), 0.U(numEntries.W)))).reduce(_ | _)

  val allocMask = toMask(io.allocPregs)
  val wbMask    = toMask(io.wbPregs)

  val table        = RegInit(VecInit(Seq.fill(numEntries)(ready)))
  val dependencies = RegInit(
    VecInit(Seq.fill(numEntries)(0.U.asTypeOf(Vec(params.loadPipelineWidth, UInt(params.loadDependencyWidth.W)))))
  )
  val wakes        = io.wakeUp.map { w =>
    val wen         = if (isInt) w.bits.rfWen else w.bits.fpWen
    val data0Cancel = io.cancel
      .map(c => (if (isInt) c.intWen else c.fpWen) && c.pdest === w.bits.pdest)
      .reduce(_ || _)
    w.valid && wen && !(w.bits.is0Lat && data0Cancel) && !LoadShouldCancel(w.bits.loadDependency, io.ldCancel)
  }
  for (idx <- 0 until numEntries) {
    val hits = wakes.zip(io.wakeUp).map { case (valid, w) => valid && w.bits.pdest === idx.U }
    val hit  = hits.foldLeft(false.B)(_ || _)
    val dep  =
      if (hits.nonEmpty) Mux1H(PriorityEncoderOH(VecInit(hits).asUInt), io.wakeUp.map(_.bits.loadDependency))
      else 0.U.asTypeOf(dependencies(idx))
    dependencies(idx) := AnsIssueUtils.shiftLoadDependency(dependencies(idx))
    when(allocMask(idx)) {
      table(idx)        := busy
      dependencies(idx) := 0.U.asTypeOf(dep)
    }.elsewhen(wbMask(idx)) {
      table(idx)        := ready
      dependencies(idx) := 0.U.asTypeOf(dep)
    }.elsewhen(hit) {
      table(idx)        := ready
      dependencies(idx) := AnsIssueUtils.shiftLoadDependency(dep)
    }.elsewhen(LoadShouldCancel(dependencies(idx), io.ldCancel)) {
      table(idx) := busy
    }
  }
  if (isInt) {
    table(0)        := ready
    dependencies(0) := 0.U.asTypeOf(dependencies(0))
  }
  for (port <- io.read) {
    val zero      = if (isInt) port.req === 0.U else false.B
    val allocated = io.allocPregs.map(a => a.valid && a.bits === port.req).reduce(_ || _)
    val cancelled = LoadShouldCancel(dependencies(port.req), io.ldCancel)
    port.busy           := !zero && (table(port.req) || allocated || cancelled)
    port.loadDependency := Mux(zero || allocated, 0.U.asTypeOf(dependencies(0)), dependencies(port.req))
  }
}
