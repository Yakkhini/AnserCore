/* 雁栖湖物理寄存器 FreeList。
 *
 * 昆明湖把公共逻辑放在 BaseFreeList，再用 MEFreeList / StdFreeList 两个子类实现差异。
 * 这里沿用相同的模块划分，但把子类差异压缩成三个小钩子：
 *   - freeListInit ：空闲表初值；
 *   - tailPtrInit  ：尾指针初值；
 *   - archAllocates：提交时该条指令是否让架构头指针前进(move 消除会跳过)。
 *
 * 与昆明湖的两点简化：
 *   1. 尾指针统一用「寄存器上周期值 + 本周期回收数」的组合形式，昆明湖 ME 用寄存器、
 *      Std 用寄存器+组合两套写法，行为等价但重复；
 *   2. 去掉向量 v0/vl 与 debug_rat / difftest 端口。
 */
package ansercore.backend.rename

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.BundleParams
import AnsRenameParams._

class AnsFreeListIO(val params: BundleParams, val size: Int) extends Bundle {
  val redirect = Input(Bool())
  val walk     = Input(Bool())

  val allocateReq    = Input(Vec(params.renameWidth, Bool()))
  val walkReq        = Input(Vec(rabCommitWidth, Bool()))
  val allocatePhyReg = Output(Vec(params.renameWidth, UInt(params.physicalRegWidth.W)))
  val canAllocate    = Output(Bool())
  val doAllocate     = Input(Bool())

  val freeReq    = Input(Vec(rabCommitWidth, Bool()))
  val freePhyReg = Input(Vec(rabCommitWidth, UInt(params.physicalRegWidth.W)))

  val commit = Input(new AnsRabCommitIO(params))
  val snpt   = Input(new AnsSnapshotPort(params))

  val debugFreeCount   = Output(UInt(log2Ceil(size + 1).W))
  val snapshotFull     = Output(Bool())
  val snapshotValids   = Output(Vec(renameSnapshotNum, Bool()))
  val snapshotEnqIndex = Output(UInt(snapshotSelectWidth.W))
}

abstract class AnsBaseFreeList(val size: Int, val physicalRegs: Int, params: BundleParams) extends Module {
  val width = params.renameWidth

  val io = IO(new AnsFreeListIO(params, size))

  protected def freeListInit:                          Seq[UInt]
  protected def tailPtrInit:                           (Boolean, Int)
  protected def archAllocates(info: AnsRabCommitInfo): Bool

  val freeList = RegInit(VecInit(freeListInit))

  val headPtr     = RegInit(AnsCircularPtr(size, false, 0))
  val headPtrOH   = RegInit(1.U(size.W))
  val archHeadPtr = RegInit(AnsCircularPtr(size, false, 0))

  // headPtr 前进 0..width 步的 one-hot，用于一次性给出 width 个候选物理寄存器。
  val headPtrOHVec = VecInit((0 to width).map(step => AnsRenameUtil.rotateLeftOH(headPtrOH, step, size)))

  val snapshotGen = Module(new AnsSnapshotGenerator(headPtr))
  snapshotGen.io.enq      := io.snpt.snptEnq
  snapshotGen.io.enqData  := headPtr
  snapshotGen.io.deq      := io.snpt.snptDeq
  snapshotGen.io.redirect := io.redirect
  snapshotGen.io.flushVec := io.snpt.flushVec
  val snapshots = snapshotGen.io.snapshots

  io.snapshotFull     := snapshotGen.io.full
  io.snapshotValids   := snapshotGen.io.valids
  io.snapshotEnqIndex := snapshotGen.io.enqIndex

  // redirect / 快照恢复作用到 headPtr 需要两拍(与昆明湖一致)。
  val lastCycleRedirect = RegNext(RegNext(io.redirect))
  val lastCycleSnpt     = RegNext(RegNext(io.snpt, 0.U.asTypeOf(io.snpt)))

  val walkCount           = PopCount(io.walkReq)
  val redirectedHeadPtr   = Mux(
    lastCycleSnpt.useSnpt,
    snapshots(lastCycleSnpt.snptSelect) + walkCount,
    archHeadPtr + walkCount,
  )
  val redirectedHeadPtrOH = Mux(
    lastCycleSnpt.useSnpt,
    (snapshots(lastCycleSnpt.snptSelect) + walkCount).toOH,
    (archHeadPtr + walkCount).toOH,
  )

  // ---- 分配：按「之前有多少条要分配」选取候选 ----
  val indexWidth       = log2Ceil(width + 1)
  val phyRegCandidates = VecInit(headPtrOHVec.map(sel => Mux1H(sel, freeList)))
  for (i <- 0 until width) {
    val allocateIndex = Wire(UInt(indexWidth.W))
    allocateIndex        := PopCount(io.allocateReq.take(i))
    io.allocatePhyReg(i) := phyRegCandidates(allocateIndex)
  }

  // ---- 架构头指针：提交时按需前进 ----
  val archAlloc = (0 until rabCommitWidth).map(i => io.commit.commitValid(i) && archAllocates(io.commit.info(i)))
  archHeadPtr := Mux(io.commit.isCommit, archHeadPtr + PopCount(archAlloc), archHeadPtr)

