package ansercore.backend.issue

import chisel3._
import chiseltest._

object IssueTestUtils {

  /** Initialize input aggregates without depending on incidental payload fields. */
  def zero(data: Data): Unit = data match {
    case value: Bool    => value.poke(false.B)
    case value: UInt    => value.poke(0.U)
    case value: SInt    => value.poke(0.S)
    case values: Vec[_] => values.foreach(zero)
    case record: Record => record.elements.values.foreach(zero)
    case other          => throw new IllegalArgumentException(s"unsupported test input: $other")
  }
}
