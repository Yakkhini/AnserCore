/* 雁栖湖 CtrlBlock，对应设计文档 §4.1 的 CtrlBlock 子块。
 *
 * 负责 Decode 到 Dispatch 的边界：组合译码 -> T1 保持寄存器（含融合补丁）-> Rename
 * -> renamePipeDispatch -> Dispatch。Dispatch 之后连接 IQ / ROB / LSQ。
 * ROB / RedirectGenerator / SnapshotGenerator 尚未实现，因此 CtrlBlock 在 AnsCtrlBlockIO 中保留这些内部边界；
 * AnsBackend 作为后端组合根连接它们（CSR/ROB 由 stub 模块占位），不把这些信号暴露到 Backend 顶层。
 */
package ansercore.backend

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.decode.{AnsDecodeGroup, AnsFusionDecodeInfo, AnsFusionDecoder}
import ansercore.backend.dispatch._
import ansercore.backend.exu.csr.AnsCsrDecodeState
import ansercore.backend.rename._

/** CtrlBlock 的调试镜像，覆盖 decode-to-rename 边界。 */
class AnsCtrlBlockDebugIO(val params: BundleParams = BundleParams()) extends Bundle {
  val t1Valid       = Output(Vec(params.renameWidth, Bool()))
  val renameInValid = Output(Vec(params.renameWidth, Bool()))
  val renameInReady = Output(Vec(params.renameWidth, Bool()))
  val renameIn      = Output(Vec(params.renameWidth, new DecodedInst(params)))

  val intRat = Output(
    Vec(
      params.renameWidth,
      Vec(2, new AnsRatReadRequest(params.logicalRegWidth)),
    )
  )
  val fpRat  = Output(
    Vec(
      params.renameWidth,
      Vec(3, new AnsRatReadRequest(params.logicalRegWidth)),
    )
  )

  val fusionInfo = Output(Vec(params.renameWidth - 1, new AnsFusionDecodeInfo))
  val dispatch   = Output(new AnsDispatchDebugIO(params))
}

/** CtrlBlock 顶层端口。 */
class AnsCtrlBlockIO(val params: BundleParams = BundleParams()) extends Bundle {
  // Frontend -> CtrlBlock.
  val in            = Vec(params.renameWidth, Flipped(Decoupled(new StaticInst(params))))
  val redirect      = Input(Valid(new AnsRenameRedirect(params)))
  val disableFusion = Input(Bool())

  // CtrlBlock -> frontend .
  val trapInstInfo = Output(Valid(new TrapInstInfo(params)))
  val stallReason  = Output(new AnsRenameStallReason)

  // Temporary top-level inputs until the ROB exists.
  val rabCommits  = Input(new AnsRabCommitIO(params))
  val snpt        = Input(new AnsSnapshotPort(params))
  val snptLastEnq = Input(Valid(UInt(params.robIndexWidth.W)))
  val snptIsFull  = Input(Bool())

  // Dispatch -> IQ / ROB / LSQ 边界；对端模块尚未实现。
  val toIssueQueues = Vec(AnsDispatchParams.IQEnqSum, Decoupled(new DynInst(params)))
  val enqRob        = Flipped(new AnsRobEnqIO(params))
  val lsqEnq        = Flipped(new AnsLsqEnqIO(params))

  // Scheduler / MemBlock 回送的状态。
  val IQValidNumVec = Input(Vec(AnsDispatchParams.IQEnqSum, UInt(AnsDispatchParams.iqValidNumWidth.W)))
  val wbPregsInt    = Input(Vec(AnsDispatchParams.nIntWb, Valid(UInt(params.physicalRegWidth.W))))
  val wbPregsFp     = Input(Vec(AnsDispatchParams.nFpWb, Valid(UInt(params.physicalRegWidth.W))))
  val wakeUpAll     = Input(new AnsIQWakeUpVec(params))
  val s0Cancel      = Input(Vec(AnsDispatchParams.nExu, new CancelSignal(params)))
  val ldCancel      = Input(Vec(params.loadPipelineWidth, new LoadCancel))
  val fromMem       = Input(new AnsMemToDispatchBundle(params))
  val singleStep    = Input(Bool())
  val fromCsr       = Input(new AnsCsrDecodeState)

