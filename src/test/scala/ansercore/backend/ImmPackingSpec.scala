package ansercore.backend

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._

class ImmPackHarness extends Module {
  val io = IO(new Bundle {
    val instr   = Input(UInt(32.W))
    val immType = Input(UInt(ImmType.width.W))
    val packed  = Output(UInt(ImmPacking.width.W))
  })

  io.packed := ImmPacking.pack(io.instr, io.immType)
}

class ImmExtendHarness extends Module {
  val io = IO(new Bundle {
    val packed   = Input(UInt(ImmPacking.width.W))
    val immType  = Input(UInt(ImmType.width.W))
    val extended = Output(UInt(64.W))
  })

  io.extended := ImmPacking.extend(io.packed, io.immType, 64)
}

class ImmRoundTripHarness extends Module {
  val io = IO(new Bundle {
    val instr    = Input(UInt(32.W))
    val immType  = Input(UInt(ImmType.width.W))
    val packed   = Output(UInt(ImmPacking.width.W))
    val extended = Output(UInt(64.W))
  })

  val packed = ImmPacking.pack(io.instr, io.immType)
  io.packed   := packed
  io.extended := ImmPacking.extend(packed, io.immType, 64)
}

class ImmPackingSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val fliS = (BigInt(0x78) << 25) | (BigInt(1) << 20) | (BigInt(17) << 15) | (BigInt(3) << 7) | 0x53

  behavior of "ImmPacking"

  it should "pack instruction bit fields without extending them" in {
    test(new ImmPackHarness) { dut =>
      def check(instr: BigInt, immType: Int, expected: BigInt): Unit = {
        dut.io.instr.poke(instr.U)
        dut.io.immType.poke(immType.U(ImmType.width.W))
        dut.io.packed.expect(expected.U)
      }

      check(BigInt("FFF00093", 16), ImmType.i, BigInt("FFF", 16)) // addi x1, x0, -1
      check(BigInt("FE20AE23", 16), ImmType.s, BigInt("FFC", 16)) // sw x2, -4(x1)
      check(BigInt("80208063", 16), ImmType.b, BigInt("800", 16)) // beq x1, x2, -4096
      check(BigInt("FFFFFFB7", 16), ImmType.u, BigInt("FFFFF", 16)) // lui x1, 0xfffff
      check(BigInt("FFFFF0EF", 16), ImmType.j, BigInt("FFFFF", 16)) // jal x1, -2
      check(fliS, ImmType.fli, 17) // fli.s f3, 17
    }
  }

  it should "extend packed immediates to XLEN by encoding type" in {
    test(new ImmExtendHarness) { dut =>
      def check(immType: Int, packed: BigInt, expected: BigInt): Unit = {
        dut.io.immType.poke(immType.U(ImmType.width.W))
        dut.io.packed.poke(packed.U(ImmPacking.width.W))
        dut.io.extended.expect(expected.U(64.W))
      }

      check(ImmType.i, BigInt("FFF", 16), BigInt("FFFFFFFFFFFFFFFF", 16))
      check(ImmType.s, BigInt("FFC", 16), BigInt("FFFFFFFFFFFFFFFC", 16))
      check(ImmType.b, BigInt("800", 16), BigInt("FFFFFFFFFFFFF000", 16))
      check(ImmType.u, BigInt("FFFFF", 16), BigInt("FFFFFFFFFFFFF000", 16))
      check(ImmType.j, BigInt("FFFFF", 16), BigInt("FFFFFFFFFFFFFFFE", 16))
      check(ImmType.z, BigInt("F00", 16), BigInt("F00", 16))
      check(ImmType.fli, 17, 17)
      check(ImmType.none, BigInt("FFFFF", 16), 0)
    }
  }

  it should "round-trip instruction encodings through pack and extend" in {
    test(new ImmRoundTripHarness) { dut =>
      val cases: Seq[(BigInt, Int, BigInt, BigInt)] = Seq(
        (BigInt("FFF00093", 16), ImmType.i, BigInt("FFF", 16), BigInt("FFFFFFFFFFFFFFFF", 16)),
        (BigInt("FE20AE23", 16), ImmType.s, BigInt("FFC", 16), BigInt("FFFFFFFFFFFFFFFC", 16)),
        (BigInt("80208063", 16), ImmType.b, BigInt("800", 16), BigInt("FFFFFFFFFFFFF000", 16)),
        (BigInt("FFFFFFB7", 16), ImmType.u, BigInt("FFFFF", 16), BigInt("FFFFFFFFFFFFF000", 16)),
        (BigInt("FFFFF0EF", 16), ImmType.j, BigInt("FFFFF", 16), BigInt("FFFFFFFFFFFFFFFE", 16)),
        (fliS, ImmType.fli, 17, 17),
      )
      cases.foreach { case (instr, immType, packed, extended) =>
        dut.io.instr.poke(instr.U)
        dut.io.immType.poke(immType.U(ImmType.width.W))
        dut.io.packed.expect(packed.U)
        dut.io.extended.expect(extended.U(64.W))
      }
    }
  }
}
