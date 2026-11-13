package ansercore.backend.decode

import chisel3._
import chisel3.util.BitPat
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.exu.csr.AnsCsrDecodeState

/** Scalar DecodeUnit and decode-table contract tests.
  *
  * DecodeUnit is combinational and is tested without a clock. The decode-to-rename boundary (T1, fusion patch, RAT
  * early read and trap reporting) is owned by Backend and tested in BackendSpec.
  */
class DecodeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  private val params = BundleParams(renameWidth = 4)

  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def sType(imm: Int, rs2: Int, rs1: Int, funct3: Int, opcode: Int): BigInt = {
    val encoded = imm & 0xfff
    ((BigInt(encoded >> 5) & 0x7f) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      ((BigInt(encoded) & 0x1f) << 7) | BigInt(opcode)
  }

  private def bType(rs2: Int, rs1: Int, funct3: Int, offset: Int): BigInt = {
    val imm = offset & 0x1fff
    ((BigInt(imm >> 12) & 1) << 31) | ((BigInt(imm >> 5) & 0x3f) << 25) |
      (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      ((BigInt(imm >> 1) & 0xf) << 8) | ((BigInt(imm >> 11) & 1) << 7) | 0x63
  }

  private def uType(imm20: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def jType(imm: Int, rd: Int): BigInt = {
    val encoded = imm & 0x1fffff
    ((BigInt(encoded >> 20) & 1) << 31) | ((BigInt(encoded >> 1) & 0x3ff) << 21) |
      ((BigInt(encoded >> 11) & 1) << 20) | ((BigInt(encoded >> 12) & 0xff) << 12) |
      (BigInt(rd) << 7) | 0x6f
  }

  private def fType(funct7: Int, rs2: Int, rs1: Int, rm: Int, rd: Int): BigInt =
    rType(funct7, rs2, rs1, rm, rd, 0x53)

  private def fma(rs3: Int, fmt: Int, rs2: Int, rs1: Int, rm: Int, rd: Int, opcode: Int): BigInt =
    (BigInt(rs3) << 27) | (BigInt(fmt) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(rm) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  private def atomic(funct5: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(funct5) << 27) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | 0x2f

  private def exception(dut: AnsDecodeUnit): BigInt = dut.io.deq.decodedInst.exceptionBits.peek().litValue

  private def clearCsr(state: AnsCsrDecodeState): Unit = {
    state.fsOff.poke(false.B)
    state.frmIllegal.poke(false.B)
    state.wfiIllegal.poke(false.B)
    state.wrsNtoIllegal.poke(false.B)
    state.sretIllegal.poke(false.B)
    state.mretIllegal.poke(false.B)
    state.sfenceVmaIllegal.poke(false.B)
    state.cboZeroIllegal.poke(false.B)
    state.cboCleanFlushIllegal.poke(false.B)
    state.cboInvalIllegal.poke(false.B)
  }

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

  private def driveUnit(dut: AnsDecodeUnit, instruction: BigInt): Unit = {
    dut.io.enq.ctrlFlow.instr.poke(instruction.U)
    dut.io.enq.ctrlFlow.pc.poke(0x1000.U)
    dut.io.enq.ctrlFlow.exceptionBits.poke(0.U)
    dut.io.enq.ctrlFlow.predictedTaken.poke(false.B)
    dut.io.enq.ctrlFlow.isLastInFtqEntry.poke(false.B)
    dut.io.enq.ctrlFlow.ftqPtr.value.poke(0.U)
    dut.io.enq.ctrlFlow.ftqPtr.flag.poke(false.B)
    dut.io.enq.ctrlFlow.ftqOffset.poke(0.U)
    clearCsr(dut.io.enq.fromCsr)
  }

  private def patternMatches(pattern: BitPat, instruction: BigInt): Boolean =
    pattern.getWidth == params.instructionWidth && (instruction & pattern.mask) == pattern.value

  behavior of "AnsDecodeUnit"

  it should "decode RV64I/M/A, control flow, loads/stores, and x0 writes" in {
    test(new AnsDecodeUnit(params)) { dut =>
      val cases = Seq(
        (iType(7, 2, 0, 3, 0x13), FuType.alu, ALUOpType.add, true, SrcType.integer),    // ADDI
        (rType(0, 4, 3, 0, 5, 0x33), FuType.alu, ALUOpType.add, true, SrcType.integer), // ADD
        (rType(1, 4, 3, 0, 5, 0x33), FuType.mul, MULOpType.mul, true, SrcType.integer), // MUL
        (rType(1, 4, 3, 4, 5, 0x33), FuType.div, DIVOpType.div, true, SrcType.integer), // DIV
        (iType(8, 2, 2, 3, 0x03), FuType.ldu, LSUOpType.lw, true, SrcType.integer),     // LW
        (sType(12, 4, 2, 2, 0x23), FuType.stu, LSUOpType.sw, false, SrcType.integer),   // SW
        (bType(4, 3, 0, 8), FuType.brh, BRUOpType.beq, false, SrcType.integer),         // BEQ
        (jType(16, 1), FuType.jmp, JumpOpType.jal, true, SrcType.none),                 // JAL
        (uType(0x12345, 7, 0x37), FuType.alu, ALUOpType.lui, true, SrcType.none),       // LUI
        (atomic(2, 0, 2, 2, 5), FuType.mou, LSUOpType.lrW, true, SrcType.integer),      // LR.W
      )

      cases.foreach { case (instruction, fu, op, writesInt, source0) =>
        driveUnit(dut, instruction)
        exception(dut) shouldBe 0
        dut.io.deq.decodedInst.fuType.expect(fu.U)
        dut.io.deq.decodedInst.fuOpType.expect(op)
        dut.io.deq.decodedInst.intWen.expect(writesInt.B)
        dut.io.deq.decodedInst.srcType(0).expect(source0.U)
      }

      driveUnit(dut, iType(0, 1, 0, 0, 0x13))
      dut.io.deq.decodedInst.fuType.expect(FuType.alu.U)
      dut.io.deq.decodedInst.intWen.expect(false.B)
      dut.io.deq.decodedInst.isMove.expect(false.B)
    }
  }

  it should "decode RVB23 B, F/D, Zfa, hints, CBO, prefetch, and MOP" in {
    test(new AnsDecodeUnit(params)) { dut =>
      val bitmanip = Seq(
        (iType(0x604, 3, 5, 5, 0x13), BKUOpType.rori),
        (iType(0x6b8, 3, 5, 5, 0x13), BKUOpType.rev8),
        (rType(0x20, 4, 3, 7, 5, 0x33), BKUOpType.andn),
        (rType(0x5, 4, 3, 6, 5, 0x33), BKUOpType.max),
        (rType(0x7, 4, 3, 5, 5, 0x33), BKUOpType.czeroEqz),
      )
      bitmanip.foreach { case (instruction, op) =>
        driveUnit(dut, instruction)
        exception(dut) shouldBe 0
        dut.io.deq.decodedInst.fuType.expect(FuType.bku.U)
        dut.io.deq.decodedInst.fuOpType.expect(op)
      }

      val floating = Seq(
        (fType(0x00, 2, 1, 0, 3), FALUOpType.faddS, true, false),
        (fType(0x01, 2, 1, 0, 3), FALUOpType.faddD, true, false),
        (fType(0x50, 2, 1, 2, 3), FALUOpType.feqS, false, true),
        (fType(0x51, 2, 1, 2, 3), FALUOpType.feqD, false, true),
      )
      floating.foreach { case (instruction, op, fpWen, intWen) =>
        driveUnit(dut, instruction)
        exception(dut) shouldBe 0
        dut.io.deq.decodedInst.fuOpType.expect(op)
        dut.io.deq.decodedInst.fpWen.expect(fpWen.B)
        dut.io.deq.decodedInst.intWen.expect(intWen.B)
      }

      val fmadd = fma(9, 0, 8, 7, 3, 6, 0x43)
      driveUnit(dut, fmadd)
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.srcType(2).expect(SrcType.floating.U)
      dut.io.deq.decodedInst.logicalSrc(2).expect(9.U)
      dut.io.deq.decodedInst.rm.expect(3.U)
      dut.io.deq.decodedInst.needsRoundingMode.expect(true.B)

      val zfa = Seq(
        (fType(0x14, 2, 1, 2, 3), FALUOpType.fminmS),
        (fType(0x15, 2, 1, 2, 3), FALUOpType.fminmD),
        (fType(0x50, 2, 1, 4, 3), FALUOpType.fleqS),
        (fType(0x51, 2, 1, 5, 3), FALUOpType.fltqD),
      )
      zfa.foreach { case (instruction, op) =>
        driveUnit(dut, instruction)
        exception(dut) shouldBe 0
        dut.io.deq.decodedInst.fuOpType.expect(op)
      }

      val fli = fType(0x78, 1, 17, 0, 3)
      driveUnit(dut, fli)
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.imm.expect(17.U)
      dut.io.deq.decodedInst.immType.expect(ImmType.fli.U(ImmType.width.W))
      dut.io.deq.decodedInst.srcType(0).expect(SrcType.none.U)

      val prefetchI = iType(0, 4, 6, 0, 0x13)
      driveUnit(dut, prefetchI)
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.fuType.expect(FuType.ldu.U)
      dut.io.deq.decodedInst.fuOpType.expect(LSUOpType.prefetchI)

      val cboZero = iType(4, 4, 2, 0, 0x0f)
      driveUnit(dut, cboZero)
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.fuOpType.expect(LSUOpType.cboZero)

      val ntlP1 = rType(0, 2, 0, 0, 0, 0x33)
      driveUnit(dut, ntlP1)
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.fuOpType.expect(ALUOpType.nop)
      dut.io.deq.decodedInst.srcType.foreach(_.expect(SrcType.none.U))
      dut.io.deq.decodedInst.intWen.expect(false.B)

      val mopR = BigInt("81c041f3", 16)
      driveUnit(dut, mopR)
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.fuOpType.expect(ALUOpType.mopR)
      dut.io.deq.decodedInst.srcType(0).expect(SrcType.none.U)
      dut.io.deq.decodedInst.intWen.expect(true.B)

      driveUnit(dut, BigInt("ffffffff", 16))
      val exceptionBits = exception(dut)
      (exceptionBits & (BigInt(1) << AnsDecodeExceptionBits.illegalInstruction)) shouldBe 1
    }
  }

  it should "decode compressed C, Zcb, Zcmop and reject reserved encodings" in {
    test(new AnsDecodeUnit(params)) { dut =>
      val compressed = Seq(
        (BigInt("0085", 16), ALUOpType.add),  // C.ADDI x1, 1
        (BigInt("4085", 16), ALUOpType.add),  // C.LI x1, 1
        (BigInt("6105", 16), ALUOpType.add),  // C.ADDI16SP
        (BigInt("8082", 16), JumpOpType.jalr), // C.JR x1
      )
      compressed.foreach { case (instruction, op) =>
        driveUnit(dut, instruction)
        exception(dut) shouldBe 0
        dut.io.deq.decodedInst.fuOpType.expect(op)
        // RVC expansion keeps the original instruction for reporting; the
        // test also checks that the decoded operation is available immediately.
        dut.io.deq.decodedInst.instr.expect(instruction.U)
      }

      driveUnit(dut, BigInt("0000", 16)) // C.ADDI4SPN with zero immediate
      exception(dut) shouldBe 1

      driveUnit(dut, BigInt("6081", 16)) // C.MOP.1
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.intWen.expect(false.B)
      dut.io.deq.decodedInst.logicalDest.expect(0.U)

      driveUnit(dut, BigInt("9c61", 16)) // C.ZEXT.B
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.fuType.expect(FuType.alu.U)
      dut.io.deq.decodedInst.fuOpType.expect(ALUOpType.and)
      dut.io.deq.decodedInst.intWen.expect(true.B)
    }
  }

  it should "carry rm and CSR metadata and reject state violations from the CSR summary" in {
    test(new AnsDecodeUnit(params)) { dut =>
      val froundDynamic = fType(0x20, 4, 2, 7, 1)
      driveUnit(dut, froundDynamic)
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.rm.expect(7.U)
      dut.io.enq.fromCsr.frmIllegal.poke(true.B)
      exception(dut) shouldBe 1

      driveUnit(dut, froundDynamic)
      dut.io.enq.fromCsr.fsOff.poke(true.B)
      exception(dut) shouldBe 1

      val csrrw = iType(0x300, 4, 1, 5, 0x73)
      driveUnit(dut, csrrw)
      exception(dut) shouldBe 0
      dut.io.deq.decodedInst.fuType.expect(FuType.csr.U)
      dut.io.deq.decodedInst.fuOpType.expect(CSROpType.csrrw)
      dut.io.deq.decodedInst.immType.expect(ImmType.z.U)
      dut.io.deq.decodedInst.logicalDest.expect(5.U)
      dut.io.deq.decodedInst.logicalSrc(0).expect(4.U)
      dut.io.deq.decodedInst.waitForward.expect(true.B)
      dut.io.deq.decodedInst.blockBackward.expect(true.B)

      val csrrwNoRead = iType(0x300, 4, 1, 0, 0x73)
      driveUnit(dut, csrrwNoRead)
      dut.io.deq.decodedInst.fuOpType.expect(CSROpType.csrrw)
      dut.io.deq.decodedInst.intWen.expect(false.B)
    }
  }

  behavior of "AnsDecodeTables"

  it should "decode every table row through its first-priority winner" in {
    test(new AnsDecodeUnit(params)) { dut =>
      val table = AnsDecodeTables.flat
      table.zipWithIndex.foreach { case ((pattern, _), index) =>
        val witness   = pattern.value
        val winner    = table.indexWhere { case (candidate, _) => patternMatches(candidate, witness) }
        withClue(s"pattern $pattern has no matching row: ") {
          winner should be >= 0
        }
        val winnerRow = table(winner)._2.asInstanceOf[AnsDecode]
        if (winner != index) {
          info(s"row $index ($pattern) is shadowed by row $winner (${table(winner)._1})")
        }

        driveUnit(dut, witness)
        exception(dut) shouldBe 0
        dut.io.deq.decodedInst.fuType.expect(winnerRow.fuType.U(FuType.width.W))
        dut.io.deq.decodedInst.fuOpType.expect(winnerRow.fuOpType.value.U(FuOpType.width.W))
        dut.io.deq.decodedInst.intWen.expect((winnerRow.intWen && ((witness >> 7) & 0x1f) != 0).B)
        dut.io.deq.decodedInst.fpWen.expect(winnerRow.fpWen.B)
        dut.io.deq.decodedInst.immType.expect(winnerRow.immType.U(ImmType.width.W))
        for (slot <- 0 until 3) {
          dut.io.deq.decodedInst.srcType(slot).expect(winnerRow.sources(slot).U(SrcType.width.W))
        }
      }
    }
  }
}
