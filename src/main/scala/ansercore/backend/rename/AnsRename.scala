/* 雁栖湖 rename 顶层模块。
 *
 * 对应设计文档 3/4/5 节与昆明湖 Rename.scala，实现：
 *   物理寄存器分配、RAT 更新、源操作数映射、同拍 bypass、Move Elimination、
 *   ROB 序号分配、快照生成、Walk 恢复。
 *
 * 与昆明湖的关键差异(详见 markdown/rename-ds.md)：
 *   - 只有整数/浮点两类寄存器，去掉向量 v0/vl/vp 及其 FreeList / RAT；
 *   - 去掉 SSIT / WaitTable(存储集预测)，相应字段固定为 0；
 *   - bypass 用从 lane0 递增的 foldLeft 直接表达，最近的写者最后生效；
 *   - 去掉性能计数器、difftest、trace 等非功能逻辑，保留文档要求的 stallReason。
 */
package ansercore.backend.rename

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles
import ansercore.backend.Bundles.{BundleParams, DecodedInst, DynInst, FuType, SrcType}
import AnsRenameParams._

class AnsRenameIO(val params: BundleParams = BundleParams()) extends Bundle {
  val intAddrWidth = log2Ceil(intLogicRegs)
  val fpAddrWidth  = log2Ceil(fpLogicRegs)

  val redirect    = Input(Valid(new AnsRenameRedirect(params)))
  val rabCommits  = Input(new AnsRabCommitIO(params))
  val snpt        = Input(new AnsSnapshotPort(params))
  val snptLastEnq = Input(Valid(UInt(params.robIndexWidth.W)))
  val snptIsFull  = Input(Bool())

  val in         = Vec(params.renameWidth, Flipped(Decoupled(new DecodedInst(params))))
  val fusionInfo = Vec(params.renameWidth - 1, Input(new AnsFusionInfo))
  val out        = Vec(params.renameWidth, Decoupled(new DynInst(params)))

  val intReadPorts =
    Vec(
      params.renameWidth,
      Vec(AnsRenameParams.intReadPorts, Input(new AnsRatReadRequest(intAddrWidth))),
    )
  val fpReadPorts  =
    Vec(params.renameWidth, Vec(AnsRenameParams.fpReadPorts, Input(new AnsRatReadRequest(fpAddrWidth))))

  val stallReason = Output(new AnsRenameStallReason)

  val debugIntRat       = Vec(intLogicRegs, Output(UInt(params.physicalRegWidth.W)))
  val debugFpRat        = Vec(fpLogicRegs, Output(UInt(params.physicalRegWidth.W)))
  val debugIntSpec      = Vec(intLogicRegs, Output(UInt(params.physicalRegWidth.W)))
  val debugFpSpec       = Vec(fpLogicRegs, Output(UInt(params.physicalRegWidth.W)))
  val debugIntFreeCount = Output(UInt(log2Ceil(intFreeListSize + 1).W))
  val debugFpFreeCount  = Output(UInt(log2Ceil(fpFreeListSize + 1).W))
}

class AnsRename(val params: BundleParams = BundleParams()) extends Module {
  val width    = params.renameWidth
  val numSrc   = params.numSrc
  val phyWidth = params.physicalRegWidth
  val robWidth = params.robIndexWidth

  val io = IO(new AnsRenameIO(params))

  val compressUnit = Module(new AnsCompressUnit(params))
  val intFreeList  = Module(new AnsMEFreeList(params))
  val fpFreeList   = Module(new AnsStdFreeList(params))
  val ratManager   = Module(new AnsRenameTableManager(params))

  // 输出的 DynInst 缓冲，供快照生成与最终输出复用。
  val uops = Wire(Vec(width, new DynInst(params)))

  val dispatchCanAccept = io.out.head.ready
  val freeListsReady    = intFreeList.io.canAllocate && fpFreeList.io.canAllocate
  val canOut            = dispatchCanAccept && freeListsReady && !io.rabCommits.isWalk

  intFreeList.io.doAllocate := dispatchCanAccept || io.rabCommits.isWalk
  fpFreeList.io.doAllocate  := dispatchCanAccept || io.rabCommits.isWalk

  for (freeList <- Seq(intFreeList, fpFreeList)) {
    freeList.io.redirect := io.redirect.valid
    freeList.io.walk     := io.rabCommits.isWalk
    freeList.io.commit   := io.rabCommits
  }

