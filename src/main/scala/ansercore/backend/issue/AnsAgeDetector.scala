/* 年龄矩阵：选择最旧的可发射条目。
 *
 * 与香山 AgeDetector 等价，去掉 XSModule/Parameters 依赖：
 *   - 只保存矩阵上三角，对角恒为真；
 *   - 入队时该行清 0（最新最年轻），该列置 1（比所有已存在条目年轻）；
 *   - 同拍多口入队时端口靠前更老。
 *
 * 雁栖湖只区分 EnqEntry 池和 ComplexEntry 池，两池各用一个矩阵。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

class AnsAgeDetector(numEntries: Int, numEnq: Int, numDeq: Int) extends Module {
  require(numEntries > 0 && numEnq > 0 && numDeq > 0)

  val io = IO(new Bundle {
    val enq      = Vec(numEnq, Input(UInt(numEntries.W)))
    val canIssue = Vec(numDeq, Input(UInt(numEntries.W)))
    val out      = Vec(numDeq, Output(UInt(numEntries.W)))
  })

  // age(i)(j): 条目 i 比条目 j 老
  val age     = Seq.fill(numEntries)(Seq.fill(numEntries)(RegInit(false.B)))
  val nextAge = Seq.fill(numEntries)(Seq.fill(numEntries)(Wire(Bool())))

  def getAge(row: Int, col: Int): Bool =
    if (row < col) age(row)(col)
    else if (row == col) true.B
    else !age(col)(row)

  def isEnq(i: Int): Bool = VecInit(io.enq.map(_(i))).asUInt.orR

  def isEnqBefore(i: Int, numPorts: Int): Bool =
    if (numPorts == 0) false.B else VecInit(io.enq.take(numPorts).map(_(i))).asUInt.orR

  for ((row, i) <- nextAge.zipWithIndex) {
    for ((elem, j) <- row.zipWithIndex) {
      if (i == j) {
        elem := true.B
      } else if (i < j) {
        when(isEnq(i) && isEnq(j)) {
          val sel    = io.enq.map(_(i))
          val result = (0 until numEnq).map(k => isEnqBefore(j, k))
          elem := !Mux1H(sel, result)
        }.elsewhen(isEnq(i)) {
          elem := false.B
        }.elsewhen(isEnq(j)) {
          elem := true.B
        }.otherwise {
          elem := getAge(i, j)
        }
      } else {
        elem := !nextAge(j)(i)
      }
      age(i)(j) := Mux(isEnq(i) || isEnq(j), elem, age(i)(j))
    }
  }

  def oldest(get: (Int, Int) => Bool, canIssue: UInt): UInt =
    VecInit((0 until numEntries).map { i =>
      (VecInit((0 until numEntries).map(j => get(i, j))).asUInt | ~canIssue).andR && canIssue(i)
    }).asUInt

  io.out.zip(io.canIssue).foreach { case (out, canIssue) => out := oldest(getAge, canIssue) }

  for (i <- 0 until numEnq) {
    when(PopCount(io.enq(i)) > 1.U) {
      assert(false.B, s"enq port $i must be one-hot")
    }
  }
}

object AnsAgeDetector {
  def apply(numEntries: Int, enq: Vec[UInt], canIssue: Vec[UInt]): Vec[Valid[UInt]] = {
    val age = Module(new AnsAgeDetector(numEntries, enq.length, canIssue.length))
    age.io.enq      := enq
    age.io.canIssue := canIssue
    val outVec = Wire(Vec(canIssue.length, Valid(UInt(numEntries.W))))
    outVec.zipWithIndex.foreach { case (out, i) =>
      out.valid := canIssue(i).orR
      out.bits  := age.io.out(i)
      when(out.valid) {
        assert(PopCount(out.bits) === 1.U, "age detector must select exactly one entry")
      }
    }
    outVec
  }
}
