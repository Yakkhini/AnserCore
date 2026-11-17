/* BusyTable：物理寄存器就绪状态跟踪（设计文档 §5.1）。
 *
 * 本版只维护 ready/busy，不维护 loadDependency（见 Dispatch 计划）：
 * - 分配：pdest 置忙（同拍读口旁路）；
 * - 写回 / IQ 唤醒：置就绪；
 * - data0CancelToDispatch：重新置忙。
 * 表项用 busy/ready 编码：busy 表示未就绪，ready 表示已就绪。
 * 复位全 ready，与香山一致；整型物理寄存器 0 恒 ready。
 */
package ansercore.backend.dispatch

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._

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
        val req  = Input(UInt(addrWidth.W))
        val busy = Output(Bool())
      },
    )
    val allocPregs = Input(Vec(nAlloc, Valid(UInt(addrWidth.W))))
    val wbPregs    = Input(Vec(nWb, Valid(UInt(addrWidth.W))))
    val wakeUp     = Input(Vec(nWakeUp, Valid(new AnsIQWakeUpBundle(params))))
    val cancel     = Input(Vec(nCancel, new CancelSignal(params)))
  })

  def toMask(ports: Vec[Valid[UInt]]): UInt =
    VecInit(ports.map(p => Mux(p.valid, UIntToOH(p.bits, numEntries), 0.U(numEntries.W)))).reduce(_ | _)

  val allocMask = toMask(io.allocPregs)
  val wbMask    = toMask(io.wbPregs)

  val cancelMask = VecInit(io.cancel.map { c =>
    val wen = if (isInt) c.intWen else c.fpWen
    Mux(wen, UIntToOH(c.pdest, numEntries), 0.U(numEntries.W))
  }).reduce(_ | _)

  val wakeUpMask = io.wakeUp
    .map { w =>
      val wen = if (isInt) w.bits.rfWen else w.bits.fpWen
      Mux(w.valid && wen, UIntToOH(w.bits.pdest, numEntries), 0.U(numEntries.W))
    }
    .foldLeft(0.U(numEntries.W))(_ | _) & (~cancelMask).asUInt

  val table = RegInit(VecInit(Seq.fill(numEntries)(ready)))

  for (idx <- 0 until numEntries) {
    when((wakeUpMask | wbMask)(idx)) {
      table(idx) := ready
    }.elsewhen((allocMask | cancelMask)(idx)) {
      table(idx) := busy
    }
  }
  if (isInt) {
    table(0) := ready
  }

  for ((port, idx) <- io.read.zipWithIndex) {
    if (isInt) {
      val readBypass = io.allocPregs.map(a => a.valid && a.bits === port.req).reduce(_ || _)
      port.busy := Mux(port.req === 0.U, false.B, table(port.req) || readBypass)
    } else {
      val readBypass = io.allocPregs.map(a => a.valid && a.bits === port.req).reduce(_ || _)
      port.busy := table(port.req) || readBypass
    }
  }
}