  // RAT 早读请求：地址由 Backend 在 T1 前驱动，数据只在 rename 内部使用。
  // `AnsRatReadRequest` 与 Backend 的 RAT 请求端口同形，可直接对接。
  for (i <- 0 until width) {
    for (j <- 0 until AnsRenameParams.intReadPorts) {
      ratManager.io.intReadPorts(i)(j).hold := io.intReadPorts(i)(j).hold
      ratManager.io.intReadPorts(i)(j).addr := io.intReadPorts(i)(j).addr
    }
    for (j <- 0 until AnsRenameParams.fpReadPorts) {
      ratManager.io.fpReadPorts(i)(j).hold := io.fpReadPorts(i)(j).hold
      ratManager.io.fpReadPorts(i)(j).addr := io.fpReadPorts(i)(j).addr
    }
  }
  ratManager.io.redirect   := io.redirect.valid
  ratManager.io.rabCommits := io.rabCommits

  // ---- ROB 压缩 ----
  compressUnit.io.in.zip(io.in).foreach { case (sink, source) =>
    sink.valid := source.valid
    sink.bits  := source.bits
  }
  val needRobFlags = compressUnit.io.out.needRobFlags
  val instrSizes   = compressUnit.io.out.instrSizes

  // 统计本周期真正要进入 ROB 的指令数（需要独占 ROB 条目）。
  def needRob(lane: Int): Bool = io.in(lane).valid && needRobFlags(lane)
  val robOffset                = (0 until width).map(lane => PopCount((0 until lane).map(needRob(_))))
  val validCount               = PopCount((0 until width).map(needRob(_)))

  // Rename 分配的 robIdx 领先 ROB 一拍：这样快照能记录 CFI 的 robIdx。
  val robIdxHead          = RegInit(0.U(robWidth.W))
  val lastCycleMispredict = RegNext(io.redirect.valid && !io.redirect.bits.flushItself, false.B)
  val robIdxHeadNext      = Mux(
    io.redirect.valid,
    io.redirect.bits.robIdx,
    Mux(lastCycleMispredict, robIdxHead +% 1.U, Mux(canOut, robIdxHead +% validCount, robIdxHead)),
  )
  robIdxHead := robIdxHeadNext

  // ---- 逐 lane 的目标寄存器判定 ----
  val hasException = Wire(Vec(width, Bool()))
  val isMove       = Wire(Vec(width, Bool()))
  val needIntDest  = Wire(Vec(width, Bool()))
  val needFpDest   = Wire(Vec(width, Bool()))
  for (i <- 0 until width) {
    hasException(i) := io.in(i).bits.hasException
    // move 消除只在无异常时生效。
    isMove(i)       := io.in(i).valid && io.in(i).bits.isMove && !hasException(i)
    needIntDest(i)  := io.in(i).valid && io.in(i).bits.intWen
    needFpDest(i)   := io.in(i).valid && io.in(i).bits.fpWen
  }

  // ---- 分配 / Walk 请求 ----
  val walkNeedIntDest = Wire(Vec(width, Bool()))
  val walkNeedFpDest  = Wire(Vec(width, Bool()))
  val walkIsMove      = Wire(Vec(width, Bool()))
  for (i <- 0 until width) {
    walkNeedIntDest(i) := io.rabCommits.walkValid(i) && io.rabCommits.info(i).rfWen
    walkNeedFpDest(i)  := io.rabCommits.walkValid(i) && io.rabCommits.info(i).fpWen
    walkIsMove(i)      := io.rabCommits.info(i).isMove

    intFreeList.io.allocateReq(i) := needIntDest(i) && !isMove(i)
    intFreeList.io.walkReq(i)     := walkNeedIntDest(i) && !walkIsMove(i)
    fpFreeList.io.allocateReq(i)  := needFpDest(i)
    fpFreeList.io.walkReq(i)      := walkNeedFpDest(i)
  }

  val allocatedDest = Wire(Vec(width, UInt(phyWidth.W)))
  for (i <- 0 until width) {
    allocatedDest(i) := MuxCase(
      0.U(phyWidth.W),
      Seq(needIntDest(i) -> intFreeList.io.allocatePhyReg(i), needFpDest(i) -> fpFreeList.io.allocatePhyReg(i)),
    )
  }

