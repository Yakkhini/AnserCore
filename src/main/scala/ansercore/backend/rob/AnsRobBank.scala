package ansercore.backend.rob

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.BundleParams

class AnsRobBankIO(val params: BundleParams, val config: AnsRobParams) extends Bundle {
  val readAddr = Input(UInt(config.rowAddrWidth.W))
  val readData = Output(new AnsRobEntry(params))

  val writeAddr  = Input(UInt(config.rowAddrWidth.W))
  val writeData  = Input(Valid(new AnsRobEntry(params)))
  val updateData = Output(new AnsRobEntry(params))
  val clear      = Input(Vec(config.bankEntries, Bool()))
}

/** One synchronous ROB bank with combinational read access.
  *
  * The bank deliberately owns only storage and valid-bit clearing. Commit and exception policy stay in the control
  * modules so the storage is reusable for normal commit and Walk paths.
  */
class AnsRobBank(val params: BundleParams, val config: AnsRobParams) extends Module {
  val io = IO(new AnsRobBankIO(params, config))

  val entries = RegInit(
    VecInit(Seq.fill(config.bankEntries)(0.U.asTypeOf(new AnsRobEntry(params))))
  )

  io.readData   := entries(io.readAddr)
  io.updateData := entries(io.writeAddr)

  for (row <- 0 until config.bankEntries) {
    when(io.clear(row)) {
      entries(row).valid := false.B
    }
  }

  when(io.writeData.valid) {
    entries(io.writeAddr) := io.writeData.bits
  }
}
