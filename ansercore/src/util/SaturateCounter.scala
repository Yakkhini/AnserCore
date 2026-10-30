/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package utils

import chisel3._
import chisel3.util._

class SaturateCounter(width: Int) extends Bundle { // scalastyle:ignore number.of.methods
  val value: UInt = UInt(width.W)

  def ===(that: SaturateCounter): Bool = this.value === that.value
  def =/=(that: SaturateCounter): Bool = this.value =/= that.value // scalastyle:ignore method.name
  def <(that:   SaturateCounter): Bool = this.value < that.value
  def <=(that:  SaturateCounter): Bool = this.value <= that.value
  def >(that:   SaturateCounter): Bool = this.value > that.value
  def >=(that:  SaturateCounter): Bool = this.value >= that.value

  def +(that: Int): SaturateCounter = {
    require(that >= 0)
    val max = (1 << width) - 1
    require(that <= max)

    val result   = Wire(new SaturateCounter(width))
    val sum      = value +& that.U
    val saturate = sum(width) || (sum(width - 1, 0).andR)
    result.value := Mux(saturate, max.U(width.W), sum(width - 1, 0))
    result
  }

  def -(that: Int): SaturateCounter = {
    require(that >= 0)
    val max = (1 << width) - 1
    require(that <= max)

    val result   = Wire(new SaturateCounter(width))
    val diff     = value -& that.U
    val saturate = diff(width)
    result.value := Mux(saturate, 0.U(width.W), diff(width - 1, 0))
    result
  }
  /* *** state methods *** */
  /* example: 3bit
   *  0   1   2   3 | 4   5   6   7
   *       Negative | Positive
   *  sat  mid weak | weak mid  sat
   */
  // direction
  def isPositive: Bool              = value(width - 1)  // value >= (1 << (width - 1)).U
  def isNegative: Bool              = !value(width - 1) // value < (1 << (width - 1)).U
  // saturated
  def isSaturatePositive: Bool      = value.andR        // value === ((1 << width) - 1).U
  def isSaturateNegative: Bool      = !value.orR        // value === 0.U
  def isSaturate: Bool              = isSaturatePositive || isSaturateNegative
  // weak
  def isWeakPositive: Bool          = {                 // value === (1 << (width - 1))
    require(width >= 2, "SaturateCounter width must be at least 2 to have weak states")
    isPositive && !value(width - 2, 0).orR
  }
  def isWeakNegative: Bool          = {                 // value === (1 << (width - 1)) - 1
    require(width >= 2, "SaturateCounter width must be at least 2 to have weak states")
    isNegative && value(width - 2, 0).andR
  }
  def isWeak: Bool                  = isWeakPositive || isWeakNegative

}

object SaturateCounter {
  def SaturatePositive(width: Int): Int = (1 << width) - 1
  def SaturateNegative(width: Int): Int = 0
  def WeakPositive(width: Int): Int     = {
    require(width >= 2, "SaturateCounter width must be at least 2 to have weak states")
    1 << (width - 1)
  }
  def WeakNegative(width: Int): Int     = {
    require(width >= 2, "SaturateCounter width must be at least 2 to have weak states")
    (1 << (width - 1)) - 1
  }

  def apply(width: Int, init: Int): SaturateCounter = {
    val counter = Wire(new SaturateCounter(width))
    counter.value := init.U
    counter
  }

  def apply(width: Int, init: UInt): SaturateCounter = {
    val counter = Wire(new SaturateCounter(width))
    counter.value := init
    counter
  }
}
