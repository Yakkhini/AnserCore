/* DataPath：IssueQueue 之后、EXU 之前的 S0/S1 两级。
 *
 * S0：PRF 读口仲裁、WB 端口冲突检查、PC/target 查询；
 * S1：按 dataSources 选择 PRF / RegCache / forward / bypass / imm / zero，
 *     填好 ExuInput 送给执行单元，并产生 S0/S1 响应与 cancel。
 *
 * 本版没有 forward 同拍发射（IQ 唤醒在 Scheduler 统一打一拍），因此
 * DataSource.forward 仍保留但在本实现中不会从 IQ 产生。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.{AnsRenameParams, AnsRenameRedirect}

class AnsDataPath(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsDataPathIO(params))

  val nExu   = AnsIssueParams.nExu
  val numSrc = params.numSrc

  val intRdGroups: Map[Int, Seq[(Int, Int)]] =
    (0 until nExu)
      .flatMap { e =>
        val exu = AnsIssueParams.exuByIndex(e)
        (0 until numSrc).map(s => (e, s, exu.intRdPorts(s))).filter(_._3 >= 0)
      }
      .groupBy(_._3)
      .view
      .mapValues(_.map(x => (x._1, x._2)).toSeq)
      .toMap

  val fpRdGroups: Map[Int, Seq[(Int, Int)]] =
    (0 until nExu)
      .flatMap { e =>
        val exu = AnsIssueParams.exuByIndex(e)
        (0 until numSrc).map(s => (e, s, exu.fpRdPorts(s))).filter(_._3 >= 0)
      }
      .groupBy(_._3)
      .view
      .mapValues(_.map(x => (x._1, x._2)).toSeq)
      .toMap

  val nIntRd = intRdGroups.keys.foldLeft(0)(_ max _) + 1
  val nFpRd  = fpRdGroups.keys.foldLeft(0)(_ max _) + 1

  // ------------------------------------------------------------------
  // PRF
  // ------------------------------------------------------------------
  val intRf = Reg(Vec(AnsRenameParams.intPhyRegs, UInt(params.xlen.W)))
  val fpRf  = Reg(Vec(AnsRenameParams.fpPhyRegs, UInt(params.xlen.W)))

  for (k <- 0 until AnsIssueParams.nIntWb) {
    when(io.intWriteBack(k).wen) {
      intRf(io.intWriteBack(k).addr) := io.intWriteBack(k).data
    }
  }
  for (k <- 0 until AnsIssueParams.nFpWb) {
    when(io.fpWriteBack(k).wen) {
      fpRf(io.fpWriteBack(k).addr) := io.fpWriteBack(k).data
    }
  }

  val intRfRaddr = Wire(Vec(nIntRd, UInt(params.physicalRegWidth.W)))
  val fpRfRaddr  = Wire(Vec(nFpRd, UInt(params.physicalRegWidth.W)))
  val intGrant   = Wire(Vec(nExu, Vec(numSrc, Bool())))
  val fpGrant    = Wire(Vec(nExu, Vec(numSrc, Bool())))

  for {
    e <- 0 until nExu
    s <- 0 until numSrc
  } {
    intGrant(e)(s) := true.B
    fpGrant(e)(s)  := true.B
  }

  for ((port, members) <- intRdGroups) {
    val req  = members.map { case (e, s) =>
      io.fromIQ(e).valid &&
      SrcType.isInteger(io.fromIQ(e).bits.srcType(s)) &&
      DataSource.readReg(io.fromIQ(e).bits.common.dataSources(s))
    }
    val addr = members.map { case (e, s) => io.fromIQ(e).bits.psrc(s) }
    intRfRaddr(port) := Mux1H(req, addr)
    members.zipWithIndex.foreach { case ((e, s), i) =>
      intGrant(e)(s) := !req(i) || PriorityEncoder(VecInit(req)) === i.U
    }
  }

  for ((port, members) <- fpRdGroups) {
    val req  = members.map { case (e, s) =>
      io.fromIQ(e).valid &&
      SrcType.isFloating(io.fromIQ(e).bits.srcType(s)) &&
      DataSource.readReg(io.fromIQ(e).bits.common.dataSources(s))
    }
    val addr = members.map { case (e, s) => io.fromIQ(e).bits.psrc(s) }
    fpRfRaddr(port) := Mux1H(req, addr)
    members.zipWithIndex.foreach { case ((e, s), i) =>
      fpGrant(e)(s) := !req(i) || PriorityEncoder(VecInit(req)) === i.U
    }
  }

  val intRfRdata = RegNext(VecInit(intRfRaddr.map(a => intRf(a))))
  val fpRfRdata  = RegNext(VecInit(fpRfRaddr.map(a => fpRf(a))))

  // 同拍写回口优先级：共享同一写口的多个候选只放行编号最小者，其余 S0 block。
  val intWbWin = Wire(Vec(nExu, Bool()))
  val fpWbWin  = Wire(Vec(nExu, Bool()))
  for (e <- 0 until nExu) {
    intWbWin(e) := true.B
    fpWbWin(e)  := true.B
  }
  for (p <- 0 until AnsIssueParams.nIntWb) {
    val members = AnsIssueParams.allExus.filter(_.intWbPort == p)
    if (members.nonEmpty) {
      val req = members.map(e => io.fromIQ(e.exuIdx).valid && io.fromIQ(e.exuIdx).bits.common.intWen)
      val win = PriorityEncoder(VecInit(req))
      members.zipWithIndex.foreach { case (e, i) =>
        intWbWin(e.exuIdx) := !req(i) || win === i.U
      }
    }
  }
  for (p <- 0 until AnsIssueParams.nFpWb) {
    val members = AnsIssueParams.allExus.filter(_.fpWbPort == p)
    if (members.nonEmpty) {
      val req = members.map(e => io.fromIQ(e.exuIdx).valid && io.fromIQ(e.exuIdx).bits.common.fpWen)
      val win = PriorityEncoder(VecInit(req))
      members.zipWithIndex.foreach { case (e, i) =>
        fpWbWin(e.exuIdx) := !req(i) || win === i.U
      }
    }
  }

  // ------------------------------------------------------------------
  // RegCache（先保留端口；IQ 当前不产生 regcache 数据源）
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
    val s0 = io.fromIQ(e.exuIdx)
    pcMem.io.read(i).valid     := s0.valid
    pcMem.io.read(i).ftqPtr    := s0.bits.common.ftqPtr
    pcMem.io.read(i).ftqOffset := s0.bits.common.ftqOffset
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
  // S0 / S1
  // ------------------------------------------------------------------
  val issueShape = io.fromIQ(0).bits.cloneType
  val s1Valid    = RegInit(VecInit(Seq.fill(nExu)(false.B)))
  val s1Data     = Reg(Vec(nExu, issueShape))

  val exuOutBypass1 = RegNext(io.exuOutput)
  val exuOutBypass2 = RegNext(exuOutBypass1)
  val prevFlush     = RegNext(io.flush)

  for (e <- 0 until nExu) {
    val exu = AnsIssueParams.exuByIndex(e)
    val s0  = io.fromIQ(e)

    val srcNotBlock = (0 until numSrc)
      .map { s =>
        if (exu.intRdPorts(s) >= 0 || exu.fpRdPorts(s) >= 0) {
          val intOk =
            if (exu.intRdPorts(s) >= 0)
              !SrcType.isInteger(s0.bits.srcType(s)) || !DataSource.readReg(s0.bits.common.dataSources(s)) || intGrant(
                e
              )(s)
            else true.B
          val fpOk  =
            if (exu.fpRdPorts(s) >= 0)
              !SrcType.isFloating(s0.bits.srcType(s)) || !DataSource.readReg(s0.bits.common.dataSources(s)) || fpGrant(
                e
              )(s)
            else true.B
          intOk && fpOk
        } else true.B
      }
      .reduce(_ && _)

    val wbOk     = (!s0.bits.common.intWen || intWbWin(e)) &&
      (!s0.bits.common.fpWen || fpWbWin(e))
    val notBlock = srcNotBlock && wbOk

    val s1EntryFlush = AnsIssueUtils.needFlush(s0.bits.common.robIdx, io.flush) ||
      AnsIssueUtils.needFlush(s0.bits.common.robIdx, prevFlush)
    val s1OutFlush   = AnsIssueUtils.needFlush(s1Data(e).common.robIdx, io.flush) ||
      AnsIssueUtils.needFlush(s1Data(e).common.robIdx, prevFlush)

    io.toIq.data0Resp(e).valid       := s0.valid
    io.toIq.data0Resp(e).bits.ready  := notBlock
    io.toIq.data0Resp(e).bits.robIdx := s0.bits.common.robIdx
    io.toIq.data0Resp(e).bits.fuType := s0.bits.common.fuType
    io.toIq.data0Cancel(e)           := s0.valid && !notBlock
    io.toIq.s0Cancel(e).intWen       := s0.valid && !notBlock && s0.bits.common.intWen
    io.toIq.s0Cancel(e).fpWen        := s0.valid && !notBlock && s0.bits.common.fpWen
    io.toIq.s0Cancel(e).pdest        := s0.bits.common.pdest

    s0.ready := true.B

    s1Valid(e) := s0.valid && notBlock && !s1EntryFlush
    when(s0.valid && notBlock && !s1EntryFlush) {
      s1Data(e) := s0.bits
    }

    io.toIq.data1Resp(e).valid       := s1Valid(e)
    io.toIq.data1Resp(e).bits.ready  := Mux(s1OutFlush, true.B, io.toExu(e).ready)
    io.toIq.data1Resp(e).bits.robIdx := s1Data(e).common.robIdx
    io.toIq.data1Resp(e).bits.fuType := s1Data(e).common.fuType
    io.toIq.data1Cancel(e)           := s1Valid(e) && !s1OutFlush && !io.toExu(e).ready
    io.toIq.rcDest(e)                := (if (exu.intWbPort >= 0) rc.io.replaceIdx(exu.intWbPort) else 0.U)

    // ---- S1 operand selection ----
    val exuIn = WireInit(s1Data(e).common)

    def exuSelect(srcIdx: Int, dataVec: Seq[UInt], validVec: Seq[Bool]): UInt = {
      val oh = (0 until nExu).map { p =>
        s1Data(e).common.exuSources(srcIdx) === (p + 1).U
      }
      Mux1H(oh, (0 until nExu).map(p => Mux(validVec(p), dataVec(p), 0.U)))
    }

    for (s <- 0 until numSrc) {
      val ds      = s1Data(e).common.dataSources(s)
      val srcType = s1Data(e).srcType(s)
      val intPort = exu.intRdPorts(s)
      val fpPort  = exu.fpRdPorts(s)
      val intSel  = if (intPort >= 0) SrcType.isInteger(srcType) else false.B
      val fpSel   = if (fpPort >= 0) SrcType.isFloating(srcType) else false.B
      val rfData  = MuxCase(
        0.U(params.xlen.W),
        Seq(
          intSel -> (if (intPort >= 0) intRfRdata(intPort) else 0.U),
          fpSel  -> (if (fpPort >= 0) fpRfRdata(fpPort) else 0.U),
        ),
      )
      val rcData  = rcRdata(e * numSrc + s)
      val imm     = ImmPacking.extend(s1Data(e).common.imm, s1Data(e).common.immType, params.xlen)
      // forward 需要 EXU 组合输出；当前 IQ 唤醒打一拍，只用 1/2 拍旁路，
      // 这里不复用 io.exuOutput 的组合路径，避免组合环。
      val fwd     = 0.U(params.xlen.W)
      val by1     = exuSelect(s, exuOutBypass1.map(_.bits.data(0)), exuOutBypass1.map(_.valid))
      val by2     = exuSelect(s, exuOutBypass2.map(_.bits.data(0)), exuOutBypass2.map(_.valid))

      exuIn.src(s) := Mux1H(
        Seq(
          DataSource.readRegOH(ds)    -> rfData,
          DataSource.readRegCache(ds) -> rcData,
          DataSource.readForward(ds)  -> fwd,
          DataSource.readBypass(ds)   -> by1,
          DataSource.readBypass2(ds)  -> by2,
          DataSource.readImm(ds)      -> imm,
          DataSource.readZero(ds)     -> 0.U,
        )
      )
    }

    if (exu.needPc) {
      val port = pcPortOfExu(e)
      exuIn.pc            := pcMem.io.read(port).pc
      exuIn.predictTarget := pcMem.io.read(port).target
    }

    io.toExu(e).valid := s1Valid(e) && !s1OutFlush
    io.toExu(e).bits  := exuIn
  }

  io.rcReplaceIdx := VecInit((0 until nExu).map { e =>
    val exu = AnsIssueParams.exuByIndex(e)
    if (exu.intWbPort >= 0) rc.io.replaceIdx(exu.intWbPort) else 0.U
  })
}
