/* Dispatch 顶层：Rename 之后、Issue Queue 之前的分派级（设计文档 §4.2）。
 *
 * 组成（对应文档 §4.1）：
 *   - Int/Fp BusyTable：维护物理寄存器 ready/busy；
 *   - RegCacheTagTable：整数源命中标签查询；
 *   - LsqEnqCtrl：LQ/SQ shadow 指针与条目分配；
 *   - 分派逻辑：ROB/LSQ/IQ 资源检查、fuType -> IQ 路由与负载均衡、整组握手。
 *
 * 与香山 NewDispatch 的差异：
 *   - 标量 RVB23，无向量/向量 LSQ；STD0 不占 Dispatch 入队口；
 *   - BusyTable 不维护 loadDependency；singleStep/ldCancel 只保留端口；
 *   - IQ 负载均衡平局按设计文档取较小索引（香山因比较矩阵写法取较大索引）。
 */
package ansercore.backend.dispatch

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.issue.AnsIssueParams
import ansercore.backend.rename.{AnsRenameParams, AnsRenameRedirect}

class AnsDispatchIO(val params: BundleParams = BundleParams()) extends Bundle {
  private val laneCount = params.renameWidth
  private val iqSum     = AnsDispatchParams.IQEnqSum
  private val phyWidth  = params.physicalRegWidth

  /** 融合补丁后的 T1 组，仅用于 fuType 识别（绕过 Rename 的延迟）。 */
  val renameIn        = Input(Vec(laneCount, Valid(new DecodedInst(params))))
  val fromRename      = Vec(laneCount, Flipped(Decoupled(new DynInst(params))))
  val toRenameAllFire = Output(Bool())
  val redirect        = Input(Valid(new AnsRenameRedirect(params)))

  val toIssueQueues = Vec(iqSum, Decoupled(new DynInst(params)))
  val IQValidNumVec = Input(Vec(AnsIssueParams.nExu, UInt(AnsDispatchParams.iqValidNumWidth.W)))

  val enqRob = Flipped(new AnsRobEnqIO(params))
  val lsqEnq = Flipped(new AnsLsqEnqIO(params))

  val wbPregsInt = Input(Vec(AnsDispatchParams.nIntWb, Valid(UInt(phyWidth.W))))
  val wbPregsFp  = Input(Vec(AnsDispatchParams.nFpWb, Valid(UInt(phyWidth.W))))
  val wakeUpAll  = Input(new AnsIQWakeUpVec(params))
  val s0Cancel   = Input(Vec(AnsDispatchParams.nExu, new CancelSignal(params)))
  val ldCancel   = Input(Vec(params.loadPipelineWidth, new LoadCancel))
  val fromMem    = Input(new AnsMemToDispatchBundle(params))
  val singleStep = Input(Bool())

  val debug = Output(new AnsDispatchDebugIO(params))
}

class AnsDispatch(val params: BundleParams = BundleParams()) extends Module {
  val width     = params.renameWidth
  val numSrc    = params.numSrc
  val numIntSrc = params.numIntSrc
  val numFpSrc  = params.numFpSrc
  val iqNum     = AnsDispatchParams.IQNum
  val enqSum    = AnsDispatchParams.IQEnqSum
  val phyWidth  = params.physicalRegWidth

  val io = IO(new AnsDispatchIO(params))

  def fuBit(fu: Int): Int = Integer.numberOfTrailingZeros(fu)

  io.toRenameAllFire := io.fromRename.map(x => !x.valid || x.fire).reduce(_ && _)

  // ---- BusyTable ----
  val intBusyTable = Module(
    new AnsBusyTable(
      params,
      numReadPorts = width * numIntSrc,
      numEntries   = AnsRenameParams.intPhyRegs,
      nWb          = AnsDispatchParams.nIntWb,
      nWakeUp      = AnsDispatchParams.nIntWakeUp + AnsDispatchParams.nMemWakeUp,
      nAlloc       = width,
      nCancel      = AnsDispatchParams.nExu,
      isInt        = true,
    )
  )
  val fpBusyTable  = Module(
    new AnsBusyTable(
      params,
      numReadPorts = width * numFpSrc,
      numEntries   = AnsRenameParams.fpPhyRegs,
      nWb          = AnsDispatchParams.nFpWb,
      nWakeUp      = AnsDispatchParams.nFpWakeUp + AnsDispatchParams.nMemWakeUp,
      nAlloc       = width,
      nCancel      = AnsDispatchParams.nExu,
      isInt        = false,
    )
  )

