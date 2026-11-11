/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package utils

import chisel3._
import chisel3.util._

class SRAMMacro1RW(
  set:      Int,
  dataBits: Int,
) extends Module {
  // Same depth and width share one module. Swap in a real SRAM by this name and these ports; do not touch mem_*.
  override def desiredName = s"SRAMMacro1RW_${set}x${dataBits}"

  val io = IO(new Bundle {
    val en    = Input(Bool())
    val wen   = Input(Bool()) // 1: write, 0: read
    val addr  = Input(UInt(log2Ceil(set).W))
    val wdata = Input(UInt(dataBits.W))
    val rdata = Output(UInt(dataBits.W))
  })
  // require
  require(set > 0)
  require(dataBits > 0)

  private val mem = SyncReadMem(set, UInt(dataBits.W))

  private val ren = io.en && !io.wen
  private val wen = io.en && io.wen

  when(wen) {
    mem.write(io.addr, io.wdata)
  }

  io.rdata := mem.read(io.addr, ren)

}

class SinglePortSRAMTemplate[T <: Data](
  gen:           T,
  set:           Int,
  shouldReset:   Boolean   = false,
  holdRead:      Boolean   = false,
  externalReset: Boolean   = false,
  resetValue:    Option[T] = None,
) extends Module {
  require(set > 0, "SRAM depth must be positive")
  require(gen.getWidth > 0, "SRAM data width must be positive")

  val resetEn           = shouldReset || externalReset
  private val addrWidth = log2Ceil(set)

  val io = IO(new Bundle {
    val en        = Input(Bool())
    val wen       = Input(Bool()) // 1: write, 0: read
    val addr      = Input(UInt(addrWidth.W))
    val wdata     = Input(gen.cloneType)
    val rdata     = Output(gen.cloneType)
    // Ports disappear with the parameters, so a disabled feature leaves no reset pins.
    val resetReq  = if (externalReset) Some(Input(Bool())) else None
    val resetDown = if (resetEn) Some(Output(Bool())) else None
  })

  private val array = Module(new SRAMMacro1RW(set, gen.getWidth))

  private def driveRead(): Unit = {
    val rawResp = array.io.rdata.asTypeOf(gen)
    if (holdRead) {
      val issuedRead    = io.en && !io.wen
      val readRespValid = RegNext(issuedRead, false.B)
      val held          = RegEnable(rawResp, readRespValid)
      io.rdata := Mux(readRespValid, rawResp, held)
    } else {
      io.rdata := rawResp
    }
  }

  if (resetEn) {
    val resetBits = resetValue match {
      case None        => 0.U(gen.getWidth.W)
      case Some(value) =>
        require(value.litOption.isDefined, "SRAM resetValue must be a literal")
        value.litValue.U(gen.getWidth.W)
    }

    // externalReset also refreshes on system reset. resetReq, like reset,
    // restarts the sweep at address 0 and holds there while asserted.
    def sweep(): (Bool, UInt) = {
      val resetting = RegInit(true.B)
      val idxNow    = if (set == 1) {
        when(resetting)(resetting := false.B)
        0.U(addrWidth.W)
      } else {
        val idx = RegInit(0.U(addrWidth.W))
        when(resetting) {
          when(idx === (set - 1).U) {
            resetting := false.B
            idx       := 0.U(addrWidth.W)
          }.otherwise {
            idx := idx +% 1.U
          }
        }
        idx
      }
      (resetting, idxNow)
    }

    val (resetting, idxNow) = if (externalReset) {
      withReset(reset.asBool || io.resetReq.get)(sweep())
    } else {
      sweep()
    }

    io.resetDown.get := !resetting
    array.io.en      := io.en || resetting
    array.io.wen     := io.wen || resetting
    array.io.addr    := Mux(resetting, idxNow, io.addr)
    array.io.wdata   := Mux(resetting, resetBits, io.wdata.asUInt)
  } else {
    array.io.en    := io.en
    array.io.wen   := io.wen
    array.io.addr  := io.addr
    array.io.wdata := io.wdata.asUInt
  }
  driveRead()
}