  // ---- 源操作数映射(读 RAT) ----
  val psrcRaw = Wire(Vec(width, Vec(numSrc, UInt(phyWidth.W))))
  for (i <- 0 until width) {
    val srcType = io.in(i).bits.srcType
    val slot0   = Mux1H(
      Seq(
        SrcType.isInteger(srcType(0))  -> ratManager.io.intReadPorts(i)(0).data,
        SrcType.isFloating(srcType(0)) -> ratManager.io.fpReadPorts(i)(0).data,
      )
    )
    val slot1   = Mux1H(
      Seq(
        SrcType.isInteger(srcType(1))  -> ratManager.io.intReadPorts(i)(1).data,
        SrcType.isFloating(srcType(1)) -> ratManager.io.fpReadPorts(i)(1).data,
      )
    )
    psrcRaw(i) := VecInit(slot0, slot1, ratManager.io.fpReadPorts(i)(2).data)

    // 宏融合：被融合指令的源 2 复用下一条 lane 已读出的端口，或直接取 x0。
    if (i < width - 1) {
      when(io.fusionInfo(i).rs2FromRs2)(psrcRaw(i)(1) := ratManager.io.intReadPorts(i + 1)(1).data)
        .elsewhen(io.fusionInfo(i).rs2FromRs1)(psrcRaw(i)(1) := ratManager.io.intReadPorts(i + 1)(0).data)
        .elsewhen(io.fusionInfo(i).rs2FromZero)(psrcRaw(i)(1) := 0.U)
    }
  }

  // ---- 同拍 bypass + pdest ----
  // 对 lane i 的每个源，检查更早的 lane k<i 是否写同一逻辑寄存器且写类型匹配。
  // foldLeft 从 lane0 递增，后迭代的 Mux 位于外层，因此最近的写者优先(与昆明湖一致)。
  val psrc  = Wire(Vec(width, Vec(numSrc, UInt(phyWidth.W))))
  val pdest = Wire(Vec(width, UInt(phyWidth.W)))
  psrc(0)  := psrcRaw(0)
  pdest(0) := Mux(isMove(0), psrc(0)(0), allocatedDest(0))
  for (i <- 1 until width) {
    val srcType = io.in(i).bits.srcType
    for (j <- 0 until numSrc) {
      val bypass = (0 until i).foldLeft(psrcRaw(i)(j)) { (acc, k) =>
        val writeType  = MuxLookup(srcType(j), false.B)(
          Seq(SrcType.integer.U -> needIntDest(k), SrcType.floating.U -> needFpDest(k))
        )
        val dependency = (io.in(k).bits.logicalDest === io.in(i).bits.logicalSrc(j)) && writeType
        Mux(dependency, pdest(k), acc)
      }
      psrc(i)(j) := bypass
    }
    pdest(i) := Mux(isMove(i), psrc(i)(0), allocatedDest(i))
  }

  // ---- 输出 valid 先行确定(不依赖快照)，供 genSnapshot 使用 ----
  for (i <- 0 until width) {
    io.out(i).valid := io.in(i).valid && freeListsReady && !io.rabCommits.isWalk
  }

  // ---- 快照生成 ----
  // 触发条件：与上一个快照保持距离、缓冲未满、上一拍未生成、且是新的指令组。
  val genSnapshot         = (0 until width).map(i => io.out(i).fire && uops(i).snapshot).reduce(_ || _)
  val lastCycleCreateSnpt = RegNext(genSnapshot && !io.snptIsFull, false.B)
  val sameSnptDistance    = (rabCommitWidth * 4).U
  val notInSameSnpt       = RegNext(
    !io.snptLastEnq.valid || ((robIdxHeadNext - io.snptLastEnq.bits) >= sameSnptDistance),
    false.B,
  )
  // 快照缓冲区未满才允许生成，避免 ROB 记录到未真正入队的 snapshot。
  val allowSnpt           = notInSameSnpt && !lastCycleCreateSnpt && !io.snptIsFull

  // ---- 组装输出 DynInst ----
  for (i <- 0 until width) {
    uops(i)                   := 0.U.asTypeOf(new DynInst(params))
    Bundles.connectSamePort(uops(i), io.in(i).bits)
    uops(i).psrc              := psrc(i)
    uops(i).pdest             := pdest(i)
    uops(i).robIdx            := robIdxHead +% robOffset(i)
    uops(i).instrSize         := instrSizes(i)
    uops(i).eliminatedMove    := isMove(i)
    uops(i).isMove            := isMove(i)
    uops(i).replayInst        := false.B
    uops(i).srcState          := 0.U.asTypeOf(uops(i).srcState)
    uops(i).srcLoadDependency := 0.U.asTypeOf(uops(i).srcLoadDependency)
    // 访存队列序号由 Dispatch 填写。
    uops(i).lqIdx             := 0.U
    uops(i).sqIdx             := 0.U
    uops(i).snapshot          := allowSnpt && FuType.isControlFlow(io.in(i).bits.fuType) && io.in(i).fire
  }