  val allocInt = Wire(Vec(width, Valid(UInt(phyWidth.W))))
  val allocFp  = Wire(Vec(width, Valid(UInt(phyWidth.W))))
  for (i <- 0 until width) {
    val uop = io.fromRename(i).bits
    allocInt(i).valid := io.fromRename(i).valid && uop.intWen && !uop.eliminatedMove
    allocInt(i).bits  := uop.pdest
    allocFp(i).valid  := io.fromRename(i).valid && uop.fpWen
    allocFp(i).bits   := uop.pdest
  }

  intBusyTable.io.wbPregs    := io.wbPregsInt
  intBusyTable.io.allocPregs := allocInt
  intBusyTable.io.cancel     := io.s0Cancel
  intBusyTable.io.wakeUp     := VecInit(io.wakeUpAll.wakeUpInt ++ io.wakeUpAll.wakeUpMem)

  fpBusyTable.io.wbPregs    := io.wbPregsFp
  fpBusyTable.io.allocPregs := allocFp
  fpBusyTable.io.cancel     := io.s0Cancel
  fpBusyTable.io.wakeUp     := VecInit(io.wakeUpAll.wakeUpFp ++ io.wakeUpAll.wakeUpMem)

  // ---- RegCacheTagTable ----
  val rcTagTable = Module(
    new AnsRegCacheTagTable(
      params,
      numReadPorts  = width * numIntSrc,
      numWritePorts = AnsDispatchParams.nIntWakeUp + AnsDispatchParams.nMemWakeUp,
      nAlloc        = width,
      nCancel       = AnsDispatchParams.nExu,
    )
  )
  rcTagTable.io.allocPregs := allocInt
  rcTagTable.io.cancel := io.s0Cancel
  for ((port, k) <- rcTagTable.io.write.zipWithIndex) {
    val wake = (io.wakeUpAll.wakeUpInt ++ io.wakeUpAll.wakeUpMem)(k)
    port.wen  := wake.valid && wake.bits.rfWen
    port.addr := wake.bits.rcDest
    port.tag  := wake.bits.pdest
  }

  // ---- 组装进入 IQ/ROB/LSQ 的 uop ----
  // 标量整数指令最多 2 个源寄存器，只有 FP FMA 需要第 3 个源槽。
  val fromRenameUpdate = Wire(Vec(width, Valid(new DynInst(params))))
  for (i <- 0 until width) {
    val uop = io.fromRename(i).bits
    fromRenameUpdate(i).bits := uop
    for (j <- 0 until numSrc) {
      val intReady =
        if (j < numIntSrc) {
          val port = intBusyTable.io.read(i * numIntSrc + j)
          port.req := uop.psrc(j)
          !port.busy
        } else false.B
      val fpReady  =
        if (j < numFpSrc) {
          val port = fpBusyTable.io.read(i * numFpSrc + j)
          port.req := uop.psrc(j)
          !port.busy
        } else false.B

      val ready = Mux(
        SrcType.isInteger(uop.srcType(j)),
        intReady,
        Mux(SrcType.isFloating(uop.srcType(j)), fpReady, true.B),
      )
      fromRenameUpdate(i).bits.srcState(j) := Mux(ready, SourceState.ready.U, SourceState.waiting.U)

      if (j < numIntSrc) {
        val rcPort = rcTagTable.io.read(i * numIntSrc + j)
        rcPort.ren                              := io.fromRename(i).valid && SrcType.isInteger(uop.srcType(j))
        rcPort.tag                              := uop.psrc(j)
        fromRenameUpdate(i).bits.useRegCache(j) := rcPort.valid
        fromRenameUpdate(i).bits.regCacheIdx(j) := rcPort.addr
      } else {
        fromRenameUpdate(i).bits.useRegCache(j) := false.B
        fromRenameUpdate(i).bits.regCacheIdx(j) := 0.U
      }
    }
  }

