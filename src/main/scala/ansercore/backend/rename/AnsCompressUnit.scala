/* 雁栖湖 ROB 压缩单元。
 *
 * 昆明湖用一张 2^RenameWidth 项的 DecodeLogic 真值表(compressTable)来反查压缩信息，
 * 表项由递归的 cntL/cntR 生成，依赖 rocket-chip 的 DecodeLogic。这里改为直接对
 * canCompress 向量做一段组合扫描，见 AnsCompressUnit 内的注释。
 *
 * 与昆明湖相比去掉了 x86 相关的 uopIdx 与多微操作拆分(标量为单微操作)；
 * 本项目直接使用 isLastInFtqEntry 阻止跨 FTQ entry 压缩。
 */
package ansercore.backend.rename

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.{BundleParams, DecodedInst}

class AnsCompressOut(val params: BundleParams) extends Bundle {
  val needRobFlags   = Vec(params.renameWidth, Output(Bool()))
  val instrSizes     = Vec(params.renameWidth, Output(UInt(AnsRenameParams.instrSizeWidth.W)))
  val masks          = Vec(params.renameWidth, Output(UInt(params.renameWidth.W)))
  val canCompressVec = Vec(params.renameWidth, Output(Bool()))
}

class AnsCompressUnitIO(val params: BundleParams) extends Bundle {
  val in  = Vec(params.renameWidth, Flipped(Valid(new DecodedInst(params))))
  val out = new AnsCompressOut(params)
}

class AnsCompressUnit(val params: BundleParams = BundleParams()) extends Module {
  val width = params.renameWidth

  val io = IO(new AnsCompressUnitIO(params))

  // 一条指令可被压缩的充分条件：有效、无异常、译码标记可压缩。
  val canCompress = io.in.map { in =>
    in.valid && !in.bits.hasException && in.bits.canRobCompress
  }
  val linked      = (0 until width - 1).map { lane =>
    canCompress(lane) && canCompress(lane + 1) && !io.in(lane).bits.isLastInFtqEntry
  }

  for (i <- 0 until width) {
    // 找出与 lane i 处于同一段连续可压缩区间的全部 lane：中间链接必须可压缩且不能跨 FTQ entry。
    val sameRun      = (0 until width).map { j =>
      val lo = math.min(i, j)
      val hi = math.max(i, j)
      canCompress(i) && (lo until hi).map(linked(_)).foldLeft(true.B)(_ && _)
    }
    val runMask      = VecInit(sameRun).asUInt
    val selfMask     = VecInit((0 until width).map(j => j.U === i.U)).asUInt
    val nextCompress = if (i == width - 1) false.B else linked(i)

    // 不可压缩的 lane 自成一组(掩码只含自身，尺寸为 1)。
    io.out.canCompressVec(i) := canCompress(i)
    io.out.masks(i)          := Mux(canCompress(i), runMask, selfMask)
    io.out.instrSizes(i)     := Mux(canCompress(i), PopCount(runMask), 1.U)
    // 一组连续可压缩 lane 中，只有最后一条需要独占一个 ROB 条目；不可压缩者也需要。
    io.out.needRobFlags(i)   := Mux(canCompress(i), !nextCompress, true.B)
  }
}
