package ansercore.backend.datapath

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.issue.AnsIssueParams

class AnsWBCollideCheckerSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsWBCollideChecker"

  it should "grant the lowest exu on a shared write port and report the conflict" in {
    test(new AnsWBCollideChecker()) { dut =>
      dut.io.valid.foreach(_.poke(false.B))
      dut.io.fuType.foreach(_.poke(0.U))
      dut.io.intWen.foreach(_.poke(false.B))
      dut.io.fpWen.foreach(_.poke(false.B))

      // ALU0 与 BJU0 共享 int 写口 0；两者都是固定延迟。
      val first  = AnsIssueParams.exuIdxOf("ALU0")
      val second = AnsIssueParams.exuIdxOf("BJU0")
      dut.io.valid(first).poke(true.B)
      dut.io.valid(second).poke(true.B)
      dut.io.fuType(first).poke(FuType.alu.U)
      dut.io.fuType(second).poke(FuType.brh.U)
      dut.io.intWen(first).poke(true.B)
      dut.io.intWen(second).poke(true.B)

      dut.io.intGrant(first).expect(true.B)
      dut.io.intGrant(second).expect(false.B)
      dut.io.intConflict(first).expect(true.B)
      dut.io.intConflict(second).expect(true.B)

      dut.io.valid(first).poke(false.B)
      dut.io.intGrant(second).expect(true.B)
      dut.io.intConflict(second).expect(false.B)

      // fp 域独立：FEX0 与 FEX2 不在同一写口。
      val fpFirst  = AnsIssueParams.exuIdxOf("FEX0")
      val fpSecond = AnsIssueParams.exuIdxOf("FEX2")
      dut.io.valid(fpFirst).poke(true.B)
      dut.io.valid(fpSecond).poke(true.B)
      dut.io.fuType(fpFirst).poke(FuType.falu.U)
      dut.io.fuType(fpSecond).poke(FuType.falu.U)
      dut.io.fpWen(fpFirst).poke(true.B)
      dut.io.fpWen(fpSecond).poke(true.B)
      dut.io.fpGrant(fpFirst).expect(true.B)
      dut.io.fpGrant(fpSecond).expect(true.B)
      dut.io.fpConflict(fpFirst).expect(false.B)
      dut.io.fpConflict(fpSecond).expect(false.B)

      // FEX0 的 F2I 与 MUL0 的固定 mul 同拍抢 IntWB1：MUL0 p1 赢。
      dut.io.valid.foreach(_.poke(false.B))
      dut.io.intWen.foreach(_.poke(false.B))
      dut.io.fpWen.foreach(_.poke(false.B))
      val mul0 = AnsIssueParams.exuIdxOf("MUL0")
      dut.io.valid(mul0).poke(true.B)
      dut.io.fuType(mul0).poke(FuType.mul.U)
      dut.io.intWen(mul0).poke(true.B)
      dut.io.valid(fpFirst).poke(true.B)
      dut.io.fuType(fpFirst).poke(FuType.fcvt.U)
      dut.io.intWen(fpFirst).poke(true.B)
      dut.io.intGrant(mul0).expect(true.B)
      dut.io.intGrant(fpFirst).expect(false.B)
      dut.io.intConflict(mul0).expect(true.B)
      dut.io.intConflict(fpFirst).expect(true.B)

      // DIV 是 held，不参与 data0 固定写口检查；FEX0 单独请求时不冲突。
      dut.io.fuType(mul0).poke(FuType.div.U)
      dut.io.intGrant(fpFirst).expect(true.B)
      dut.io.intConflict(fpFirst).expect(false.B)

      // FEX0 的 FP 结果不进入 int 冲突检查。
      dut.io.valid(mul0).poke(false.B)
      dut.io.intWen(fpFirst).poke(false.B)
      dut.io.fpWen(fpFirst).poke(true.B)
      dut.io.fuType(fpFirst).poke(FuType.falu.U)
      dut.io.intGrant(fpFirst).expect(true.B)
      dut.io.intConflict(fpFirst).expect(false.B)
    }
  }
}