  // ---- LsqEnqCtrl ----
  val lsqCtrl = Module(new AnsLsqEnqCtrl(params))
  lsqCtrl.io.redirect := io.redirect
  lsqCtrl.io.fromMem  := io.fromMem
  for (i <- 0 until width) {
    val fire     = io.fromRename(i).fire
    val fuType   = io.fromRename(i).bits.fuType
    val isMem    = FuType.isMemory(fuType)
    val isAtomic = FuType.isAtomic(fuType)

    lsqCtrl.io.enq.needAlloc(i) := Mux(
      !fire,
      0.U,
      Mux(isMem, Mux(isAtomic, 2.U, 1.U), 0.U),
    )
    lsqCtrl.io.enq.req(i).valid := fire && isMem
    lsqCtrl.io.enq.req(i).bits  := fromRenameUpdate(i).bits
    lsqCtrl.io.enq.iqAccept(i)  := !io.fromRename(i).valid || fire

    fromRenameUpdate(i).bits.lqIdx := lsqCtrl.io.enq.resp(i).lqIdx
    fromRenameUpdate(i).bits.sqIdx := lsqCtrl.io.enq.resp(i).sqIdx
  }
  io.lsqEnq.needAlloc := lsqCtrl.io.toLsq.needAlloc
  io.lsqEnq.req       := lsqCtrl.io.toLsq.req
  io.lsqEnq.iqAccept  := lsqCtrl.io.toLsq.iqAccept

  val lsqCanAccept = lsqCtrl.io.enq.canAccept

  // ---- IQ 负载均衡预选 ----
  val groups  = AnsDispatchParams.multiIQGroups.toIndexedSeq
  val singles = AnsDispatchParams.singleIQ.toIndexedSeq

  val groupHits = Wire(Vec(groups.size, Vec(width, Bool())))
  for {
    (group, g) <- groups.zipWithIndex
    i          <- 0 until width
  } {
    val sel = group.fuTypes.map(ft => io.renameIn(i).bits.fuType(fuBit(ft))).reduce(_ || _)
    groupHits(g)(i) := io.renameIn(i).valid && sel
  }

  // 本 lane 之前同组 uop 的数量：用作负载均衡的排序序号。
  val groupCounts = Wire(Vec(groups.size, Vec(width, UInt(log2Up(width).W))))
  for (g <- groups.indices) {
    groupCounts(g)(0) := 0.U
    for (i <- 1 until width) {
      groupCounts(g)(i) := groupCounts(g)(i - 1) + groupHits(g)(i - 1).asUInt
    }
  }

  // 平局取较小索引：低索引行用 <=，高索引行用严格 <。行内 1 的个数即该出队口的负载排名。
  val compareMatrix: Seq[Seq[Seq[Bool]]] = groups.map { group =>
    val loads = group.exuIdx.map(idx => io.IQValidNumVec(idx))
    val n     = loads.size
    Seq.tabulate(n, n) { (a, b) =>
      if (a == b) false.B
      else if (a < b) loads(a) <= loads(b)
      else loads(a) < loads(b)
    }
  }

  val iqSort: Seq[Seq[Seq[Bool]]] = groups.indices.map { g =>
    val n = groups(g).exuIdx.size
    Seq.tabulate(n, n)((rank, m) => PopCount(compareMatrix(g)(m)) === (n - 1 - rank).U)
  }

  // 排名 -> 出队口 -> 该出队口所属的 IQ。
  val iqAtRank: Seq[Vec[UInt]] = groups.indices.map { g =>
    val n = groups(g).exuIdx.size
    VecInit(
      Seq.tabulate(n)(rank => Mux1H(iqSort(g)(rank).zip(groups(g).exuIdx.map(e => AnsDispatchParams.exuToIQ(e).U))))
    )
  }

