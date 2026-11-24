/* DataPath：IssueQueue 之后、BypassNetwork 之前的 data0/data1 两级。
 *
 * data0：PRF 读口仲裁、同拍 WB 端口冲突检查、PC/target 查询；
 * data1：锁存控制信息与 PRF/RegCache 读数据，产生 data0/data1 响应与 cancel。
 * 操作数的最终选择（forward/bypass/imm/zero 等）在 AnsBypassNetwork 完成。
 *
 * PRF、RegCache、PcTargetMem 与中央 WbFuBusyTable 都由本模块持有；issue 包不再
 * 包含这些结构。PRF 采用 AnsRegFile 的地址 bank × 数据 split（默认 4×4）。
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.issue.{AnsIssueParams, AnsIssueUtils}
import ansercore.backend.rename.{AnsRenameParams, AnsRenameRedirect}

class AnsDataPath(
  val params:    BundleParams = BundleParams(),
  val prfConfig: AnsPrfConfig = AnsPrfConfig(),
) extends Module {
  AnsIssueParams.validate(params)
  val io = IO(new AnsDataPathIO(params))

  val nExu   = AnsIssueParams.nExu
  val numSrc = params.numSrc

  // ------------------------------------------------------------------
  // PRF（地址 bank × 数据 split，默认 4×4）
  // ------------------------------------------------------------------
  val intRf = Module(
    new AnsRegFile(
      AnsRegFileParams(
        numEntries    = AnsRenameParams.intPhyRegs,
        dataWidth     = params.xlen,
        addrWidth     = params.physicalRegWidth,
        numRead       = AnsIssueParams.nIntRd,
        numWrite      = AnsIssueParams.nIntWb,
        numAddrBanks  = prfConfig.intAddrBanks,
        numDataSplits = prfConfig.intDataSplits,
        hasZero       = true,
      )
    )
  )
  val fpRf  = Module(
    new AnsRegFile(
      AnsRegFileParams(
        numEntries    = AnsRenameParams.fpPhyRegs,
        dataWidth     = params.xlen,
        addrWidth     = params.physicalRegWidth,
        numRead       = AnsIssueParams.nFpRd,
        numWrite      = AnsIssueParams.nFpWb,
        numAddrBanks  = prfConfig.fpAddrBanks,
        numDataSplits = prfConfig.fpDataSplits,
        hasZero       = false,
      )
    )
  )

  for (k <- 0 until AnsIssueParams.nIntWb) {
    intRf.io.write(k).wen  := io.intWriteBack(k).wen
    intRf.io.write(k).addr := io.intWriteBack(k).addr
    intRf.io.write(k).data := io.intWriteBack(k).data
  }
  for (k <- 0 until AnsIssueParams.nFpWb) {
    fpRf.io.write(k).wen  := io.fpWriteBack(k).wen
    fpRf.io.write(k).addr := io.fpWriteBack(k).addr
    fpRf.io.write(k).data := io.fpWriteBack(k).data
  }

  // ------------------------------------------------------------------
  // PRF 读口仲裁
  // ------------------------------------------------------------------
  val intArb = Module(
    new AnsRFReadArbiter(
      AnsDataPathParams.intRdGroups,
      AnsIssueParams.nIntRd,
      nExu,
      numSrc,
      params.physicalRegWidth,
    )
  )
  val fpArb  = Module(
    new AnsRFReadArbiter(
      AnsDataPathParams.fpRdGroups,
      AnsIssueParams.nFpRd,
      nExu,
      numSrc,
      params.physicalRegWidth,
    )
  )

  for (e <- 0 until nExu) {
    val exu = AnsIssueParams.exuByIndex(e)
    val in  = io.fromIQ(e)
    for (s <- 0 until numSrc) {
      val intSlot = exu.intRdPorts(s) >= 0
      val fpSlot  = exu.fpRdPorts(s) >= 0
      intArb.io.reqValid(e)(s) := intSlot.B && in.valid &&
        SrcType.isInteger(in.bits.srcType(s)) && DataSource.readReg(in.bits.common.dataSources(s))
      intArb.io.reqAddr(e)(s)  := in.bits.psrc(s)
      fpArb.io.reqValid(e)(s)  := fpSlot.B && in.valid &&
        SrcType.isFloating(in.bits.srcType(s)) && DataSource.readReg(in.bits.common.dataSources(s))
      fpArb.io.reqAddr(e)(s)   := in.bits.psrc(s)
    }
  }

  val intRfRaddr = intArb.io.portAddr
  val fpRfRaddr  = fpArb.io.portAddr

  for (p <- 0 until AnsIssueParams.nIntRd) {
    intRf.io.read(p).addr := intRfRaddr(p)
  }
  for (p <- 0 until AnsIssueParams.nFpRd) {
    fpRf.io.read(p).addr := fpRfRaddr(p)
  }

  val intRfRdata = RegNext(VecInit((0 until AnsIssueParams.nIntRd).map(p => intRf.io.read(p).data)))
  val fpRfRdata  = RegNext(VecInit((0 until AnsIssueParams.nFpRd).map(p => fpRf.io.read(p).data)))

  // ------------------------------------------------------------------
  // 同拍写口冲突
  // ------------------------------------------------------------------
  val wbCollide = Module(new AnsWBCollideChecker(nExu, AnsIssueParams.nIntWb, AnsIssueParams.nFpWb))
  for (e <- 0 until nExu) {
    wbCollide.io.valid(e)  := io.fromIQ(e).valid
    wbCollide.io.fuType(e) := io.fromIQ(e).bits.common.fuType
    wbCollide.io.intWen(e) := io.fromIQ(e).bits.common.intWen
    wbCollide.io.fpWen(e)  := io.fromIQ(e).bits.common.fpWen
  }

  // ------------------------------------------------------------------
  // RegCache（保留端口；IQ 当前不产生 regcache 数据源）
  // ------------------------------------------------------------------
  val rcReadPorts = nExu * numSrc
  val rc          = Module(new AnsRegCache(params, rcReadPorts, AnsIssueParams.nIntWb))
  for {
    e <- 0 until nExu
    s <- 0 until numSrc
  } {
    val r = rc.io.read(e * numSrc + s)
    r.ren  := io.fromIQ(e).valid && DataSource.readRegCache(io.fromIQ(e).bits.common.dataSources(s))
    r.addr := io.fromIQ(e).bits.regCacheIdx(s)
  }
  for (k <- 0 until AnsIssueParams.nIntWb) {
    rc.io.write(k).wen  := io.intWriteBack(k).wen
    rc.io.write(k).addr := rc.io.replaceIdx(k)
    rc.io.write(k).data := io.intWriteBack(k).data
  }
  val rcRdata     = rc.io.read.map(_.data)

  // ------------------------------------------------------------------
  // PC / target
  // ------------------------------------------------------------------
  val pcExus      = AnsIssueParams.allExus.filter(_.needPc)
  val pcPortOfExu = pcExus.zipWithIndex.map { case (e, i) => e.exuIdx -> i }.toMap
  val pcMem       = Module(new AnsPcTargetMem(params, pcExus.size))

  pcMem.io.write := io.pcMemWrite
  pcExus.zipWithIndex.foreach { case (e, i) =>
    val data0 = io.fromIQ(e.exuIdx)
    pcMem.io.read(i).valid     := data0.valid
    pcMem.io.read(i).ftqPtr    := data0.bits.common.ftqPtr
    pcMem.io.read(i).ftqOffset := data0.bits.common.ftqOffset
  }

  // ------------------------------------------------------------------
  // Central Wb busy table
  // ------------------------------------------------------------------
  val wbBusy = Module(new AnsWbFuBusyTable(params))
  wbBusy.io.write    := io.wbBusyTableWrite
  io.wbBusyTableRead := wbBusy.io.read
  io.wbConflict      := wbBusy.io.conflict
  io.toIq.wbConflict := wbBusy.io.conflict

  // ------------------------------------------------------------------
  // data0 / data1
  // ------------------------------------------------------------------
  val issueShape = io.fromIQ(0).bits.cloneType
  val data1Valid = RegInit(VecInit(Seq.fill(nExu)(false.B)))
  val data1Data  = Reg(Vec(nExu, issueShape))

  // 只传播前一拍固定 latency0 的 data0 失败；不可按 EXU 身份撤销更早的结果。
  val blocked0Lat   = Wire(Vec(nExu, Bool()))
  val blocked0LatD1 = RegNext(blocked0Lat, 0.U.asTypeOf(blocked0Lat))
  val prevFlush     = RegNext(io.flush, 0.U.asTypeOf(io.flush))

  val srcNotBlockVec = Wire(Vec(nExu, Bool()))
  val intWbOkVec     = Wire(Vec(nExu, Bool()))
  val fpWbOkVec      = Wire(Vec(nExu, Bool()))

  for (e <- 0 until nExu) {
    val exu   = AnsIssueParams.exuByIndex(e)
    val data0 = io.fromIQ(e)

    val srcNotBlock = (0 until numSrc)
      .map { s =>
        val intSlot             = exu.intRdPorts(s) >= 0
        val fpSlot              = exu.fpRdPorts(s) >= 0
        val intRead             = intSlot.B &&
          SrcType.isInteger(data0.bits.srcType(s)) && DataSource.readReg(data0.bits.common.dataSources(s))
        val fpRead              = fpSlot.B &&
          SrcType.isFloating(data0.bits.srcType(s)) && DataSource.readReg(data0.bits.common.dataSources(s))
        // 0-lat 取消沿每拍选择的依赖链逐拍传播。
        val forwardFromCanceled = DataSource.readForward(data0.bits.common.dataSources(s)) &&
          VecInit((0 until nExu).map { p =>
            data0.bits.common.exuSources(s) === (p + 1).U &&
            blocked0LatD1(p)
          }).asUInt.orR
        (!intRead || intArb.io.grant(e)(s)) &&
        (!fpRead || fpArb.io.grant(e)(s)) &&
        !forwardFromCanceled
      }
      .reduce(_ && _)

    val intWbOk         = !data0.bits.common.intWen || wbCollide.io.intGrant(e)
    val fpWbOk          = !data0.bits.common.fpWen || wbCollide.io.fpGrant(e)
    val data0LoadCancel = LoadShouldCancel(data0.bits.common.loadDependency, io.ldCancel)
    val notBlock        = srcNotBlock && intWbOk && fpWbOk && !data0LoadCancel
    srcNotBlockVec(e) := srcNotBlock
    intWbOkVec(e)     := intWbOk
    fpWbOkVec(e)      := fpWbOk

    val data1EntryFlush = AnsIssueUtils.needFlush(data0.bits.common.robIdx, io.flush) ||
      AnsIssueUtils.needFlush(data0.bits.common.robIdx, prevFlush)
    val data1OutFlush   = AnsIssueUtils.needFlush(data1Data(e).common.robIdx, io.flush) ||
      AnsIssueUtils.needFlush(data1Data(e).common.robIdx, prevFlush)

    // data0 只在失败时发 block；通过时静默（valid=0），条目据此把 timer 推进到 data1。
    val data0Blocked = data0.valid && !notBlock
    val isFixed0     = exu.fuLatencyMap
      .filter(_._2 == 0)
      .keys
      .map(fu => data0.bits.common.fuType === fu.U)
      .foldLeft(false.B)(_ || _)
    blocked0Lat(e) := data0Blocked && isFixed0

    io.toIq.data0Resp(e).valid              := data0Blocked
    io.toIq.data0Resp(e).bits.resp          := IssueRespType.block.U
    io.toIq.data0Resp(e).bits.robIdx        := data0.bits.common.robIdx
    io.toIq.data0Resp(e).bits.fuType        := data0.bits.common.fuType
    io.toIq.data0Cancel(e)                  := data0Blocked
    io.toIq.data0CancelToDispatch(e).intWen := data0Blocked && data0.bits.common.intWen
    io.toIq.data0CancelToDispatch(e).fpWen  := data0Blocked && data0.bits.common.fpWen
    io.toIq.data0CancelToDispatch(e).pdest  := data0.bits.common.pdest

    // 接收即消费本次尝试；失败由响应通知 IQ 重试，不在 data0/data1 保持等待。
    data0.ready := true.B

    data1Valid(e) := data0.fire && notBlock && !data1EntryFlush
    when(data0.fire && notBlock && !data1EntryFlush) {
      data1Data(e)                       := data0.bits
      data1Data(e).common.loadDependency := AnsIssueUtils.shiftLoadDependency(data0.bits.common.loadDependency)
    }

    val data1LoadCancel = LoadShouldCancel(data1Data(e).common.loadDependency, io.ldCancel)
    when(data1Valid(e) && !data1OutFlush && !data1LoadCancel && exu.isFixedFu(data1Data(e).common.fuType)) {
      assert(io.toBypassNetwork(e).ready, "fixed data1 must be accepted after data0")
    }
    val data1Resp       = Wire(new IssueEntryResponse(params))
    data1Resp.resp   := Mux(
      data1OutFlush,
      IssueRespType.success.U,
      Mux(io.toBypassNetwork(e).ready && !data1LoadCancel, IssueRespType.success.U, IssueRespType.block.U),
    )
    data1Resp.robIdx := data1Data(e).common.robIdx
    data1Resp.fuType := data1Data(e).common.fuType

    io.toIq.data1Resp(e).valid := data1Valid(e)
    io.toIq.data1Resp(e).bits  := data1Resp
    // load cancel 返回 block，确保 IQ 保留条目；redirect 由 IQ 自身清理。
    io.toIq.data1Cancel(e)     := data1Valid(e) && IssueRespType.isBlocked(data1Resp.resp)
    io.toIq.rcDest(e)          := (if (exu.intWbPort >= 0) rc.io.replaceIdx(exu.intWbPort) else 0.U)

    // ---- data1：填 PRF 数据与控制信息；数据源选择交给 BypassNetwork ----
    val exuIn = WireInit(data1Data(e).common)

    for (s <- 0 until numSrc) {
      val srcType = data1Data(e).srcType(s)
      val intPort = exu.intRdPorts(s)
      val fpPort  = exu.fpRdPorts(s)
      val intSel  = if (intPort >= 0) SrcType.isInteger(srcType) else false.B
      val fpSel   = if (fpPort >= 0) SrcType.isFloating(srcType) else false.B
      val intData = if (intPort >= 0) intRfRdata(intPort) else 0.U(params.xlen.W)
      val fpData  = if (fpPort >= 0) fpRfRdata(fpPort) else 0.U(params.xlen.W)
      exuIn.src(s) := Mux(intSel, intData, Mux(fpSel, fpData, 0.U(params.xlen.W)))
    }

    if (exu.needPc) {
      val port = pcPortOfExu(e)
      exuIn.pc            := pcMem.io.read(port).pc
      exuIn.predictTarget := pcMem.io.read(port).target
    }

    io.toBypassNetwork(e).valid := data1Valid(e) && !data1OutFlush && !data1LoadCancel
    io.toBypassNetwork(e).bits  := exuIn

    for (s <- 0 until numSrc) {
      io.rcData(e)(s) := rcRdata(e * numSrc + s)
    }
  }

  // ------------------------------------------------------------------
  // 只读性能计数器
  // ------------------------------------------------------------------
  for (p <- 0 until AnsIssueParams.nIntRd) {
    val conflict = AnsDataPathParams.intRdGroups
      .get(p)
      .map { members =>
        PopCount(VecInit(members.map(m => intArb.io.reqValid(m.exuIdx)(m.srcIdx)))) > 1.U
      }
      .getOrElse(false.B)
    val cnt      = RegInit(0.U(32.W))
    cnt                      := cnt + conflict.asUInt
    io.perf.intRdConflict(p) := cnt
  }
  for (p <- 0 until AnsIssueParams.nFpRd) {
    val conflict = AnsDataPathParams.fpRdGroups
      .get(p)
      .map { members =>
        PopCount(VecInit(members.map(m => fpArb.io.reqValid(m.exuIdx)(m.srcIdx)))) > 1.U
      }
      .getOrElse(false.B)
    val cnt      = RegInit(0.U(32.W))
    cnt                     := cnt + conflict.asUInt
    io.perf.fpRdConflict(p) := cnt
  }
  for (p <- 0 until AnsIssueParams.nIntWb) {
    val members  = AnsIssueParams.allExus.filter(_.intWbPort == p)
    val conflict = members.map(e => wbCollide.io.intConflict(e.exuIdx)).reduceOption(_ || _).getOrElse(false.B)
    val cnt      = RegInit(0.U(32.W))
    cnt                      := cnt + conflict.asUInt
    io.perf.intWbConflict(p) := cnt
  }
  for (p <- 0 until AnsIssueParams.nFpWb) {
    val members  = AnsIssueParams.allExus.filter(_.fpWbPort == p)
    val conflict = members.map(e => wbCollide.io.fpConflict(e.exuIdx)).reduceOption(_ || _).getOrElse(false.B)
    val cnt      = RegInit(0.U(32.W))
    cnt                     := cnt + conflict.asUInt
    io.perf.fpWbConflict(p) := cnt
  }

  val readBlockCnt = RegInit(0.U(32.W))
  val wbBlockCnt   = RegInit(0.U(32.W))
  readBlockCnt           := readBlockCnt + PopCount((0 until nExu).map(e => io.fromIQ(e).valid && !srcNotBlockVec(e))).asUInt
  wbBlockCnt             := wbBlockCnt + PopCount(
    (0 until nExu).map(e => io.fromIQ(e).valid && srcNotBlockVec(e) && (!intWbOkVec(e) || !fpWbOkVec(e)))
  ).asUInt
  io.perf.data0BlockRead := readBlockCnt
  io.perf.data0BlockWb   := wbBlockCnt

  io.rcReplaceIdx := VecInit((0 until nExu).map { e =>
    val exu = AnsIssueParams.exuByIndex(e)
    if (exu.intWbPort >= 0) rc.io.replaceIdx(exu.intWbPort) else 0.U
  })
}