  // FreeList 在 T0 拍快照 headPtr，RAT 内部再打一拍拍快照 spec_table(与昆明湖一致)。
  val freeListSnpt = Wire(new AnsSnapshotPort(params))
  freeListSnpt         := io.snpt
  freeListSnpt.snptEnq := genSnapshot
  intFreeList.io.snpt  := freeListSnpt
  fpFreeList.io.snpt   := freeListSnpt

  val tableSnpt = Wire(new AnsSnapshotPort(params))
  tableSnpt          := io.snpt
  tableSnpt.snptEnq  := genSnapshot
  ratManager.io.snpt := tableSnpt

  // ---- RAT 推测写(重命名) ----
  for (i <- 0 until width) {
    ratManager.io.intRenamePorts(i).wen  :=
      needIntDest(
        i
      ) && intFreeList.io.canAllocate && intFreeList.io.doAllocate && !io.rabCommits.isWalk && !io.redirect.valid
    ratManager.io.intRenamePorts(i).addr := io.in(i).bits.logicalDest
    ratManager.io.intRenamePorts(i).data := pdest(i)

    ratManager.io.fpRenamePorts(i).wen  :=
      needFpDest(
        i
      ) && fpFreeList.io.canAllocate && fpFreeList.io.doAllocate && !io.rabCommits.isWalk && !io.redirect.valid
    ratManager.io.fpRenamePorts(i).addr := io.in(i).bits.logicalDest
    ratManager.io.fpRenamePorts(i).data := allocatedDest(i)
  }

  // ---- FreeList 回收(提交时释放旧物理寄存器) ----
  for (i <- 0 until rabCommitWidth) {
    intFreeList.io.freeReq(i)    := ratManager.io.intNeedFree(i)
    intFreeList.io.freePhyReg(i) := RegNext(ratManager.io.intOldPdest(i))

    val fpCommit = io.rabCommits.isCommit && io.rabCommits.commitValid(i) && io.rabCommits.info(i).fpWen
    fpFreeList.io.freeReq(i)    := RegNext(fpCommit, false.B)
    fpFreeList.io.freePhyReg(i) := ratManager.io.fpOldPdest(i)
  }

  // ---- 输出握手 ----
  for (i <- 0 until width) {
    io.in(i).ready := !io.in(0).valid || canOut
    io.out(i).bits := uops(i)
  }

  // ---- 阻塞原因（文档 4.2.1） ----
  val anyInputValid = VecInit(io.in.map(_.valid)).asUInt.orR
  io.stallReason.integerFreeList  := anyInputValid && !intFreeList.io.canAllocate
  io.stallReason.floatingFreeList := anyInputValid && !fpFreeList.io.canAllocate
  io.stallReason.walk             := io.rabCommits.isWalk
  io.stallReason.redirect         := io.redirect.valid
  io.stallReason.downstream       :=
    anyInputValid && freeListsReady && !io.rabCommits.isWalk && !io.redirect.valid && !dispatchCanAccept

  io.debugIntRat       := ratManager.io.debugIntRat
  io.debugFpRat        := ratManager.io.debugFpRat
  io.debugIntSpec      := ratManager.io.debugIntSpec
  io.debugFpSpec       := ratManager.io.debugFpSpec
  io.debugIntFreeCount := intFreeList.io.debugFreeCount
  io.debugFpFreeCount  := fpFreeList.io.debugFreeCount

  // ---- 协议断言（参考昆明湖/rename-codex） ----
  for (i <- 0 until width) {
    when(io.in(i).valid) {
      assert(!(io.in(i).bits.intWen && io.in(i).bits.fpWen), "one scalar uop cannot write both register files")
      when(io.in(i).bits.intWen) {
        assert(io.in(i).bits.logicalDest =/= 0.U, "integer destination must not be x0")
      }
    }
  }
  assert(!(io.rabCommits.isCommit && io.rabCommits.isWalk), "commit and walk are mutually exclusive")
  when(io.rabCommits.isWalk) {
    for (i <- 0 until width) {
      assert(!ratManager.io.intRenamePorts(i).wen, "normal integer rename overlapped walk")
      assert(!ratManager.io.fpRenamePorts(i).wen, "normal floating rename overlapped walk")
    }
  }
  assert(intFreeList.io.snapshotFull === fpFreeList.io.snapshotFull, "free-list snapshots lost synchronization")
  assert(intFreeList.io.snapshotValids === fpFreeList.io.snapshotValids, "free-list snapshot valids differ")
  assert(
    intFreeList.io.snapshotEnqIndex === fpFreeList.io.snapshotEnqIndex,
    "free-list snapshot indices differ",
  )
}