  val laneIQSelect = Wire(Vec(width, Vec(iqNum, Bool())))
  for (i <- 0 until width) {
    val chosenPerGroup = groups.indices.map { g =>
      val n    = groups(g).exuIdx.size
      val cnt  = groupCounts(g)(i)
      val rank = Mux(cnt >= n.U, cnt - n.U, cnt)
      iqAtRank(g)(rank(log2Up(n) - 1, 0))
    }
    val singleHits     = singles.map { case (fu, exu) =>
      (io.renameIn(i).bits.fuType(fuBit(fu)), AnsDispatchParams.exuToIQ(exu).U)
    }
    for (iq <- 0 until iqNum) {
      val multiHit  = groups.indices.map(g => groupHits(g)(i) && chosenPerGroup(g) === iq.U).reduce(_ || _)
      val singleHit = singleHits.map { case (hit, target) => hit && target === iq.U }.reduce(_ || _)
      laneIQSelect(i)(iq) := multiHit || singleHit
    }
  }

  // 与香山一致：整组接收时用 T1 组预选，逐 lane 完成 handshake 后清零。
  val uopSelIQ = RegInit(VecInit(Seq.fill(width)(VecInit(Seq.fill(iqNum)(false.B)))))
  for (i <- 0 until width) {
    when(io.toRenameAllFire) {
      uopSelIQ(i) := Mux(io.renameIn(i).valid, laneIQSelect(i), 0.U.asTypeOf(laneIQSelect(i)))
    }.elsewhen(io.fromRename(i).fire) {
      uopSelIQ(i) := 0.U.asTypeOf(laneIQSelect(i))
    }
  }

  val matrixWidth    = log2Up(width + 1)
  val uopSelIQMatrix = Wire(Vec(width, Vec(iqNum, UInt(matrixWidth.W))))
  for {
    i  <- 0 until width
    iq <- 0 until iqNum
  } {
    uopSelIQMatrix(i)(iq) := PopCount((0 to i).map(k => uopSelIQ(k)(iq)))
  }

  // 每个 IQ 的入队容量：本 lane 及其之前 lane 占用的所有端口都必须 ready，
  // 否则会破坏"端口靠前更老"的年龄顺序。
  val uopBlockByIQ = Wire(Vec(width, Bool()))
  for (i <- 0 until width) {
    uopBlockByIQ(i) := (0 until iqNum)
      .map { iq =>
        val cnt      = uopSelIQMatrix(i)(iq)
        val portBusy = (0 until AnsDispatchParams.iqList(iq).numEnq)
          .map(e => (cnt > e.U) && !io.toIssueQueues(AnsDispatchParams.iqEnqPort(iq, e)).ready)
          .reduce(_ || _)
        cnt > AnsDispatchParams.iqList(iq).numEnq.U || portBusy
      }
      .reduce(_ || _)
  }

  // ---- 资源检查 ----
  val isWaitForward        = VecInit(
    (0 until width).map(i => io.fromRename(i).valid && io.fromRename(i).bits.waitForward)
  )
  val blockedByWaitForward = Wire(Vec(width, Bool()))
  blockedByWaitForward(0) := !io.enqRob.isEmpty && isWaitForward(0)
  for (i <- 1 until width) {
    val previousValid = VecInit((0 until i).map(k => io.fromRename(k).valid)).asUInt.orR
    blockedByWaitForward(i) := blockedByWaitForward(i - 1) ||
      ((!io.enqRob.isEmpty || previousValid) && isWaitForward(i))
  }

  val isBlockBackward      =
    VecInit((0 until width).map(i => io.fromRename(i).valid && io.fromRename(i).bits.blockBackward))
  val notBlockedByPrevious = VecInit((0 until width).map { i =>
    if (i == 0) true.B else (0 until i).map(k => !isBlockBackward(k)).reduce(_ && _)
  })

  val loadNeed  = VecInit((0 until width).map { i =>
    val fuType = io.fromRename(i).bits.fuType
    io.fromRename(i).valid && (FuType.isLoad(fuType) || FuType.isAtomic(fuType))
  })
  val storeNeed = VecInit((0 until width).map { i =>
    val fuType = io.fromRename(i).bits.fuType
    io.fromRename(i).valid && (FuType.isStore(fuType) || FuType.isAtomic(fuType))
  })

