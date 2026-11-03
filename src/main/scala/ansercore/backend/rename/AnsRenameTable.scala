/* 雁栖湖寄存器别名表(RAT)。
 *
 * AnsRenameTable 对应昆明湖 RenameTable.scala，AnsRenameTableManager 对应 RenameTableWrapper.scala。
 * 只保留整数/浮点两张表，去掉向量 v0/vl、difftest 与调试旁路。
 *
 * 读写时序沿用昆明湖的 T0/T1 折叠(见 AnsRenameTable 内注释)：
 *   - 推测写的三源仲裁：redirect(快照/架构态) > walk/rename 推测写 > 保持；
 *   - 读端口用「锁存地址读表 + 写数据旁路」两路优先 mux。
 */
package ansercore.backend.rename

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.BundleParams
import AnsRenameParams._

class AnsRenameTableIO(val params: BundleParams, val isInteger: Boolean) extends Bundle {
  val tableWidth = log2Ceil(32)

  val redirect       = Input(Bool())
  val readPorts      =
    Vec(
      (if (isInteger) intReadPorts else fpReadPorts) * params.renameWidth,
      new AnsRatReadPort(tableWidth, params.physicalRegWidth),
    )
  val specWritePorts = Vec(rabCommitWidth, Input(new AnsRatWritePort(tableWidth, params.physicalRegWidth)))
  val archWritePorts = Vec(rabCommitWidth, Input(new AnsRatWritePort(tableWidth, params.physicalRegWidth)))
  val oldPdest       = Vec(rabCommitWidth, Output(UInt(params.physicalRegWidth.W)))
  val needFree       = Vec(rabCommitWidth, Output(Bool()))
  val snpt           = Input(new AnsSnapshotPort(params))
  val debugRdata     = Vec(32, Output(UInt(params.physicalRegWidth.W)))
  val debugSpecRdata = Vec(32, Output(UInt(params.physicalRegWidth.W)))

  val snapshotFull     = Output(Bool())
  val snapshotValids   = Output(Vec(renameSnapshotNum, Bool()))
  val snapshotEnqIndex = Output(UInt(snapshotSelectWidth.W))
}

class AnsRenameTable(val isInteger: Boolean, val params: BundleParams = BundleParams()) extends Module {
  val logicRegs  = 32
  val tableWidth = log2Ceil(logicRegs)

  val io = IO(new AnsRenameTableIO(params, isInteger))

  // spec/arch 初值：整数全 0(x0 -> p0)；浮点恒等映射(f_i -> p_i)。
  val initTable =
    if (isInteger) VecInit.fill(logicRegs)(0.U(params.physicalRegWidth.W))
    else VecInit.tabulate(logicRegs)(i => i.U(params.physicalRegWidth.W))

  val specTable     = RegInit(initTable)
  val specTableNext = WireInit(specTable)
  val archTable     = RegInit(initTable)
  val archTableNext = WireDefault(archTable)
  val oldPdest      = RegInit(VecInit(Seq.fill(rabCommitWidth)(0.U(params.physicalRegWidth.W))))
  val needFree      = RegInit(VecInit(Seq.fill(rabCommitWidth)(false.B)))

  // 写：T0 的 specWritePorts 实际在 T1 生效(t1WSpec)；读：T0 锁存地址，T1 读表，
  // 并把 T0 写入的数据旁路到 T1 的读数据。
  val t1Redirect = RegNext(io.redirect, false.B)
  val t1Raddr    = io.readPorts.map(port => RegEnable(port.addr, !port.hold))
  val t1Rdata    = VecInit(t1Raddr.map(specTable(_)))
  val t1WSpec    = RegNext(Mux(io.redirect, 0.U.asTypeOf(io.specWritePorts), io.specWritePorts))
  val t1Snpt     = RegNext(io.snpt, 0.U.asTypeOf(io.snpt))
  val t2Snpt     = RegNext(t1Snpt, 0.U.asTypeOf(io.snpt))
  val t2Redirect = RegNext(t1Redirect, false.B)

  val snapshotGen = Module(new AnsSnapshotGenerator(specTable))
  snapshotGen.io.enq      := t1Snpt.snptEnq
  snapshotGen.io.enqData  := specTable
  snapshotGen.io.deq      := t1Snpt.snptDeq
  snapshotGen.io.redirect := t1Redirect
  snapshotGen.io.flushVec := t1Snpt.flushVec
  val snapshots = snapshotGen.io.snapshots

  io.snapshotFull     := snapshotGen.io.full
  io.snapshotValids   := snapshotGen.io.valids
  io.snapshotEnqIndex := snapshotGen.io.enqIndex

