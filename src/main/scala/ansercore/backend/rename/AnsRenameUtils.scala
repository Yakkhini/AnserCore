/* 雁栖湖 rename 自包含工具函数。
 *
 * 昆明湖把这些放在 utility 库里(CircularQueuePtr / HasCircularQueuePtrHelper /
 * GatedValidRegNext / CircularShift 等)，本项目要求 rename 模块自包含，因此在这里用
 * 最小实现补齐。相比库版本做了精简：
 *   - 环形指针只保留本模块真正用到的 +、距离、相等、toOH；
 *   - 用显式的 flag/value 字段，天然支持非 2 的幂长度(整数 FreeList 长度 180)。
 */
package ansercore.backend.rename

import chisel3._
import chisel3.util._

/** size 槽环形指针：value 在 [0, size) 回绕，flag 记录回绕圈数以区分空/满。 */
class AnsCircularPtr(val size: Int) extends Bundle {
  val addrBits = log2Ceil(size)

  val value = UInt(addrBits.W)
  val flag  = Bool()

  /** 前进 count 步(允许跨回绕，count < size)。 */
  def +(count: UInt): AnsCircularPtr = {
    val next     = Wire(new AnsCircularPtr(size))
    val extended = value +& count
    val wrapped  = extended >= size.U
    next.value := Mux(wrapped, extended - size.U, extended)(addrBits - 1, 0)
    next.flag  := flag ^ wrapped
    next
  }

  /** 后退 count 步(允许跨回绕，count <= size)。 */
  def -(count: UInt): AnsCircularPtr = {
    val flipped = this + (size.U - count)
    val next    = Wire(new AnsCircularPtr(size))
    next.value := flipped.value
    next.flag  := !flipped.flag
    next
  }

  def samePtr(that: AnsCircularPtr): Bool = (value === that.value) && (flag === that.flag)

  def toOH: UInt = UIntToOH(value, size)
}

object AnsCircularPtr {
  def apply(size: Int, flag: Boolean, value: Int): AnsCircularPtr = {
    val ptr = Wire(new AnsCircularPtr(size))
    ptr.value := value.U
    ptr.flag  := flag.B
    ptr
  }
}

object AnsRenameUtil {

  /** 从 from 沿前进方向到 to 的槽数((to - from) mod size)。 */
  def forwardDistance(from: AnsCircularPtr, to: AnsCircularPtr): UInt = {
    require(from.size == to.size, "distance requires pointers of the same ring size")
    Mux(
      from.flag === to.flag,
      to.value - from.value,
      to.size.U + to.value - from.value,
    )
  }

  def isFull(enq: AnsCircularPtr, deq: AnsCircularPtr): Bool =
    (enq.flag =/= deq.flag) && (enq.value === deq.value)

  def isEmpty(enq: AnsCircularPtr, deq: AnsCircularPtr): Bool =
    (enq.flag === deq.flag) && (enq.value === deq.value)

  /** 把 size 位 one-hot 循环左移 shift 位(shift 为编译期常量)。 */
  def rotateLeftOH(oh: UInt, shift: Int, size: Int): UInt = {
    require(shift >= 0 && shift < size, "rotation must stay within the ring")
    if (shift == 0) oh else Cat(oh(size - 1 - shift, 0), oh(size - 1, size - shift))
  }

  /** 优先级 mux：索引最小的有效选择优先(等价昆明湖 ParallelPriorityMux)。 */
  def priorityMux[T <: Data](sels: Seq[Bool], vals: Seq[T]): T = {
    require(sels.nonEmpty && sels.length == vals.length, "priority mux needs matched selects and values")
    sels.zip(vals).reverse.foldLeft(vals.head) { case (acc, (sel, value)) => Mux(sel, value, acc) }
  }
}
