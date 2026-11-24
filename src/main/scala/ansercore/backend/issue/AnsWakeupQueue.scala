/* 推测唤醒队列。
 *
 * 指令从 IQ 出队时按固定延迟进入对应深度的移位管线，延迟到达后广播给
 * 其它 IQ。遇到 redirect / load cancel / data0-data1 block 时删除对应唤醒。
 *
 * 各 latency 管线合流后仅有一个输出寄存级；Backend 组合广播。
 * latency0 与生产者 data0 同拍，由接收方过滤 data0Cancel。
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
      valids(i + 1) := valids(i) && (if (i == 0) !AnsIssueUtils.needFlush(bits(i).robIdx, io.flush.redirect)
                                     else !flushFunc(bits(i), i))
      when(valids(i)) {
        bits(i + 1) := (if (i == 0) bits(i) else shiftDependency(bits(i)))
      }
    }

    // 输出级也要做一次 flush 检查。XiangShan MultiWakeupQueue 对每个 pipe 的
    // deq 再执行 flushFunc(deq.bits, lat)，因此 depth=1 的 data0Fail、depth=2 的
    // data1Fail 不会漏到 IQ/Dispatch。depth>=3 的 data0/data1 已在 stage1/2 检查。
    io.deq.valid := valids.last && !flushFunc(bits.last, depth)
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
  val pipeBits  = VecInit(pipes.zip(sortedLat).map { case (pipe, lat) =>
    val bits = WireInit(pipe.io.deq.bits)
    if (lat > 0) bits.loadDependency := AnsIssueUtils.shiftLoadDependency(pipe.io.deq.bits.loadDependency)
    bits
  })

  val merged   = Mux1H(pipeValid, pipeBits)
  val outValid = RegInit(false.B)
  val outBits  = Reg(new AnsIqWakeUp(params))
  // latency0 的依赖已在 Entry 发射时为 data0 移位，不再次移动。
  outValid     := pipeValid.asUInt.orR && !AnsIssueUtils.needFlush(merged.robIdx, io.flush.redirect)
  when(pipeValid.asUInt.orR)(outBits := merged)
  io.deq.valid := outValid && !AnsIssueUtils.needFlush(outBits.robIdx, io.flush.redirect)
  io.deq.bits  := outBits

  when(PopCount(pipeValid) > 1.U) {
    assert(false.B, "multiple wakeup pipes fire in the same cycle")
  }
}
