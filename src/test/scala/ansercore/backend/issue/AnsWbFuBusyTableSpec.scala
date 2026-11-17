package ansercore.backend.issue

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import IssueTestUtils.zero

class AnsWbFuBusyTableSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsWbFuBusyTable"

  it should "merge shared reservations and report conflicts only within the same port and latency slot" in {
    test(new AnsWbFuBusyTable()) { dut =>
      zero(dut.io.write)
      dut.io.write(0).intWbBusyTable.poke(2.U)
      dut.io.write(1).intWbBusyTable.poke(8.U)
      dut.io.write(0).intDeqRespSet.poke(4.U)
      dut.io.write(1).intDeqRespSet.poke(4.U)
      dut.io.read(0).intWbBusyTable.expect(10.U)
      dut.io.read(1).intWbBusyTable.expect(10.U)
      dut.io.conflict(0).intConflict.expect(true.B)
      dut.io.conflict(1).intConflict.expect(true.B)
      dut.io.conflict(2).intConflict.expect(false.B)

      dut.io.write(1).intDeqRespSet.poke(8.U)
      dut.io.conflict(0).intConflict.expect(false.B)
      dut.io.conflict(1).intConflict.expect(false.B)

      // FEX0/FEX1 的浮点写口不同；相同延迟槽不构成跨端口冲突。
      dut.io.write(6).fpWbBusyTable.poke(16.U)
      dut.io.write(7).fpWbBusyTable.poke(32.U)
      dut.io.write(6).fpDeqRespSet.poke(4.U)
      dut.io.write(7).fpDeqRespSet.poke(4.U)
      dut.io.read(6).fpWbBusyTable.expect(16.U)
      dut.io.read(7).fpWbBusyTable.expect(32.U)
      dut.io.conflict(6).fpConflict.expect(false.B)
      dut.io.conflict(7).fpConflict.expect(false.B)
      dut.io.read(6).intWbBusyTable.expect(0.U)
      dut.io.read(0).fpWbBusyTable.expect(0.U)
    }
  }
}
