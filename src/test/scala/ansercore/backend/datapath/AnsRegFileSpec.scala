package ansercore.backend.datapath

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnsRegFileSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsRegFile"

  private val DataWidth = 64
  private val AddrWidth = 8

  private def params(
    banks:      Int,
    splits:     Int,
    hasZero:    Boolean = false,
    numEntries: Int     = 16,
    numRead:    Int     = 4,
    numWrite:   Int     = 4,
  ): AnsRegFileParams =
    AnsRegFileParams(
      numEntries    = numEntries,
      dataWidth     = DataWidth,
      addrWidth     = AddrWidth,
      numRead       = numRead,
      numWrite      = numWrite,
      numAddrBanks  = banks,
      numDataSplits = splits,
      hasZero       = hasZero,
    )

  private def initWrites(dut: AnsRegFile): Unit = {
    for (w <- 0 until dut.p.numWrite) {
      dut.io.write(w).wen.poke(false.B)
      dut.io.write(w).addr.poke(0.U(AddrWidth.W))
      dut.io.write(w).data.poke(0.U(DataWidth.W))
    }
  }

  private def writeOne(dut: AnsRegFile, port: Int, addr: Int, data: BigInt): Unit = {
    dut.io.write(port).wen.poke(true.B)
    dut.io.write(port).addr.poke(addr.U(AddrWidth.W))
    dut.io.write(port).data.poke(data.U(DataWidth.W))
    dut.clock.step()
    dut.io.write(port).wen.poke(false.B)
  }

  private def expectRead(dut: AnsRegFile, port: Int, addr: Int, data: BigInt): Unit = {
    dut.io.read(port).addr.poke(addr.U(AddrWidth.W))
    dut.io.read(port).data.expect(data.U(DataWidth.W))
  }

  private def pattern(i: Int): BigInt =
    (BigInt("0123456789abcdef", 16) ^ (BigInt(i) << 8)) & ((BigInt(1) << DataWidth) - 1)

  it should "support configurable address banks and data splits" in {
    for ((banks, splits) <- Seq((1, 1), (4, 1), (1, 4), (4, 4), (2, 2), (8, 4))) {
      test(new AnsRegFile(params(banks, splits))) { dut =>
        initWrites(dut)
        dut.clock.step(2)
        for (i <- 0 until 16) {
          writeOne(dut, 0, i, pattern(i))
        }
        for (i <- 0 until 16) {
          expectRead(dut, 0, i, pattern(i))
        }
      }
    }
  }

  it should "read different banks and entries in the same cycle" in {
    test(new AnsRegFile(params(4, 4))) { dut =>
      initWrites(dut)
      for (i <- 0 until 16) {
        writeOne(dut, 0, i, pattern(i))
      }
      val addresses = Seq(0, 1, 4, 5)
      addresses.zipWithIndex.foreach { case (addr, r) =>
        dut.io.read(r).addr.poke(addr.U(AddrWidth.W))
      }
      addresses.zipWithIndex.foreach { case (addr, r) =>
        dut.io.read(r).data.expect(pattern(addr).U(DataWidth.W))
      }
    }
  }

  it should "write different banks and entries in the same cycle" in {
    test(new AnsRegFile(params(4, 4))) { dut =>
      initWrites(dut)
      val addresses = Seq(0, 4, 8, 12) // 同一 bank、不同 entry
      addresses.zipWithIndex.foreach { case (addr, w) =>
        dut.io.write(w).wen.poke(true.B)
        dut.io.write(w).addr.poke(addr.U(AddrWidth.W))
        dut.io.write(w).data.poke(pattern(addr).U(DataWidth.W))
      }
      dut.clock.step()
      initWrites(dut)
      addresses.foreach(addr => expectRead(dut, 0, addr, pattern(addr)))
    }
  }

  it should "keep integer address zero at zero when hasZero is set" in {
    test(new AnsRegFile(params(4, 4, hasZero = true))) { dut =>
      initWrites(dut)
      writeOne(dut, 0, 0, BigInt("deadbeef", 16))
      expectRead(dut, 0, 0, 0)
      writeOne(dut, 0, 1, BigInt("1234", 16))
      expectRead(dut, 0, 0, 0)
      expectRead(dut, 0, 1, BigInt("1234", 16))
    }
  }

  it should "map the 180-entry integer PRF across 4 banks and 4 splits" in {
    test(new AnsRegFile(params(4, 4, hasZero = true, numEntries = 180, numRead = 10))) { dut =>
      initWrites(dut)
      val addresses = Seq(1, 2, 3, 4, 5, 44, 45, 89, 90, 179)
      addresses.foreach(addr => writeOne(dut, 0, addr, pattern(addr)))
      addresses.foreach(addr => expectRead(dut, 0, addr, pattern(addr)))
      // 越界地址读回 0
      expectRead(dut, 0, 200, 0)
    }
  }
}