  // ---- 推测头指针：正常分配或 walk 恢复时前进 ----
  val walkAlloc   = io.walk && io.doAllocate
  val normalAlloc = io.canAllocate && io.doAllocate
  val doAllocate  = walkAlloc || normalAlloc
  val numAllocate = Wire(UInt(indexWidth.W))
  numAllocate := Mux(io.walk, PopCount(io.walkReq), PopCount(io.allocateReq))
  val headPtrAllocate   = Mux(lastCycleRedirect, redirectedHeadPtr, headPtr + numAllocate)
  val headPtrOHAllocate = Mux(lastCycleRedirect, redirectedHeadPtrOH, headPtrOHVec(numAllocate))
  val realDoAllocate    = !io.redirect && doAllocate
  headPtr   := Mux(realDoAllocate, headPtrAllocate, headPtr)
  headPtrOH := Mux(realDoAllocate, headPtrOHAllocate, headPtrOH)

  // ---- 回收：把释放的物理寄存器写回空闲表，并推进尾指针 ----
  val lastTailPtr = RegInit(AnsCircularPtr(size, tailPtrInit._1, tailPtrInit._2))
  val tailPtr     = Wire(new AnsCircularPtr(size))
  tailPtr           := lastTailPtr + PopCount(io.freeReq)
  for (i <- 0 until rabCommitWidth) {
    val offset = if (i == 0) 0.U else PopCount(io.freeReq.take(i))
    when(io.freeReq(i)) {
      freeList((lastTailPtr + offset).value) := io.freePhyReg(i)
    }
  }
  lastTailPtr       := tailPtr
  io.debugFreeCount := AnsRenameUtil.forwardDistance(headPtr, tailPtr)

  // ---- 空闲计数：打一拍后作为分配许可，改善时序 ----
  val freeRegCnt = Mux(
    walkAlloc && !lastCycleRedirect,
    AnsRenameUtil.forwardDistance(headPtr, tailPtr) - PopCount(io.walkReq),
    Mux(
      normalAlloc,
      AnsRenameUtil.forwardDistance(headPtr, tailPtr) - PopCount(io.allocateReq),
      AnsRenameUtil.forwardDistance(headPtr, tailPtr),
    ),
  )
  io.canAllocate := RegNext(freeRegCnt >= width.U, false.B)

  for (i <- 0 until rabCommitWidth) {
    when(io.freeReq(i)) {
      assert(io.freePhyReg(i) < physicalRegs.U, "free-list return is outside the physical register file")
      if (physicalRegs == AnsRenameParams.intPhyRegs) {
        assert(io.freePhyReg(i) =/= 0.U, "integer p0 must never enter the free list")
      }
    }
    for (j <- 0 until i) {
      when(io.freeReq(i) && io.freeReq(j)) {
        assert(io.freePhyReg(i) =/= io.freePhyReg(j), "a physical register was returned twice")
      }
    }
  }
  assert(AnsRenameUtil.forwardDistance(headPtr, tailPtr) <= size.U, "free-list occupancy escaped its range")
  when(lastCycleRedirect && lastCycleSnpt.useSnpt) {
    assert(snapshotGen.io.valids(lastCycleSnpt.snptSelect), "free list selected an invalid snapshot")
  }
}

/** 整数 FreeList：支持 Move Elimination。 */
class AnsMEFreeList(val params: BundleParams = BundleParams())
    extends AnsBaseFreeList(AnsRenameParams.intFreeListSize, AnsRenameParams.intPhyRegs, params) {

  // 初始空闲表 {1, 2, ..., size-1, 0}：物理寄存器 0 恒映射 x0，不参与分配。
  override protected def freeListInit: Seq[UInt] =
    (0 until size).map(i => ((i + 1) % size).U(params.physicalRegWidth.W))

  // 尾指针指向最后一个槽，使初始 freeRegCnt = size - 1。
  override protected def tailPtrInit: (Boolean, Int) = (false, size - 1)

  // move 指令不分配物理寄存器，因此架构头指针不为 move 前进。
  override protected def archAllocates(info: AnsRabCommitInfo): Bool = info.rfWen && !info.isMove
}

/** 浮点 FreeList：标准实现，无 move 消除。 */
class AnsStdFreeList(val params: BundleParams = BundleParams())
    extends AnsBaseFreeList(AnsRenameParams.fpFreeListSize, AnsRenameParams.fpPhyRegs, params) {

  // 空闲表从物理号 fpLogicRegs 开始：逻辑寄存器 0..31 已由架构 RAT 初始映射为自身。
  override protected def freeListInit: Seq[UInt] =
    (0 until size).map(i => (i + AnsRenameParams.fpLogicRegs).U(params.physicalRegWidth.W))

  // 尾指针 = (flag=true, 0)，使初始 freeRegCnt = size(整个环都是空闲)。
  override protected def tailPtrInit: (Boolean, Int) = (true, 0)

  // 每条浮点写指令必定分配新寄存器，提交即旧寄存器必死。
  override protected def archAllocates(info: AnsRabCommitInfo): Bool = info.fpWen
}
