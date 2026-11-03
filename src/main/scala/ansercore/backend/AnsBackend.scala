/* AnserCore backend top level，对应设计文档 §4.1 的 Backend 子图。
 *
 * Backend 是后端的组合根：CtrlBlock（Decode -> T1 -> Rename -> Dispatch）以及未来的
 * CSR / ROB / LSQ / IQ / Scheduler / DataPath / MemBlock 都在其内部连接。对外的端口只有
 * 前端接口（指令输入、stall/redirect）和未来的访存接口；ROB 提交、CSR 状态、调度反馈等
 * 都属于后端内部总线，不再出现在 AnsBackendIO 中。
 *
 * 当前 CtrlBlock（内含 Dispatch）已存在；CSR/ROB 用 `AnsCsrStub`/`AnsRobStub` 占位，
 * IQ/LSQ/Scheduler 反馈暂用内部常量驱动。
 */
package ansercore.backend

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.dispatch._
import ansercore.backend.exu.csr.AnsCsrStub
import ansercore.backend.rename._
import ansercore.backend.rob.AnsRobStub

/** Backend 调试镜像；只用于 bring-up，不参与功能握手。 */
class AnsBackendDebugIO(override val params: BundleParams = BundleParams()) extends AnsCtrlBlockDebugIO(params) {

  /** Internal CSR trap bus; mirrored for bring-up. */
  val trapInstInfo = Output(Valid(new TrapInstInfo(params)))

  /** Dispatch -> IQ 的调试镜像；真实 IQ 实现后由 IQ 消费 ready。 */
  val iqEnq = Output(Vec(AnsDispatchParams.IQEnqSum, Valid(new DynInst(params))))
}

/** Backend 对外端口：前端接口（访存接口待 LSU/数据缓存实现后再加）。 */
class AnsBackendIO(val params: BundleParams = BundleParams()) extends Bundle {
  // Frontend -> backend.
  val in = Vec(params.renameWidth, Flipped(Decoupled(new StaticInst(params))))

  // Backend -> frontend.
  val stallReason = Output(new AnsRenameStallReason)
  // TODO: branch/ROB 落地后在 Backend 输出 frontendRedirect（target PC / FTQ 指针）。

  val debug = Output(new AnsBackendDebugIO(params))
}

class AnsBackend(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsBackendIO(params))

  val ctrlBlock = Module(new AnsCtrlBlock(params))

  // ---- frontend interface ----
  for (lane <- 0 until params.renameWidth) {
    ctrlBlock.io.in(lane).valid := io.in(lane).valid
    ctrlBlock.io.in(lane).bits  := io.in(lane).bits
    io.in(lane).ready           := ctrlBlock.io.in(lane).ready
  }
  io.stallReason := ctrlBlock.io.stallReason

  // ---- internal backend modules ----
  val csr = Module(new AnsCsrStub(params))
  val rob = Module(new AnsRobStub(params))

  ctrlBlock.io.fromCsr       := csr.io.decodeState
  ctrlBlock.io.disableFusion := csr.io.disableFusion
  ctrlBlock.io.singleStep    := csr.io.singleStep
  csr.io.trapInstInfo        := ctrlBlock.io.trapInstInfo

  ctrlBlock.io.redirect    := rob.io.redirect
  ctrlBlock.io.rabCommits  := rob.io.rabCommits
  ctrlBlock.io.snpt        := rob.io.snpt
  ctrlBlock.io.snptLastEnq := rob.io.snptLastEnq
  ctrlBlock.io.snptIsFull  := rob.io.snptIsFull

  rob.io.enq.needAlloc                     := ctrlBlock.io.enqRob.needAlloc
  rob.io.enq.req                           := ctrlBlock.io.enqRob.req
  ctrlBlock.io.enqRob.canAccept            := rob.io.enq.canAccept
  ctrlBlock.io.enqRob.canAcceptForDispatch := rob.io.enq.canAcceptForDispatch
  ctrlBlock.io.enqRob.isEmpty              := rob.io.enq.isEmpty
  ctrlBlock.io.enqRob.resp                 := rob.io.enq.resp

  // TODO: 用真实 IQ 替换：当前永远可接收，Dispatch 输出悬空。
  for (port <- 0 until AnsDispatchParams.IQEnqSum) {
    ctrlBlock.io.toIssueQueues(port).ready := true.B
  }

  // TODO: 用真实 LSQ 替换；当前永远可入队、返回全 0 lq/sq 索引。
  ctrlBlock.io.lsqEnq.canAccept := true.B
  ctrlBlock.io.lsqEnq.resp      := 0.U.asTypeOf(ctrlBlock.io.lsqEnq.resp)

  // TODO: 用 Scheduler / DataPath / MemBlock 的真实反馈替换。
  ctrlBlock.io.IQValidNumVec := 0.U.asTypeOf(ctrlBlock.io.IQValidNumVec)
  ctrlBlock.io.wbPregsInt    := 0.U.asTypeOf(ctrlBlock.io.wbPregsInt)
  ctrlBlock.io.wbPregsFp     := 0.U.asTypeOf(ctrlBlock.io.wbPregsFp)
  ctrlBlock.io.wakeUpAll     := 0.U.asTypeOf(ctrlBlock.io.wakeUpAll)
  ctrlBlock.io.s0Cancel      := 0.U.asTypeOf(ctrlBlock.io.s0Cancel)
  ctrlBlock.io.ldCancel      := 0.U.asTypeOf(ctrlBlock.io.ldCancel)
  ctrlBlock.io.fromMem       := 0.U.asTypeOf(ctrlBlock.io.fromMem)

  // ---- debug mirrors ----
  io.debug.t1Valid       := ctrlBlock.io.debug.t1Valid
  io.debug.renameInValid := ctrlBlock.io.debug.renameInValid
  io.debug.renameInReady := ctrlBlock.io.debug.renameInReady
  io.debug.renameIn      := ctrlBlock.io.debug.renameIn
  io.debug.intRat        := ctrlBlock.io.debug.intRat
  io.debug.fpRat         := ctrlBlock.io.debug.fpRat
  io.debug.fusionInfo    := ctrlBlock.io.debug.fusionInfo
  io.debug.dispatch      := ctrlBlock.io.debug.dispatch
  io.debug.trapInstInfo  := ctrlBlock.io.trapInstInfo

  for (port <- 0 until AnsDispatchParams.IQEnqSum) {
    io.debug.iqEnq(port).valid := ctrlBlock.io.toIssueQueues(port).valid
    io.debug.iqEnq(port).bits  := ctrlBlock.io.toIssueQueues(port).bits
  }
}
