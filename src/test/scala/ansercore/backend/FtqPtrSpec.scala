package ansercore.backend

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._

class FtqPtrHarness extends Module {
  val params = BundleParams()

  val io = IO(new Bundle {
    val leftFlag   = Input(Bool())
    val leftValue  = Input(UInt(params.ftqPtrWidth.W))
    val rightFlag  = Input(Bool())
    val rightValue = Input(UInt(params.ftqPtrWidth.W))
    val after      = Output(Bool())
    val same       = Output(Bool())
  })

  val left  = Wire(new FtqPtr(params))
  val right = Wire(new FtqPtr(params))
  left.flag   := io.leftFlag
  left.value  := io.leftValue
  right.flag  := io.rightFlag
  right.value := io.rightValue

  io.after := left.isAfter(right)
  io.same  := left === right
}

class FtqPtrSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "FtqPtr"

  it should "compare age across the circular-queue wrap point" in {
    test(new FtqPtrHarness) { dut =>
      dut.io.leftFlag.poke(false.B)
      dut.io.leftValue.poke(63.U)
      dut.io.rightFlag.poke(false.B)
      dut.io.rightValue.poke(0.U)
      dut.io.after.expect(true.B)
      dut.io.same.expect(false.B)

      dut.io.leftFlag.poke(true.B)
      dut.io.leftValue.poke(0.U)
      dut.io.after.expect(true.B)

      dut.io.leftFlag.poke(false.B)
      dut.io.leftValue.poke(1.U)
      dut.io.rightFlag.poke(true.B)
      dut.io.rightValue.poke(0.U)
      dut.io.after.expect(false.B)

      dut.io.leftValue.poke(0.U)
      dut.io.rightFlag.poke(false.B)
      dut.io.rightValue.poke(0.U)
      dut.io.same.expect(true.B)
    }
  }
}
