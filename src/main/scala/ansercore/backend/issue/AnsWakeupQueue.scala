/* 推测唤醒队列。
 *
 * 指令从 IQ 出队时按固定延迟进入对应深度的移位管线，延迟到达后广播给
 * 其它 IQ。遇到 redirect / load cancel / data0-data1 block 时删除对应唤醒。
 *
 * 注意：Delay-0 管线是组合直通；Backend 在广播前统一打一拍，避免 IQ
 * 内部形成组合环，同时仍比真正的 WB 唤醒早。
 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.AnsRenameRedirect

class AnsWakeupFlush(val params: BundleParams = BundleParams()) extends Bundle {
  val redirect  = Valid(new AnsRenameRedirect(params))
  val ldCancel  = Vec(params.loadPipelineWidth, new LoadCancel)
  val data0Fail = Bool()
  val data1Fail = Bool()
}

class AnsWakeupPipe(val depth: Int, val params: BundleParams = BundleParams()) extends Module {
  require(depth >= 0)

  val io = IO(new Bundle {
    val flush = Input(new AnsWakeupFlush(params))
    val enq   = Input(Valid(new AnsIqWakeUp(params)))
    val deq   = Output(Valid(new AnsIqWakeUp(params)))
  })

  def flushFunc(bits: AnsIqWakeUp, stage: Int): Bool = {
    val redirectFlush = AnsIssueUtils.needFlush(bits.robIdx, io.flush.redirect)
    val loadFlush     = LoadShouldCancel(bits.loadDependency, io.flush.ldCancel)
    val stageFlush    =
      (if (stage == 1) io.flush.data0Fail else false.B) ||
        (if (stage == 2) io.flush.data1Fail else false.B)
    redirectFlush || loadFlush || stageFlush
  }

  def shiftDependency(bits: AnsIqWakeUp): AnsIqWakeUp = {
    val next = WireInit(bits)
    next.loadDependency := VecInit(bits.loadDependency.map(x => (x << 1)(params.loadDependencyWidth - 1, 0)))
    next
  }

  if (depth == 0) {
    io.deq := io.enq
  } else {
    val valids = io.enq.valid +: Seq.fill(depth)(RegInit(false.B))
    val bits   = io.enq.bits +: Seq.fill(depth)(Reg(new AnsIqWakeUp(params)))

    for (i <- 0 until depth) {
      valids(i + 1) := (if (i == 0) valids(i) else valids(i) && !flushFunc(bits(i), i))
      when(valids(i)) {
        bits(i + 1) := (if (i == 0) bits(i) else shiftDependency(bits(i)))
      }
    }

    io.deq.valid := valids.last
    io.deq.bits  := bits.last
  }
}

class AnsMultiWakeupQueue(
  val latencySet: Set[Int],
  val params:     BundleParams = BundleParams(),
) extends Module {
  require(latencySet.nonEmpty && latencySet.min >= 0)
  require(latencySet.max <= params.maxLatency, "wakeup latency exceeds the latency field capacity")

  val io = IO(new Bundle {
    val flush = Input(new AnsWakeupFlush(params))
    val enq   = Flipped(Valid(new Bundle {
      val uop = new AnsIqWakeUp(params)
      val lat = UInt(log2Ceil(params.maxLatency + 1).W)
    }))
    val deq   = Output(Valid(new AnsIqWakeUp(params)))
  })

  val sortedLat = latencySet.toSeq.sorted
  val pipes     = sortedLat.map(d => Module(new AnsWakeupPipe(d, params)))

  pipes.zip(sortedLat).foreach { case (pipe, lat) =>
    pipe.io.flush     := io.flush
    pipe.io.enq.valid := io.enq.valid && io.enq.bits.lat === lat.U
    pipe.io.enq.bits  := io.enq.bits.uop
  }

  val pipeValid = VecInit(pipes.map(_.io.deq.valid))
  val pipeBits  = VecInit(pipes.map(_.io.deq.bits))

  io.deq.valid := pipeValid.asUInt.orR
  io.deq.bits  := Mux1H(pipeValid, pipeBits)

  when(PopCount(pipeValid) > 1.U) {
    assert(false.B, "multiple wakeup pipes fire in the same cycle")
  }
}