  val debug = Output(new AnsCtrlBlockDebugIO(params))
}

class AnsCtrlBlock(val params: BundleParams = BundleParams()) extends Module {
  val width = params.renameWidth
  require(width >= 2, "fusion needs at least two rename lanes")
  require(params.numSrc >= 3, "RVB23 FMA needs three source slots")

  val io = IO(new AnsCtrlBlockIO(params))

  val decode     = Module(new AnsDecodeGroup(params))
  val fusion     = Module(new AnsFusionDecoder(params))
  val rename     = Module(new AnsRename(params))
  val renamePipe = Module(new AnsRenamePipeDispatch(params))
  val dispatch   = Module(new AnsDispatch(params))

  // ---- combinational decode ----
  decode.io.fromCsr := io.fromCsr
  for (lane <- 0 until width) {
    decode.io.in(lane).valid := io.in(lane).valid
    decode.io.in(lane).bits  := io.in(lane).bits
    io.in(lane).ready        := decode.io.in(lane).ready
  }

  // ---- T1 holding register at the decode/rename boundary ----
  val t1Valid   = RegInit(VecInit(Seq.fill(width)(false.B)))
  val decodedT1 = Reg(Vec(width, new DecodedInst(params)))

  val visibleValid = VecInit(t1Valid.zip(fusion.io.clear).map { case (valid, clear) => valid && !clear })
  val fired        = visibleValid
    .zip(rename.io.in.map(_.ready))
    .map { case (valid, ready) => !valid || ready } // only when no fuse should be valid
    .reduce(_ && _)
  val accept       = fired && !io.redirect.valid
  decode.io.accept := accept

  // Fusion samples the same accepted transaction as decodedT1.
  fusion.io.disableFusion := io.disableFusion
  fusion.io.accept        := accept
  for (lane <- 0 until width) {
    fusion.io.in(lane).valid := io.in(lane).valid
    fusion.io.in(lane).bits  := decode.io.out(lane)
  }

  when(io.redirect.valid) {
    t1Valid := VecInit(Seq.fill(width)(false.B))
  }.elsewhen(fired) {
    decodedT1 := decode.io.out
    t1Valid   := VecInit(io.in.map(_.valid))
  }.otherwise {
    for (lane <- 0 until width) {
      when(rename.io.in(lane).fire) {
        t1Valid(lane) := false.B
      }
    }
  }

  // Fusion patches the held instruction before it enters rename.
  val renameBits = Wire(Vec(width, new DecodedInst(params)))
  renameBits := decodedT1
  for (lane <- 0 until width - 1) {
    fusion.io.out(lane).bits.update(renameBits(lane))
    rename.io.fusionInfo(lane).rs2FromRs1  := fusion.io.info(lane).rs2FromRs1
    rename.io.fusionInfo(lane).rs2FromRs2  := fusion.io.info(lane).rs2FromRs2
    rename.io.fusionInfo(lane).rs2FromZero := fusion.io.info(lane).rs2FromZero
  }

  for (lane <- 0 until width) {
    rename.io.in(lane).valid := visibleValid(lane) && !io.redirect.valid
    rename.io.in(lane).bits  := renameBits(lane)
  }

  // RAT early read: addresses come from the pre-fusion decode result; hold comes
  // from the T1 state so a stalled group keeps reading the same mapping.
  val holdRatRequest = t1Valid.asUInt.orR && !fired
  for (lane <- 0 until width) {
    for (port <- 0 until 2) {
      rename.io.intReadPorts(lane)(port).addr := decode.io.out(lane).logicalSrc(port)
      rename.io.intReadPorts(lane)(port).hold := t1Valid(lane) && holdRatRequest
    }
    for (port <- 0 until 3) {
      rename.io.fpReadPorts(lane)(port).addr := decode.io.out(lane).logicalSrc(port)
      rename.io.fpReadPorts(lane)(port).hold := t1Valid(lane) && holdRatRequest
    }
  }