  // spec 表三源仲裁：redirect 用快照/架构态全表替换，否则用推测写(最近优先级最高)。
  val t1WSpecOH = t1WSpec.map(w => Mux(w.wen, UIntToOH(w.addr), 0.U))
  for ((next, entry) <- specTableNext.zipWithIndex) {
    val matchVec = t1WSpecOH.map(_.apply(entry))
    // bypass youngest write for same physical register 
    val wMatch   = AnsRenameUtil.priorityMux(matchVec.reverse, t1WSpec.map(_.data).reverse)
    next := Mux(
      t2Redirect,
      Mux(t2Snpt.useSnpt, snapshots(t2Snpt.snptSelect)(entry), archTable(entry)),
      Mux(VecInit(matchVec).asUInt.orR, wMatch, specTable(entry)),
    )
  }
  specTable := specTableNext

  // 读端口：写入旁路优先于表内容。
  for ((port, i) <- io.readPorts.zipWithIndex) {
    val t0Bypass   = io.specWritePorts.map { w =>
      w.wen && Mux(port.hold, w.addr === t1Raddr(i), w.addr === port.addr)
    }
    val t1Bypass   = RegNext(Mux(io.redirect, 0.U.asTypeOf(VecInit(t0Bypass)), VecInit(t0Bypass)))
    // bypass youngest write for same physical register 
    val bypassData = AnsRenameUtil.priorityMux(t1Bypass.reverse, t1WSpec.map(_.data).reverse)
    port.data := Mux(t1Bypass.asUInt.orR, bypassData, t1Rdata(i))
  }

  // 架构表写 + 旧物理寄存器追踪(用于 FreeList 回收)。
  for ((w, i) <- io.archWritePorts.zipWithIndex) {
    when(w.wen)(archTableNext(w.addr) := w.data)
    val archMask = VecInit(Seq.fill(params.physicalRegWidth)(w.wen)).asUInt
    oldPdest(i) := MuxCase(
      archTable(w.addr) & archMask,
      io.archWritePorts.take(i).reverse.map(x => (x.wen && x.addr === w.addr, x.data & archMask)),
    )
  }
  archTable := archTableNext

  // need_free 只对整数表有意义：move 消除会让多个逻辑寄存器共享同一物理寄存器，
  // 必须全表扫描确认旧物理寄存器不再被任何架构寄存器引用后才能回收。
  if (isInteger) {
    for (((old, free), i) <- oldPdest.zip(needFree).zipWithIndex) {
      val blockedByDuplicate = if (i == 0) false.B else VecInit(oldPdest.take(i).map(_ === old)).asUInt.orR
      free := VecInit(archTable.map(_ =/= old)).asUInt.andR && !blockedByDuplicate
    }
  } else {
    needFree := VecInit(Seq.fill(rabCommitWidth)(false.B))
  }

  io.oldPdest       := oldPdest
  io.needFree       := needFree
  io.debugRdata     := archTable
  io.debugSpecRdata := specTable

  if (isInteger) {
    for (w <- io.specWritePorts) {
      when(w.wen)(assert(w.addr =/= 0.U, "integer speculative RAT write must not target x0"))
    }
    for (w <- io.archWritePorts) {
      when(w.wen)(assert(w.addr =/= 0.U, "integer architectural RAT write must not target x0"))
    }
  }
  when(t2Redirect && t2Snpt.useSnpt) {
    assert(snapshotGen.io.valids(t2Snpt.snptSelect), "RAT restore selected an invalid snapshot")
  }
}

class AnsRenameTableManagerIO(val params: BundleParams) extends Bundle {
  val intAddrWidth = log2Ceil(intLogicRegs)
  val fpAddrWidth  = log2Ceil(fpLogicRegs)

  val redirect   = Input(Bool())
  val rabCommits = Input(new AnsRabCommitIO(params))
  val snpt       = Input(new AnsSnapshotPort(params))

  val intReadPorts   =
    Vec(
      params.renameWidth,
      Vec(AnsRenameParams.intReadPorts, new AnsRatReadPort(intAddrWidth, params.physicalRegWidth)),
    )
  val fpReadPorts    =
    Vec(params.renameWidth, Vec(AnsRenameParams.fpReadPorts, new AnsRatReadPort(fpAddrWidth, params.physicalRegWidth)))
  val intRenamePorts =
    Vec(params.renameWidth, Input(new AnsRatWritePort(intAddrWidth, params.physicalRegWidth)))
  val fpRenamePorts  =
    Vec(params.renameWidth, Input(new AnsRatWritePort(fpAddrWidth, params.physicalRegWidth)))