  // 含当前 lane 的前缀需求，与 LSQ 空闲计数做严格比较（与香山一致）。
  val loadCount  = VecInit((0 until width).map(i => PopCount(loadNeed.take(i + 1))))
  val storeCount = VecInit((0 until width).map(i => PopCount(storeNeed.take(i + 1))))

  val allowDispatch = Wire(Vec(width, Bool()))
  for (i <- 0 until width) {
    val previous   = if (i == 0) true.B else allowDispatch(i - 1)
    val lqOk       = lsqCtrl.io.lqFreeCount > loadCount(i)
    val sqOk       = lsqCtrl.io.sqFreeCount > storeCount(i)
    val resourceOk = Mux(
      loadNeed(i) && storeNeed(i),
      lqOk && sqOk,
      Mux(loadNeed(i), lqOk, Mux(storeNeed(i), sqOk, true.B)),
    )
    allowDispatch(i) := resourceOk && previous
  }

  val thisCanActualOut = Wire(Vec(width, Bool()))
  for (i <- 0 until width) {
    thisCanActualOut(i) := !io.redirect.valid && !blockedByWaitForward(i) &&
      notBlockedByPrevious(i) && io.enqRob.canAcceptForDispatch
  }

  // ---- 整组握手 ----
  for (i <- 0 until width) {
    val fire = allowDispatch(i) && !uopBlockByIQ(i) && thisCanActualOut(i) && lsqCanAccept
    io.fromRename(i).ready    := fire
    fromRenameUpdate(i).valid := io.fromRename(i).valid && fire &&
      !io.fromRename(i).bits.eliminatedMove && !io.fromRename(i).bits.hasException
  }

  // ---- ROB / LSQ 入队 ----
  for (i <- 0 until width) {
    io.enqRob.needAlloc(i) := io.fromRename(i).valid
    io.enqRob.req(i).valid := io.fromRename(i).fire
    io.enqRob.req(i).bits  := fromRenameUpdate(i).bits
  }

  // ---- IQ 输出 ----
  // 每个 IQ 的第 e 个入队口选"到本 lane 为止第 e+1 条选中该 IQ 的指令"，
  // 保证 lane 顺序映射到端口顺序（端口 0 更老）。
  for {
    iq <- 0 until iqNum
    e  <- 0 until AnsDispatchParams.iqList(iq).numEnq
  } {
    val port = AnsDispatchParams.iqEnqPort(iq, e)
    val oh   = VecInit((0 until width).map(i => uopSelIQMatrix(i)(iq) === (e + 1).U))
    val hit  = oh.asUInt.orR
    io.toIssueQueues(port).bits  := PriorityMux(oh, fromRenameUpdate.map(_.bits))
    io.toIssueQueues(port).valid := Mux(hit, PriorityMux(oh, fromRenameUpdate.map(_.valid)), false.B)
  }

  // ---- debug ----
  io.debug.allowDispatch         := allowDispatch
  io.debug.blockedByWaitForward  := blockedByWaitForward
  io.debug.notBlockedByPrevious  := notBlockedByPrevious
  io.debug.uopBlockByIQ          := uopBlockByIQ
  io.debug.lsqCanAccept          := lsqCanAccept
  io.debug.lqFreeCount           := lsqCtrl.io.lqFreeCount
  io.debug.sqFreeCount           := lsqCtrl.io.sqFreeCount
  io.debug.uopSelIQ              := uopSelIQ
  io.debug.uopSelIQMatrix        := uopSelIQMatrix
  io.debug.fromRenameUpdateValid := VecInit((0 until width).map(i => fromRenameUpdate(i).valid))
  io.debug.enqRobReqValid        := VecInit((0 until width).map(i => io.enqRob.req(i).valid))
  io.debug.toIssueQueuesValid    := VecInit((0 until enqSum).map(p => io.toIssueQueues(p).valid))
}
