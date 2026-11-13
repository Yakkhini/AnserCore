package ansercore.backend

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.decode.AnsDecodeExceptionBits
import ansercore.backend.dispatch.AnsDispatchParams

/** Backend 对外接口测试。
  *
  * Backend 只暴露前端输入、stall 和 debug 镜像；ROB/CSR/LSQ/IQ 的连接都在 Backend 内部。 需要驱动这些内部总线的用例放在 CtrlBlockSpec。
  */
class BackendSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams(renameWidth = 4)

  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def fma(rs3: Int, fmt: Int, rs2: Int, rs1: Int, rm: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(rs3) << 27) | (BigInt(fmt) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(rm) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def clearStatic(inst: StaticInst): Unit = {
    inst.instr.poke(0.U)
    inst.pc.poke(0.U)
    inst.exceptionBits.poke(0.U)
    inst.predictedTaken.poke(false.B)
    inst.isLastInFtqEntry.poke(false.B)
    inst.ftqPtr.value.poke(0.U)
    inst.ftqPtr.flag.poke(false.B)
    inst.ftqOffset.poke(0.U)
  }

  private def idle(dut: AnsBackend): Unit = {
    for (lane <- 0 until params.renameWidth) {
      dut.io.in(lane).valid.poke(false.B)
      clearStatic(dut.io.in(lane).bits)
    }
  }

  private def resetBackend(dut: AnsBackend): Unit = {
    dut.reset.poke(true.B)
    idle(dut)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step(4)
  }

  private def drive(dut: AnsBackend, lane: Int, instr: BigInt, pc: Int = 0x1000): Unit = {
    dut.io.in(lane).valid.poke(true.B)
    clearStatic(dut.io.in(lane).bits)
    dut.io.in(lane).bits.instr.poke(instr.U)
    dut.io.in(lane).bits.pc.poke(pc.U)
    dut.io.in(lane).bits.ftqPtr.value.poke(1.U)
    dut.io.in(lane).bits.ftqPtr.flag.poke(false.B)
    dut.io.in(lane).bits.ftqOffset.poke(lane.U)
  }

  behavior of "AnsBackend"

  it should "accept a group at T0 and expose the T1 result at T1" in {
    test(new AnsBackend(params)) { dut =>
      resetBackend(dut)
      val addi = iType(11, 0, 0, 1, 0x13)
      drive(dut, 0, addi, pc = 0x2000)
      dut.io.in(0).ready.expect(true.B)
      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)

      dut.io.debug.renameInValid(0).expect(true.B)
      dut.io.debug.renameIn(0).instr.expect(addi.U)
      dut.io.debug.renameIn(0).pc.expect(0x2000.U)
      dut.io.debug.renameIn(0).fuOpType.expect(ALUOpType.add)

      dut.clock.step()
      dut.io.debug.renameInValid(0).expect(false.B)
    }
  }

  it should "drive both RAT banks and the FMA third source from the decoded group" in {
    test(new AnsBackend(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, fma(13, 0, 12, 11, 0, 10, 0x43))
      dut.io.debug.intRat(0)(0).addr.expect(11.U)
      dut.io.debug.intRat(0)(1).addr.expect(12.U)
      dut.io.debug.fpRat(0)(0).addr.expect(11.U)
      dut.io.debug.fpRat(0)(1).addr.expect(12.U)
      dut.io.debug.fpRat(0)(2).addr.expect(13.U)

      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)
      dut.io.debug.renameIn(0).logicalSrc(2).expect(13.U)
      dut.io.debug.renameIn(0).srcType(2).expect(SrcType.floating.U)
    }
  }

  it should "fuse a safe same-block pair and expose fusion metadata" in {
    test(new AnsBackend(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, iType(1, 6, 1, 5, 0x13))    // slli x5, x6, 1
      drive(dut, 1, rType(0, 7, 5, 0, 5, 0x33)) // add x5, x5, x7
      dut.clock.step()
      dut.io.in.foreach(_.valid.poke(false.B))

      dut.io.debug.renameInValid(0).expect(true.B)
      dut.io.debug.renameInValid(1).expect(false.B)
      dut.io.debug.renameIn(0).fuType.expect(FuType.bku.U)
      dut.io.debug.renameIn(0).fuOpType.expect(BKUOpType.sh1add)
      dut.io.debug.renameIn(0).logicalSrc(1).expect(7.U)
      dut.io.debug.renameIn(0).srcType(1).expect(SrcType.integer.U)
      dut.io.debug.fusionInfo(0).rs2FromRs2.expect(true.B)
    }
  }

  it should "carry a frontend exception through decode into T1" in {
    test(new AnsBackend(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, iType(1, 0, 0, 1, 0x13))
      dut.io.in(0).bits.exceptionBits.poke((BigInt(1) << FetchExceptionBits.misaligned).U)
      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)

      val bits     = dut.io.debug.renameIn(0).exceptionBits.peek().litValue
      val expected = BigInt(1) << AnsDecodeExceptionBits.fetchMisaligned
      (bits & expected) shouldBe expected
    }
  }

  it should "report an illegal instruction through the internal CSR trap bus" in {
    test(new AnsBackend(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, 0) // table miss -> illegal
      dut.clock.step()
      dut.io.in(0).valid.poke(false.B)

      val bits     = dut.io.debug.renameIn(0).exceptionBits.peek().litValue
      val expected = BigInt(1) << AnsDecodeExceptionBits.illegalInstruction
      (bits & expected) shouldBe expected
      dut.io.debug.trapInstInfo.valid.expect(true.B)
      dut.io.debug.trapInstInfo.bits.instr.expect(0.U)
    }
  }

  it should "rename a decode group and dispatch it to the internal IQ buses" in {
    test(new AnsBackend(params)) { dut =>
      resetBackend(dut)
      drive(dut, 0, iType(1, 0, 0, 1, 0x13), pc = 0x1000) // addi x1, x0, 1
      drive(dut, 1, iType(2, 1, 0, 2, 0x13), pc = 0x1004) // addi x2, x1, 2
      dut.clock.step()
      dut.io.in.foreach(_.valid.poke(false.B))
      dut.clock.step()

      dut.io.debug.iqEnq(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ0)).valid.expect(true.B)
      dut.io.debug.iqEnq(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ0)).bits.pdest.expect(1.U)
      dut.io.debug.iqEnq(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ1)).valid.expect(true.B)
      dut.io.debug.iqEnq(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ1)).bits.psrc(0).expect(1.U)
      dut.io.debug.iqEnq(AnsDispatchParams.iqEnqPort(AnsDispatchParams.intIQ1)).bits.pdest.expect(2.U)
    }
  }
}