  val intOldPdest  = Vec(rabCommitWidth, Output(UInt(params.physicalRegWidth.W)))
  val fpOldPdest   = Vec(rabCommitWidth, Output(UInt(params.physicalRegWidth.W)))
  val intNeedFree  = Vec(rabCommitWidth, Output(Bool()))
  val debugIntRat  = Vec(intLogicRegs, Output(UInt(params.physicalRegWidth.W)))
  val debugFpRat   = Vec(fpLogicRegs, Output(UInt(params.physicalRegWidth.W)))
  val debugIntSpec = Vec(intLogicRegs, Output(UInt(params.physicalRegWidth.W)))
  val debugFpSpec  = Vec(fpLogicRegs, Output(UInt(params.physicalRegWidth.W)))
}

/** 把两张 RAT、读端口与提交/回滚写端口封装到一起。 */
class AnsRenameTableManager(val params: BundleParams = BundleParams()) extends Module {
  require(params.renameWidth == rabCommitWidth, "walk 写端口与 rename 写端口需共用同一组推测写端口")

  val io = IO(new AnsRenameTableManagerIO(params))

  val intRat = Module(new AnsRenameTable(isInteger = true, params))
  val fpRat  = Module(new AnsRenameTable(isInteger = false, params))

  for (rat <- Seq(intRat, fpRat)) {
    rat.io.redirect := io.redirect
    rat.io.snpt     := io.snpt
  }

  intRat.io.readPorts.zip(io.intReadPorts.flatten).foreach { case (ratPort, port) =>
    ratPort.hold := port.hold
    ratPort.addr := port.addr
    port.data    := ratPort.data
  }
  fpRat.io.readPorts.zip(io.fpReadPorts.flatten).foreach { case (ratPort, port) =>
    ratPort.hold := port.hold
    ratPort.addr := port.addr
    port.data    := ratPort.data
  }

  for (i <- 0 until rabCommitWidth) {
    val info        = io.rabCommits.info(i)
    val commitValid = io.rabCommits.commitValid(i)
    val walkValid   = io.rabCommits.walkValid(i)

    // 架构写：提交时更新。
    intRat.io.archWritePorts(i).wen  := io.rabCommits.isCommit && commitValid && info.rfWen
    intRat.io.archWritePorts(i).addr := info.ldest
    intRat.io.archWritePorts(i).data := info.pdest
    fpRat.io.archWritePorts(i).wen   := io.rabCommits.isCommit && commitValid && info.fpWen
    fpRat.io.archWritePorts(i).addr  := info.ldest
    fpRat.io.archWritePorts(i).data  := info.pdest

    // 推测写：先放 walk 恢复，稍后由 rename 推测写覆盖。
    intRat.io.specWritePorts(i).wen  := io.rabCommits.isWalk && walkValid && info.rfWen
    intRat.io.specWritePorts(i).addr := info.ldest
    intRat.io.specWritePorts(i).data := info.pdest
    fpRat.io.specWritePorts(i).wen   := io.rabCommits.isWalk && walkValid && info.fpWen
    fpRat.io.specWritePorts(i).addr  := info.ldest
    fpRat.io.specWritePorts(i).data  := info.pdest
  }

  for ((spec, rename) <- intRat.io.specWritePorts.zip(io.intRenamePorts)) {
    when(rename.wen) {
      spec.wen  := true.B
      spec.addr := rename.addr
      spec.data := rename.data
    }
  }
  for ((spec, rename) <- fpRat.io.specWritePorts.zip(io.fpRenamePorts)) {
    when(rename.wen) {
      spec.wen  := true.B
      spec.addr := rename.addr
      spec.data := rename.data
    }
  }

  io.intOldPdest  := intRat.io.oldPdest
  io.fpOldPdest   := fpRat.io.oldPdest
  io.intNeedFree  := intRat.io.needFree
  io.debugIntRat  := intRat.io.debugRdata
  io.debugFpRat   := fpRat.io.debugRdata
  io.debugIntSpec := intRat.io.debugSpecRdata
  io.debugFpSpec  := fpRat.io.debugSpecRdata

  assert(intRat.io.snapshotFull === fpRat.io.snapshotFull, "integer/floating RAT snapshot fullness differs")
  assert(intRat.io.snapshotValids === fpRat.io.snapshotValids, "integer/floating RAT snapshot valids differ")
  assert(intRat.io.snapshotEnqIndex === fpRat.io.snapshotEnqIndex, "integer/floating RAT snapshot indices differ")
}
