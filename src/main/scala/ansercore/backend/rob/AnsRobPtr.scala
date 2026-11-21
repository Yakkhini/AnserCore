package ansercore.backend.rob

import chisel3._
import chisel3.util._

/** Ring pointer used by the ROB storage and control blocks. */
class AnsRobPtr(val config: AnsRobParams = AnsRobParams()) extends Bundle {
  val flag  = Bool()
  val value = UInt(config.valueWidth.W)

  def ===(that: AnsRobPtr): Bool = asUInt === that.asUInt

  def =/=(that: AnsRobPtr): Bool = asUInt =/= that.asUInt

  /** Wrap-aware age comparison, matching the existing FtqPtr convention. */
  def isAfter(that: AnsRobPtr): Bool = (flag ^ that.flag) ^ (value > that.value)

  def isBefore(that: AnsRobPtr): Bool = (flag ^ that.flag) ^ (value < that.value)

  def +(offset: UInt): AnsRobPtr = {
    val result = Wire(new AnsRobPtr(config))
    val sum    = asUInt +& offset
    result.flag  := sum(config.valueWidth)
    result.value := sum(config.valueWidth - 1, 0)
    result
  }

  def next: AnsRobPtr = this + 1.U

  def bank: UInt = value(config.bankAddrWidth - 1, 0)

  def row: UInt = value(config.valueWidth - 1, config.bankAddrWidth)

  /** First entry in the physical bank line, retaining the wrap flag. */
  def lineHeadPtr: AnsRobPtr = AnsRobPtr(flag, (value >> config.bankAddrWidth) << config.bankAddrWidth, config)

  def needFlush(redirect: AnsRobPtr, flushItself: Bool): Bool =
    isAfter(redirect) || (flushItself && this === redirect)
}

object AnsRobPtr {
  def apply(flag: Bool, value: UInt, config: AnsRobParams = AnsRobParams()): AnsRobPtr = {
    val result = Wire(new AnsRobPtr(config))
    result.flag  := flag
    result.value := value
    result
  }

  def fromUInt(value: UInt, config: AnsRobParams): AnsRobPtr = {
    require(value.getWidth == -1 || value.getWidth == config.ptrWidth, "ROB pointer width mismatch")
    val result = Wire(new AnsRobPtr(config))
    result.flag  := value(config.valueWidth)
    result.value := value(config.valueWidth - 1, 0)
    result
  }
}
