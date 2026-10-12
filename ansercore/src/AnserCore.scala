/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package ansercore

import chisel3._
import chisel3.experimental.dataview._

import circt.stage.ChiselStage

class AnserCore extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  io.out := io.in + 1.U
}

object Main extends App {
  println("Generating the AnserCore hardware design...")

  ChiselStage.emitSystemVerilogFile(
    gen         = new AnserCore(),
    args        = Array("--target-dir", "out/verilog"),
    firtoolOpts = Array(
      "-preserve-aggregate=1d-vec",
      "-disable-layers=Verification",
    ),
  )

  ChiselStage.emitSystemVerilogFile(
    gen         = new AnserCore(),
    args        = Array("--target-dir", "out/sta"),
    firtoolOpts = Array(
      "--lowering-options=disallowLocalVariables,disallowExpressionInliningInPorts,disallowPackedArrays",
      "-disable-layers=Verification",
    ),
  )

}
