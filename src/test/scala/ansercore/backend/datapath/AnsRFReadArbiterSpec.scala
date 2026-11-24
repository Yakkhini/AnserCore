package ansercore.backend.datapath

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AnsRFReadArbiterSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsRFReadArbiter"

  private val groups = Map(
    0 -> Seq(
      AnsReadPortMember(exuIdx = 0, srcIdx = 0, portIdx = 0, priority = 0),
      AnsReadPortMember(exuIdx = 1, srcIdx = 0, portIdx = 0, priority = 1),
    )
  )

  private val invertedGroups = Map(
    0 -> Seq(
      // AnsDataPathParams 会按 priority 排序后送给 arbiter；这里直接给排序后的成员。
      AnsReadPortMember(exuIdx = 1, srcIdx = 0, portIdx = 0, priority = 0),
      AnsReadPortMember(exuIdx = 0, srcIdx = 0, portIdx = 0, priority = 1),
    )
  )

  it should "pick the lowest member and grant address and winner from the same one-hot" in {
    test(new AnsRFReadArbiter(groups, nPorts = 1, nExu = 2, numSrc = 1, addrWidth = 8)) { dut =>
      for (e <- 0 until 2; s <- 0 until 1) {
        dut.io.reqValid(e)(s).poke(false.B)
        dut.io.reqAddr(e)(s).poke(0.U)
      }

      dut.io.reqValid(0)(0).poke(true.B)
      dut.io.reqValid(1)(0).poke(true.B)
      dut.io.reqAddr(0)(0).poke(1.U)
      dut.io.reqAddr(1)(0).poke(2.U)
      dut.io.portAddr(0).expect(1.U)
      dut.io.grant(0)(0).expect(true.B)
      dut.io.grant(1)(0).expect(false.B)

      dut.io.reqValid(0)(0).poke(false.B)
      dut.io.portAddr(0).expect(2.U)
      dut.io.grant(0)(0).expect(true.B)
      dut.io.grant(1)(0).expect(true.B)

      dut.io.reqValid(1)(0).poke(false.B)
      dut.io.portAddr(0).expect(0.U)
    }
  }

  it should "honor explicit priority even when exuIdx order is opposite" in {
    test(new AnsRFReadArbiter(invertedGroups, nPorts = 1, nExu = 2, numSrc = 1, addrWidth = 8)) { dut =>
      dut.io.reqValid(0)(0).poke(true.B)
      dut.io.reqValid(1)(0).poke(true.B)
      dut.io.reqAddr(0)(0).poke(1.U)
      dut.io.reqAddr(1)(0).poke(2.U)
      dut.io.portAddr(0).expect(2.U)
      dut.io.grant(0)(0).expect(false.B)
      dut.io.grant(1)(0).expect(true.B)
    }
  }
}
