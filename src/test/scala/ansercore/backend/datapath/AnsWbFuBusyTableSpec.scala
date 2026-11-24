package ansercore.backend.datapath

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.issue.IssueTestUtils.zero

class AnsWbFuBusyTableSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "AnsWbFuBusyTable"

  it should "merge both shared fixed integer write ports" in {
    test(new AnsWbFuBusyTable()) { dut =>
      zero(dut.io.write)

      // IntWB2：ALU2（idx4）lat0 与 MUL1（idx3）lat2 共享。
      dut.io.write(4).intWbBusyTable.poke(2.U)
      dut.io.write(3).intWbBusyTable.poke(8.U)
      dut.io.write(4).intDeqRespSet.poke(4.U)
      dut.io.write(3).intDeqRespSet.poke(4.U)
      dut.io.read(4).intWbBusyTable.expect(10.U)
      dut.io.read(3).intWbBusyTable.expect(10.U)
      dut.io.conflict(4).intConflict.expect(true.B)
      dut.io.conflict(3).intConflict.expect(true.B)

      dut.io.write(3).intDeqRespSet.poke(8.U)
      dut.io.conflict(4).intConflict.expect(false.B)
      dut.io.conflict(3).intConflict.expect(false.B)

      // IntWB0 不做中央合并。
      dut.io.write(0).intWbBusyTable.poke(16.U)
      dut.io.read(0).intWbBusyTable.expect(0.U)
      // IntWB1 合并 MUL0/FEX0 的 lat2 与 ALU1 的 lat0。
      dut.io.write(6).intWbBusyTable.poke(16.U)
      dut.io.read(6).intWbBusyTable.expect(16.U)
      dut.io.read(1).intWbBusyTable.expect(16.U)
      dut.io.read(2).intWbBusyTable.expect(16.U)
      dut.io.write(6).fpWbBusyTable.poke(16.U)
      dut.io.read(6).fpWbBusyTable.expect(0.U)
    }
  }
}
