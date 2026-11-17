/* DataPath：IssueQueue 之后、EXU 之前的 data0/data1 两级。
 *
 * data0：PRF 读口仲裁、WB 端口冲突检查、PC/target 查询；
 * data1：按 dataSources 选择 PRF / RegCache / forward / bypass / imm / zero，
 *     填好 ExuInput 送给执行单元，并产生 data0/data1 响应与 cancel。
 *
 * 本版没有 forward 同拍发射（IQ 唤醒在 Backend 统一打一拍），因此
 * DataSource.forward 仍保留但在本实现中不会从 IQ 产生。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.{AnsRenameParams, AnsRenameRedirect}

object AnsDataPath {

  /** 静态 PRF 读口映射：一个 EXU 的源槽使用哪个物理读口。 */
  final case class ReadPortMember(exuIdx: Int, srcIdx: Int, portIdx: Int)
}

class AnsDataPath(val params: BundleParams = BundleParams()) extends Module {
  import AnsDataPath.ReadPortMember

  AnsIssueParams.validate(params)
  val io = IO(new AnsDataPathIO(params))

  val nExu   = AnsIssueParams.nExu
  val numSrc = params.numSrc

  def readPortGroups(portsOf: AnsExuParams => Seq[Int]): Map[Int, Seq[ReadPortMember]] =
    AnsIssueParams.allExus
      .flatMap { exu =>
        portsOf(exu).zipWithIndex.collect {
          case (port, src) if port >= 0 => ReadPortMember(exu.exuIdx, src, port)
        }
      }
      .groupBy(_.portIdx)

  val intRdGroups = readPortGroups(_.intRdPorts)
  val fpRdGroups  = readPortGroups(_.fpRdPorts)
  val nIntRd      = AnsIssueParams.nIntRd
  val nFpRd       = AnsIssueParams.nFpRd

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

  /** 地址与授权使用同一个 one-hot 获胜者；组内顺序为 EXU 编号、源槽编号。 */
  def connectReadPorts(
    groups:       Map[Int, Seq[ReadPortMember]],
    isSourceType: UInt => Bool,
    addresses:    Vec[UInt],
    grants:       Vec[Vec[Bool]],
  ): Unit = {
    addresses.foreach(_ := 0.U)
    for ((port, members) <- groups.toSeq.sortBy(_._1)) {
      val requests           = members.map { member =>
        val in = io.fromIQ(member.exuIdx)
        in.valid && isSourceType(in.bits.srcType(member.srcIdx)) &&
        DataSource.readReg(in.bits.common.dataSources(member.srcIdx))
      }
      val winnerOH           = PriorityEncoderOH(VecInit(requests).asUInt)
      val requestedAddresses = members.map(m => io.fromIQ(m.exuIdx).bits.psrc(m.srcIdx))
      addresses(port) := Mux1H(winnerOH, requestedAddresses)
      members.zipWithIndex.foreach { case (member, i) =>
        grants(member.exuIdx)(member.srcIdx) := !requests(i) || winnerOH(i)
      }
    }
  }

  connectReadPorts(intRdGroups, SrcType.isInteger, intRfRaddr, intGrant)
  connectReadPorts(fpRdGroups, SrcType.isFloating, fpRfRaddr, fpGrant)

  val intRfRdata = RegNext(VecInit(intRfRaddr.map(a => intRf(a))))
  val fpRfRdata  = RegNext(VecInit(fpRfRaddr.map(a => fpRf(a))))

  // 同拍写回口优先级：共享同一写口的多个候选只放行编号最小者，其余 data0 block。
  def writebackWinners(
    numPorts: Int,
    portOf:   AnsExuParams => Int,
    writes:   IssueQueueIssueBundle => Bool,
  ): Vec[Bool] = {
    val winners = WireInit(VecInit(Seq.fill(nExu)(true.B)))
    for (port <- 0 until numPorts) {
      val members = AnsIssueParams.allExus.filter(e => portOf(e) == port)
      if (members.nonEmpty) {
        val requests = members.map(e => io.fromIQ(e.exuIdx).valid && writes(io.fromIQ(e.exuIdx).bits))
        val winnerOH = PriorityEncoderOH(VecInit(requests).asUInt)
        members.zipWithIndex.foreach { case (exu, i) =>
          winners(exu.exuIdx) := !requests(i) || winnerOH(i)
        }
      }
    }
    winners
  }

  val intWbWin = writebackWinners(AnsIssueParams.nIntWb, _.intWbPort, _.common.intWen)
  val fpWbWin  = writebackWinners(AnsIssueParams.nFpWb, _.fpWbPort, _.common.fpWen)

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

