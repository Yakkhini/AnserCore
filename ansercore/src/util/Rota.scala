/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package utils

import chisel3._

object Rota {
  sealed trait Direction
  case object Left extends Direction
  case object Right extends Direction

  /**
    * Circularly rotates a Vec by an elaboration-time amount.
    *
    * Left(1):  in(0), in(1), in(2) -> out(1), out(2), out(0)
    * Right(1): in(0), in(1), in(2) -> out(2), out(0), out(1)
    */
  def apply[T <: Data](in: Vec[T], amount: Int, direction: Direction): Vec[T] = {
    require(in.nonEmpty, "Rota input must not be empty")
    require(amount >= 0, "Rota amount must be non-negative")

    val size  = in.length
    val shift = amount % size
    direction match {
      case Left =>
        VecInit((0 until size).map(i => in((i + shift) % size)))
      case Right =>
        VecInit((0 until size).map(i => in((i - shift + size) % size)))
    }
  }

}
