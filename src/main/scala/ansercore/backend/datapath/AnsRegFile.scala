/* 可配置的物理寄存器堆：地址 bank × 数据 split。
 *
 * 地址低位选择 bank，高位选择 bank 内 entry；每个 bank 内再按数据位切成
 * `numDataSplits` 个独立 array。读是异步读，由 `AnsDataPath` 在外部
 * `RegNext` 打一拍，保持与旧扁平 PRF 相同的 data0 → data1 时序；写是同步写。
 *
 * 默认 4 个地址 bank × 4 个 16-bit 数据 split，可通过 `AnsPrfConfig` 调整。
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._

/** 一个 banked PRF 的硬件形状。
  *
  * `numAddrBanks` 必须是 1/2/4/8；`numDataSplits` 必须是 1/2/4/8 且整除 `dataWidth`。
  * `numEntries` 不要求整除 bank 数，最后一个 bank 允许有未使用的条目。
  */
final case class AnsRegFileParams(
  numEntries:    Int,
  dataWidth:     Int,
  addrWidth:     Int,
  numRead:       Int,
  numWrite:      Int,
  numAddrBanks:  Int     = 4,
  numDataSplits: Int     = 4,
  hasZero:       Boolean = false,
) {
  require(numEntries > 0, "numEntries must be positive")
  require(dataWidth > 0, "dataWidth must be positive")
  require(addrWidth > 0, "addrWidth must be positive")
  require(numRead >= 0, "numRead must be non-negative")
  require(numWrite > 0, "numWrite must be positive")
  require(Seq(1, 2, 4, 8).contains(numAddrBanks), "numAddrBanks must be 1/2/4/8")
  require(Seq(1, 2, 4, 8).contains(numDataSplits), "numDataSplits must be 1/2/4/8")
  require(dataWidth % numDataSplits == 0, "dataWidth must be divisible by numDataSplits")
  require(numEntries <= (1 << addrWidth), "numEntries exceeds addrWidth")

  def bankBits: Int = log2Ceil(numAddrBanks)

  def entriesPerBank: Int = (numEntries + numAddrBanks - 1) / numAddrBanks

  def sliceWidth: Int = dataWidth / numDataSplits
}

/** PRF 分 bank 的默认配置；作为 `AnsDataPath` 的可选构造参数。 */
final case class AnsPrfConfig(
  intAddrBanks:  Int = 4,
  intDataSplits: Int = 4,
  fpAddrBanks:   Int = 4,
  fpDataSplits:  Int = 4,
)

class AnsRegFileReadPort(val p: AnsRegFileParams) extends Bundle {
  val addr = Input(UInt(p.addrWidth.W))
  val data = Output(UInt(p.dataWidth.W))
}

class AnsRegFileWritePort(val p: AnsRegFileParams) extends Bundle {
  val wen  = Input(Bool())
  val addr = Input(UInt(p.addrWidth.W))
  val data = Input(UInt(p.dataWidth.W))
}

class AnsRegFileIO(val p: AnsRegFileParams) extends Bundle {
  val read  = Vec(p.numRead, new AnsRegFileReadPort(p))
  val write = Vec(p.numWrite, new AnsRegFileWritePort(p))
}

class AnsRegFile(val p: AnsRegFileParams) extends Module {
  val io = IO(new AnsRegFileIO(p))

  private val numBanks       = p.numAddrBanks
  private val numSplits      = p.numDataSplits
  private val bankBits       = p.bankBits
  private val entriesPerBank = p.entriesPerBank
  private val sliceWidth     = p.sliceWidth
  private val entryBits      = math.max(1, log2Ceil(entriesPerBank))

  // mem(bank)(split)(entry)
  private val mem: Seq[Seq[Vec[UInt]]] = Seq.tabulate(numBanks) { _ =>
    Seq.tabulate(numSplits) { _ =>
      Reg(Vec(entriesPerBank, UInt(sliceWidth.W)))
    }
  }

  private def bankOf(addr: UInt): UInt =
    if (numBanks == 1) 0.U else addr(bankBits - 1, 0)

  private def validAddr(addr: UInt): Bool = addr < p.numEntries.U

  private def entryOf(addr: UInt): UInt = {
    val raw       = addr >> bankBits
    val truncated = if (raw.getWidth > entryBits) raw(entryBits - 1, 0) else raw
    Mux(validAddr(addr), truncated, 0.U)
  }

  private def zeroAddr(addr: UInt): Bool =
    if (p.hasZero) addr === 0.U else false.B

  // ---- 写：同步写；同一拍所有 bank 中只有 bankOf(addr) 命中 ----
  for (w <- 0 until p.numWrite) {
    val addr  = io.write(w).addr
    val bank  = bankOf(addr)
    val entry = entryOf(addr)
    val wen   = io.write(w).wen && !zeroAddr(addr)
    for (b <- 0 until numBanks) {
      when(wen && bank === b.U) {
        for (s <- 0 until numSplits) {
          mem(b)(s)(entry) := io.write(w).data((s + 1) * sliceWidth - 1, s * sliceWidth)
        }
      }
    }
    assert(!io.write(w).wen || validAddr(addr), "AnsRegFile write address out of range")
  }

  // renaming / WbDataPath 应保证同一拍没有两个写口写同一地址；这里兜底。
  // hasZero 时地址 0 的写本来就是 no-op，不触发该断言。
  for (i <- 0 until p.numWrite; j <- i + 1 until p.numWrite) {
    val sameAddr = io.write(i).wen && io.write(j).wen &&
      io.write(i).addr === io.write(j).addr &&
      !zeroAddr(io.write(i).addr)
    assert(!sameAddr, "AnsRegFile write ports write the same address")
  }

  // ---- 读：异步读，由 AnsDataPath 外部 RegNext 打拍 ----
  for (r <- 0 until p.numRead) {
    val addr  = io.read(r).addr
    val bank  = bankOf(addr)
    val entry = entryOf(addr)
    val slices = (0 until numSplits).map { s =>
      if (numBanks == 1) {
        mem(0)(s)(entry)
      } else {
        Mux1H(
          (0 until numBanks).map(b => bank === b.U),
          (0 until numBanks).map(b => mem(b)(s)(entry)),
        )
      }
    }
    val raw = if (numSplits == 1) slices.head else Cat(slices.reverse)
    io.read(r).data := Mux(validAddr(addr) && !zeroAddr(addr), raw, 0.U(p.dataWidth.W))
  }
}