  val exuOutBypass1 = RegNext(io.exuOutput)
  val exuOutBypass2 = RegNext(exuOutBypass1)
  val prevFlush     = RegNext(io.flush)

  for (e <- 0 until nExu) {
    val exu   = AnsIssueParams.exuByIndex(e)
    val data0 = io.fromIQ(e)

    val srcNotBlock = (0 until numSrc)
      .map { s =>
        if (exu.intRdPorts(s) >= 0 || exu.fpRdPorts(s) >= 0) {
          val intOk =
            if (exu.intRdPorts(s) >= 0)
              !SrcType
                .isInteger(data0.bits.srcType(s)) || !DataSource.readReg(data0.bits.common.dataSources(s)) || intGrant(
                e
              )(s)
            else true.B
          val fpOk  =
            if (exu.fpRdPorts(s) >= 0)
              !SrcType
                .isFloating(data0.bits.srcType(s)) || !DataSource.readReg(data0.bits.common.dataSources(s)) || fpGrant(
                e
              )(s)
            else true.B
          intOk && fpOk
        } else true.B
      }
      .reduce(_ && _)

    val wbOk     = (!data0.bits.common.intWen || intWbWin(e)) &&
      (!data0.bits.common.fpWen || fpWbWin(e))
    val notBlock = srcNotBlock && wbOk

    val data1EntryFlush = AnsIssueUtils.needFlush(data0.bits.common.robIdx, io.flush) ||
      AnsIssueUtils.needFlush(data0.bits.common.robIdx, prevFlush)
    val data1OutFlush   = AnsIssueUtils.needFlush(data1Data(e).common.robIdx, io.flush) ||
      AnsIssueUtils.needFlush(data1Data(e).common.robIdx, prevFlush)

    io.toIq.data0Resp(e).valid              := data0.valid
    io.toIq.data0Resp(e).bits.ready         := notBlock
    io.toIq.data0Resp(e).bits.robIdx        := data0.bits.common.robIdx
    io.toIq.data0Resp(e).bits.fuType        := data0.bits.common.fuType
    io.toIq.data0Cancel(e)                  := data0.valid && !notBlock
    io.toIq.data0CancelToDispatch(e).intWen := data0.valid && !notBlock && data0.bits.common.intWen
    io.toIq.data0CancelToDispatch(e).fpWen  := data0.valid && !notBlock && data0.bits.common.fpWen
    io.toIq.data0CancelToDispatch(e).pdest  := data0.bits.common.pdest

    // 接收即消费本次尝试；失败由响应通知 IQ 重试，不在 data0/data1 保持等待。
    data0.ready := true.B

    data1Valid(e) := data0.fire && notBlock && !data1EntryFlush
    when(data0.fire && notBlock && !data1EntryFlush) {
      data1Data(e) := data0.bits
    }

    io.toIq.data1Resp(e).valid       := data1Valid(e)
    io.toIq.data1Resp(e).bits.ready  := Mux(data1OutFlush, true.B, io.toExu(e).ready)
    io.toIq.data1Resp(e).bits.robIdx := data1Data(e).common.robIdx
    io.toIq.data1Resp(e).bits.fuType := data1Data(e).common.fuType
    io.toIq.data1Cancel(e)           := data1Valid(e) && !data1OutFlush && !io.toExu(e).ready
    io.toIq.rcDest(e)                := (if (exu.intWbPort >= 0) rc.io.replaceIdx(exu.intWbPort) else 0.U)

    // ---- data1 operand selection ----
    val exuIn = WireInit(data1Data(e).common)

    def exuSelect(srcIdx: Int, dataVec: Seq[UInt], validVec: Seq[Bool]): UInt = {
      val oh = (0 until nExu).map { p =>
        data1Data(e).common.exuSources(srcIdx) === (p + 1).U
      }
      Mux1H(oh, (0 until nExu).map(p => Mux(validVec(p), dataVec(p), 0.U)))
    }

    for (s <- 0 until numSrc) {
      val ds      = data1Data(e).common.dataSources(s)
      val srcType = data1Data(e).srcType(s)
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
      val imm     = ImmPacking.extend(data1Data(e).common.imm, data1Data(e).common.immType, params.xlen)
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

    io.toExu(e).valid := data1Valid(e) && !data1OutFlush
    io.toExu(e).bits  := exuIn
  }

  io.rcReplaceIdx := VecInit((0 until nExu).map { e =>
    val exu = AnsIssueParams.exuByIndex(e)
    if (exu.intWbPort >= 0) rc.io.replaceIdx(exu.intWbPort) else 0.U
  })
}
