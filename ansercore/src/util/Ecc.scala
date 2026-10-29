/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package utils

import chisel3._
import chisel3.util._
import dataclass.data

trait EccResult {
  def rawData: UInt       // 原始数据
  def correctedData: UInt // 被修复的数据
  def correctableError: Bool  // 发生了可恢复的错误
  def uncorrectableError: Bool // 发生了不可恢复的错误
  def error = correctableError || uncorrectableError
}

abstract class EccCode(val dataWidth: Int){
  require(dataWidth >= 1)

  def parityWidth: Int
  protected def codeEncode(data: UInt, enableErrorInjection: Boolean, injectError: Bool):UInt
  protected def codeDecode(codeword: UInt): EccResult

  final def codeWidth: Int = dataWidth + parityWidth

  final def encode(data: UInt, enableErrorInjection: Boolean, injectError: Bool): UInt = {
    require(data.getWidth == dataWidth)
    codeEncode(data, enableErrorInjection, injectError)
  }

  final def encode(data: UInt): UInt = encode(data, false, false.B)

  final def decode(codeword: UInt): EccResult = {
    require(codeword.getWidth == codeWidth)
    codeDecode(codeword)
  }
}


class ParityCode(dataWidth: Int) extends EccCode(dataWidth){

  override val parityWidth = 1

  override protected def codeEncode(data: UInt, enableErrorInjection: Boolean, injectError: Bool): UInt = {
    val parity = data.xorR
    val encodedParity =
      if (enableErrorInjection) {
        parity ^ injectError
      } else {
        parity
      }

    Cat(encodedParity, data)
  }

  override protected def codeDecode(codeword: UInt): EccResult = new EccResult {
    val rawData = codeword(codeword.getWidth-2,0)
    val correctedData = rawData
    val correctableError = false.B
    val uncorrectableError = codeword.xorR
  }
}

class SecCode(dataWidth: Int) extends EccCode(dataWidth) {
  override val parityWidth = {
    require(dataWidth >= 1)
    var m = 0
    while ((1 << m) < dataWidth + m + 1) m += 1
    m
  }

  private val hamm2sys = (1 to codeWidth).map { h =>
    if (isPow2(h)) {
      dataWidth + log2Floor(h)
    } else {
      h - 2 - log2Floor(h)
    }
  }

  override protected def codeEncode(data: UInt, enableErrorInjection: Boolean, injectError: Bool): UInt = {
    require(!enableErrorInjection || !isPow2(codeWidth + 1), s"SEC code of length $codeWidth cannot be poisoned")

    val parity = (0 until parityWidth).map { j =>
      val terms = (1 to codeWidth).flatMap { p =>
        if ((p & (1 << j)) != 0 && !isPow2(p)) Some(data(hamm2sys(p-1))) else None
      }

      val value = terms.reduceOption(_ ^ _).getOrElse(false.B)

      if (enableErrorInjection) value ^ injectError else value
    }

    Cat(VecInit(parity).asUInt, data)
  }

  override protected def codeDecode(codeword: UInt): EccResult = {
    val syndrome = VecInit((0 until parityWidth).map { j =>
      (1 to codeWidth).filter(p => (p & (1 << j)) != 0).map { p =>
        codeword(hamm2sys(p-1))
      }.reduce(_ ^ _)
    }).asUInt

    val badPosition = syndrome

    // Reverse the mapping: Systematic bit index -> 1-based Hamming position.
    val sys2hamm = hamm2sys.zipWithIndex.sortBy(_._1).map {
      case (_, hammingIndex) => hammingIndex + 1
    }

    val hammBadBitOH = UIntToOH(badPosition, codeWidth+1)

    // Exactly codeWidth bits, aligned with {parity, data} codeword bits.
    val sysBadBitOH = VecInit(sys2hamm.map(p => hammBadBitOH(p))).asUInt

    val raw = codeword(dataWidth - 1, 0)
    val correctedCode = codeword ^ sysBadBitOH
    val invalidSyndrome = badPosition > codeWidth.U

    new EccResult {
      val rawData = raw
      val correctedData = correctedCode(dataWidth-1, 0)
      val uncorrectableError = invalidSyndrome
      val correctableError = !invalidSyndrome && badPosition.orR
    }
  }
}

class SecDedCode(dataWidth: Int) extends EccCode(dataWidth) {
  private val secCode = new SecCode(dataWidth)

  override val parityWidth = secCode.parityWidth + 1
  override protected def codeEncode(data: UInt, enableErrorInjection: Boolean, injectError: Bool): UInt = {
    // The low bits retain the systematic SEC layout: {SEC parity, data}.
    val secWord = secCode.encode(data)
    val overallParity = secWord.xorR ^ (if (enableErrorInjection) injectError else false.B)
    Cat(overallParity, secWord)
  }

  override protected def codeDecode(codeword: UInt): EccResult = {
    val secWord = codeword(secCode.codeWidth - 1, 0)
    val secResult = secCode.decode(secWord)
    val raw = codeword(dataWidth - 1, 0)

    // The XOR of the complete codeword is zero for a valid SECDED word.
    // syndrome != 0 and odd overall parity identifies a single SEC-bit error;
    // syndrome == 0 and odd parity identifies an overall-parity-bit error.
    val overallError = codeword.xorR
    val syndromeError = secResult.correctableError
    val singleSecError = overallError && syndromeError
    val doubleError = !overallError && syndromeError

    new EccResult {
      val rawData = raw
      val correctedData = secResult.correctedData
      val correctableError = singleSecError
      val uncorrectableError = doubleError
    }
  }

}

object EccType {
  def apply(name: String, dataWidth: Int): EccCode = name.toLowerCase match {
    case "parity" => new ParityCode(dataWidth)
    case "sec" => new SecCode(dataWidth)
    case "secded" => new SecDedCode(dataWidth)
    case _ => throw new IllegalArgumentException("Unknown Ecc type")
  }
}
