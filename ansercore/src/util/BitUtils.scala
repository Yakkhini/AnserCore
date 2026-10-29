/*
 * SPDX-FileCopyrightText: 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-FileCopyrightText: 2020-2021 Peng Cheng Laboratory
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package utils

import chisel3._
import chisel3.util._

import scala.math.min

object SignExt {
  def apply(a: UInt, len: Int): UInt = {
    val aLen = a.getWidth
    require(len >= aLen)
    val signBit = a(aLen-1)
    if (aLen == len) a else Cat(Fill(len - aLen, signBit), a)
  }
}

object ZeroExt {
  def apply(a: UInt, len: Int): UInt = {
    val aLen = a.getWidth
    require(len >= aLen)
    if (aLen == len) a(len-1,0) else Cat(0.U((len - aLen).W), a)
  }
}

object HighMask {
  def apply(a: UInt): UInt = apply(a, includeSelf = false)
  def apply(a: UInt, includeSelf: Boolean): UInt =
    computeMask(a.asBools, includeSelf).asUInt

  def apply(a: Seq[Bool]): Seq[Bool] = apply(a, includeSelf = false)
  def apply(a: Seq[Bool], includeSelf: Boolean): Seq[Bool] =
    computeMask(a, includeSelf).toSeq

  def apply(a: Vec[Bool]): Vec[Bool] = apply(a, includeSelf = false)
  def apply(a: Vec[Bool], includeSelf: Boolean): Vec[Bool] =
    computeMask(a.toSeq, includeSelf)

  private def computeMask(a: Seq[Bool], includeSelf: Boolean): Vec[Bool] = {
    require(a.nonEmpty, "HighMask input must not be empty")
    val result = Wire(Vec(a.length, Bool()))
    var lowerOr = false.B
    for (i <- a.indices) {
      if (includeSelf) {
        lowerOr = lowerOr || a(i)
        result(i) := lowerOr
      } else {
        result(i) := lowerOr
        lowerOr = lowerOr || a(i)
      }
    }
    result
  }
}
object LowMask {
  def apply(a: UInt): UInt = apply(a, includeSelf = false)
  def apply(a: UInt, includeSelf: Boolean): UInt =
    computeMask(a.asBools, includeSelf).asUInt

  def apply(a: Seq[Bool]): Seq[Bool] = apply(a, includeSelf = false)
  def apply(a: Seq[Bool], includeSelf: Boolean): Seq[Bool] =
    computeMask(a, includeSelf).toSeq

  def apply(a: Vec[Bool]): Vec[Bool] = apply(a, includeSelf = false)
  def apply(a: Vec[Bool], includeSelf: Boolean): Vec[Bool] =
    computeMask(a.toSeq, includeSelf)

  private def computeMask(a: Seq[Bool], includeSelf: Boolean): Vec[Bool] = {
    require(a.nonEmpty, "LowMask input must not be empty")
    assert(
      PopCount(a) === 1.U,
      "LowMask expects exactly one bit set"
    )
    val result = Wire(Vec(a.length, Bool()))
    var upperOr = false.B
    for (i <- a.indices.reverse) {
      if (includeSelf) {
        upperOr = upperOr || a(i)
        result(i) := upperOr
      } else {
        result(i) := upperOr
        upperOr = upperOr || a(i)
      }
    }
    result
  }
}

/**
 * SelectFirstN: select n index from bit mask, low bit has high priority.
 */
object SelectFirstN {
  def apply(in: UInt, n: Int ): Vec[UInt] = {
    val sels = Wire(Vec(n, UInt(in.getWidth.W)))
    var mask = in

    for (i <- 0 until n) {
      sels(i) := LowPriorityOH(mask)
      mask = mask & ~sels(i)
    }
    sels
  }

  def apply(in: Seq[Bool], n:Int): Vec[UInt] = {
    apply(Cat(in), n)
  }

}

object AlignDown {
  def apply(x: UInt, align: Int ): UInt= {???}
  def apply(x: UInt, align: UInt): UInt= {???}
}

object AlignUp {
  def apply(x: UInt, align: Int ): UInt= {???}
  def apply(x: UInt, align: UInt): UInt= {???}
}


object IsOneHot{
  def apply(x: UInt): Bool = {
    x.orR && AtMostOneHot(x)
  }
}

object AtMostOneHot{
  def apply(x: UInt): Bool ={
    (x & (x - 1.U)) === 0.U
  }
}

object XorFold{
  def apply(x: UInt, inWidth: Int, outWidth: Int): UInt ={
    require(inWidth >= outWidth && outWidth > 0)
    val xWidth = x.getWidth

    val useWidth = math.min(xWidth, inWidth)

    val nChunks = (useWidth + outWidth -1)/ outWidth
    val chunks = (0 until nChunks).map{i =>
      val lo = i * outWidth
      val hi = math.min(lo + outWidth, useWidth) -1
      x(hi, lo).pad(outWidth)
    }

    chunks.reduce(_ ^ _)
  }

  def apply(x: UInt, outWidth: Int): UInt = XorFold(x, x.getWidth, outWidth)
}

object Mux1H {
  def apply[T <: Data](in: Iterable[(Bool, T)]): T = {
    val terms = in.toSeq
    require(terms.nonEmpty, "Mux1H input must not be empty")
    val proto = terms.head._2
    require(proto.widthOption.isDefined, "Mux1H data width must be known")
    val width = proto.getWidth
    require(width > 0, "Mux1H data width must be positive")
    val masked = terms.map { case (sel, data) =>
      require(data.getWidth == width, "Mux1H data widths must match")
      data.asUInt & Fill(width, sel)
    }
    masked.reduce(_ | _).asTypeOf(proto)
  }

  def apply[T <: Data](sel: Seq[Bool], in: Seq[T]): T = {
    require(sel.length == in.length, "Mux1H select and data counts must match")
    apply(sel.zip(in))
  }

  def apply[T <: Data](sel: UInt, in: Seq[T]): T = {
    require(sel.getWidth >= in.length, "Mux1H select is narrower than data")
    apply((0 until in.length).map(sel(_)), in)
  }

  def apply(sel: UInt, in: UInt): Bool = (sel & in).orR
}

// 低编号优先。或链挡住后面的 1。输入全 0 时输出全 0。
object LowPriorityOH {
  def apply(in: Seq[Bool]): Seq[Bool] = {
    require(in.nonEmpty, "LowPriorityOH input must not be empty")
    val result = Wire(Vec(in.length, Bool()))
    var lowerOr = false.B
    for (i <- in.indices) {
      result(i) := in(i) && !lowerOr
      lowerOr = lowerOr || in(i)
    }
    Seq.tabulate(in.length)(result(_))
  }

  def apply(in: Bits): UInt = VecInit(apply(in.asBools)).asUInt
}