  // ---- rename external ports ----
  rename.io.redirect    := io.redirect
  rename.io.rabCommits  := io.rabCommits
  rename.io.snpt        := io.snpt
  rename.io.snptLastEnq := io.snptLastEnq
  rename.io.snptIsFull  := io.snptIsFull

  // ---- Rename -> renamePipeDispatch -> Dispatch ----
  for (lane <- 0 until width) {
    renamePipe.io.in(lane).valid := rename.io.out(lane).valid
    renamePipe.io.in(lane).bits  := rename.io.out(lane).bits
    rename.io.out(lane).ready    := renamePipe.io.in(lane).ready

    dispatch.io.fromRename(lane).valid := renamePipe.io.out(lane).valid
    dispatch.io.fromRename(lane).bits  := renamePipe.io.out(lane).bits
    renamePipe.io.out(lane).ready      := dispatch.io.fromRename(lane).ready
  }
  renamePipe.io.flush      := io.redirect.valid
  renamePipe.io.outAllFire := dispatch.io.toRenameAllFire

  // IQ 预选使用同拍的 T1 组（融合补丁后），比 fromRename 早一拍。
  for (lane <- 0 until width) {
    dispatch.io.renameIn(lane).valid := visibleValid(lane) && !io.redirect.valid
    dispatch.io.renameIn(lane).bits  := renameBits(lane)
  }

  dispatch.io.redirect      := io.redirect
  dispatch.io.IQValidNumVec := io.IQValidNumVec
  dispatch.io.wbPregsInt    := io.wbPregsInt
  dispatch.io.wbPregsFp     := io.wbPregsFp
  dispatch.io.wakeUpAll     := io.wakeUpAll
  dispatch.io.s0Cancel      := io.s0Cancel
  dispatch.io.ldCancel      := io.ldCancel
  dispatch.io.fromMem       := io.fromMem
  dispatch.io.singleStep    := io.singleStep

  for (port <- 0 until AnsDispatchParams.IQEnqSum) {
    io.toIssueQueues(port).valid          := dispatch.io.toIssueQueues(port).valid
    io.toIssueQueues(port).bits           := dispatch.io.toIssueQueues(port).bits
    dispatch.io.toIssueQueues(port).ready := io.toIssueQueues(port).ready
  }

  io.enqRob.needAlloc                     := dispatch.io.enqRob.needAlloc
  io.enqRob.req                           := dispatch.io.enqRob.req
  dispatch.io.enqRob.canAccept            := io.enqRob.canAccept
  dispatch.io.enqRob.canAcceptForDispatch := io.enqRob.canAcceptForDispatch
  dispatch.io.enqRob.isEmpty              := io.enqRob.isEmpty
  dispatch.io.enqRob.resp                 := io.enqRob.resp

  io.lsqEnq.needAlloc          := dispatch.io.lsqEnq.needAlloc
  io.lsqEnq.req                := dispatch.io.lsqEnq.req
  io.lsqEnq.iqAccept           := dispatch.io.lsqEnq.iqAccept
  dispatch.io.lsqEnq.canAccept := io.lsqEnq.canAccept
  dispatch.io.lsqEnq.resp      := io.lsqEnq.resp

  io.stallReason := rename.io.stallReason

  // Report the oldest exceptional instruction when it actually leaves decode.
  val exceptionFire   = VecInit((0 until width).map { lane =>
    rename.io.in(lane).fire && renameBits(lane).hasException
  })
  val oldestException = PriorityEncoder(exceptionFire)
  io.trapInstInfo.valid := exceptionFire.asUInt.orR
  io.trapInstInfo.bits  := 0.U.asTypeOf(new TrapInstInfo(params))
  when(io.trapInstInfo.valid) {
    io.trapInstInfo.bits.fromDecodedInst(renameBits(oldestException))
  }

  io.debug.t1Valid       := t1Valid
  io.debug.renameInValid := VecInit(rename.io.in.map(_.valid))
  io.debug.renameInReady := VecInit(rename.io.in.map(_.ready))
  io.debug.renameIn      := renameBits
  io.debug.intRat        := rename.io.intReadPorts
  io.debug.fpRat         := rename.io.fpReadPorts
  io.debug.fusionInfo    := fusion.io.info
  io.debug.dispatch      := dispatch.io.debug
}
