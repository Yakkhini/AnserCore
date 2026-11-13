/* LsqEnqCtrl：LQ/SQ shadow 分配（设计文档 §5.4）。
 *
 * Dispatch 是顺序执行的最后一步，这里按程序顺序给访存指令分配 lqIdx/sqIdx，
 * 并维护 LQ/SQ 空闲计数。指针/计数是 MemBlock 真实 LSQ 的本地投影：
 *   - 同拍返回 lqIdx/sqIdx，避免跨越到 LSQ 的组合路径；
 *   - 重定向后由 MemBlock 回传的取消计数恢复指针（t1/t2/t3 链）；
 *   - 送往 LSQ 的 enq 请求打一拍。
 * 标量配置下 load 占 1 个 LQ 条目，store 占 1 个 SQ 条目，AMO 两者各占 1 个。
 */
package ansercore.backend.dispatch

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.AnsRenameRedirect

class AnsLsqEnqCtrlIO(val params: BundleParams = BundleParams()) extends Bundle {
  val redirect = Input(Valid(new AnsRenameRedirect(params)))
  val fromMem  = Input(new AnsMemToDispatchBundle(params))

  /** Dispatch 侧接口：Dispatch 驱动 needAlloc/req/iqAccept，读回 canAccept/resp。 */
  val enq = new AnsLsqEnqIO(params)

  /** 送往 LSQ 的已寄存请求。 */
  val toLsq = Output(new AnsLsqEnqOut(params))

  val lqFreeCount = Output(UInt(log2Up(AnsDispatchParams.lqEntries + 1).W))
  val sqFreeCount = Output(UInt(log2Up(AnsDispatchParams.sqEntries + 1).W))
}

class AnsLsqEnqCtrl(val params: BundleParams = BundleParams()) extends Module {
  val width      = params.renameWidth
  val ptrWidth   = params.queueIndexWidth
  val allocWidth = log2Up(width + 1)

  val io = IO(new AnsLsqEnqCtrlIO(params))

  val lqPtr     = RegInit(0.U(ptrWidth.W))
  val sqPtr     = RegInit(0.U(ptrWidth.W))
  val lqCounter = RegInit(AnsDispatchParams.lqEntries.U(log2Up(AnsDispatchParams.lqEntries + 1).W))
  val sqCounter = RegInit(AnsDispatchParams.sqEntries.U(log2Up(AnsDispatchParams.sqEntries + 1).W))
  val canAccept = RegInit(false.B)

  val needLQ = VecInit(io.enq.req.map { r =>
    r.valid && (FuType.isLoad(r.bits.fuType) || FuType.isAtomic(r.bits.fuType))
  })
  val needSQ = VecInit(io.enq.req.map { r =>
    r.valid && (FuType.isStore(r.bits.fuType) || FuType.isAtomic(r.bits.fuType))
  })

  val lqCum = Wire(Vec(width + 1, UInt(allocWidth.W)))
  val sqCum = Wire(Vec(width + 1, UInt(allocWidth.W)))
  lqCum(0) := 0.U
  sqCum(0) := 0.U
  for (i <- 0 until width) {
    lqCum(i + 1) := lqCum(i) + needLQ(i).asUInt
    sqCum(i + 1) := sqCum(i) + needSQ(i).asUInt
  }

  // 只统计被 IQ 接收的 lane：遇到第一个未接收的 lane 就停止累加。
  val blockVec   = io.enq.iqAccept.map(!_) :+ true.B
  val lqAllocNum = PriorityMux(blockVec, lqCum)
  val sqAllocNum = PriorityMux(blockVec, sqCum)

  io.lqFreeCount := lqCounter
  io.sqFreeCount := sqCounter

  val t1Redirect = RegNext(io.redirect.valid, false.B)
  val t2Redirect = RegNext(t1Redirect, false.B)
  val t2Update   = t2Redirect && !VecInit(io.enq.needAlloc.map(_.orR)).asUInt.orR
  val t3Update   = RegNext(t2Update, false.B)
  val t3LqCancel = RegEnable(io.fromMem.lqCancelCnt, t2Update)
  val t3SqCancel = RegEnable(io.fromMem.sqCancelCnt, t2Update)

  when(t3Update) {
    lqPtr     := lqPtr - t3LqCancel
    lqCounter := lqCounter + io.fromMem.lcommit + t3LqCancel
    sqPtr     := sqPtr - t3SqCancel
    sqCounter := sqCounter + io.fromMem.scommit + t3SqCancel
  }.elsewhen(!io.redirect.valid && canAccept) {
    lqPtr     := lqPtr + lqAllocNum
    lqCounter := lqCounter + io.fromMem.lcommit - lqAllocNum
    sqPtr     := sqPtr + sqAllocNum
    sqCounter := sqCounter + io.fromMem.scommit - sqAllocNum
  }.otherwise {
    lqCounter := lqCounter + io.fromMem.lcommit
    sqCounter := sqCounter + io.fromMem.scommit
  }

  val lqCanAccept = lqCounter >= lqAllocNum
  val sqCanAccept = sqCounter >= sqAllocNum
  canAccept        := lqCanAccept && sqCanAccept && !t2Update
  io.enq.canAccept := canAccept

  for ((resp, i) <- io.enq.resp.zipWithIndex) {
    resp.lqIdx := lqPtr + lqCum(i)
    resp.sqIdx := sqPtr + sqCum(i)
  }

  // ---- 送往 LSQ 的寄存器化请求 ----
  io.toLsq.needAlloc := RegNext(io.enq.needAlloc)
  io.toLsq.iqAccept  := RegNext(io.enq.iqAccept)
  for (i <- 0 until width) {
    val doEnq = io.enq.req(i).valid && !io.redirect.valid && canAccept
    val bits  = Wire(new DynInst(params))
    bits                  := io.enq.req(i).bits
    bits.lqIdx            := io.enq.resp(i).lqIdx
    bits.sqIdx            := io.enq.resp(i).sqIdx
    io.toLsq.req(i).valid := RegNext(doEnq, false.B)
    io.toLsq.req(i).bits  := RegEnable(bits, doEnq)
  }
}
