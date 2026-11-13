/* ROB 占位模块。
 *
 * Backend 内部使用：向 Rename 提供空闲的提交/快照/redirect 信号，并始终接收 Dispatch 入队。
 * 真实 ROB 落地后直接替换该模块，AnsBackend 的连接不变。
 */
package ansercore.backend.rob

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.dispatch.AnsRobEnqIO
import ansercore.backend.rename.{AnsRabCommitIO, AnsRenameRedirect, AnsSnapshotPort}

class AnsRobStubIO(val params: BundleParams = BundleParams()) extends Bundle {
  val redirect    = Output(Valid(new AnsRenameRedirect(params)))
  val rabCommits  = Output(new AnsRabCommitIO(params))
  val snpt        = Output(new AnsSnapshotPort(params))
  val snptLastEnq = Output(Valid(UInt(params.robIndexWidth.W)))
  val snptIsFull  = Output(Bool())

  /** ROB 视角的 Dispatch 入队请求；canAccept/resp 由 ROB 返回。 */
  val enq = new AnsRobEnqIO(params)
}

class AnsRobStub(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsRobStubIO(params))

  io.redirect    := 0.U.asTypeOf(Valid(new AnsRenameRedirect(params)))
  io.rabCommits  := 0.U.asTypeOf(new AnsRabCommitIO(params))
  io.snpt        := 0.U.asTypeOf(new AnsSnapshotPort(params))
  io.snptLastEnq := 0.U.asTypeOf(Valid(UInt(params.robIndexWidth.W)))
  io.snptIsFull  := false.B

  io.enq.canAccept            := true.B
  io.enq.canAcceptForDispatch := true.B
  io.enq.isEmpty              := true.B
  io.enq.resp                 := 0.U.asTypeOf(io.enq.resp)

  // Keep Dispatch's requests visible until the real ROB consumes them.
  dontTouch(io.enq.needAlloc)
  dontTouch(io.enq.req)
}
