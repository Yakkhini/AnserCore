/* BypassNetwork：发射操作数的最终选择。
 *
 * DataPath data1 只负责把 PRF/RegCache 数据读出来并填进 ExuInput；本模块按
 * `dataSources` 选择 reg / regcache / forward / bypass / imm / zero。
 * forward 是生产者的组合输出，bypass 是打 1 拍的副本，选择依据是条目记录的
 * `exuSources`（生产者 exuIdx + 1）。BN 与 EXU 之间的 `AnsExuInputPipe` 切断
 * forward 的组合环。
 */
package ansercore.backend.datapath

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.issue.AnsIssueParams

class AnsBypassNetworkIO(val params: BundleParams = BundleParams()) extends Bundle {
  val fromDataPath =
    Vec(AnsIssueParams.nExu, Flipped(Decoupled(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc)))))

  /** RegCache 读数据，按 exu×src 展平。 */
  val rcData = Input(Vec(AnsIssueParams.nExu, Vec(params.numSrc, UInt(params.xlen.W))))

  /** EXU 组合输出：forward / bypass 的来源。 */
  val fromExu = Vec(AnsIssueParams.nExu, Flipped(Valid(new ExuOutput(params, ExecutionUnitParams()))))

  val toExu = Vec(AnsIssueParams.nExu, Decoupled(new ExuInput(params, ExecutionUnitParams(numRegSrc = params.numSrc))))
}

class AnsBypassNetwork(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsBypassNetworkIO(params))

  val nExu   = AnsIssueParams.nExu
  val numSrc = params.numSrc

  private val forwardData  = VecInit(io.fromExu.map(_.bits.data(0)))
  private val forwardValid = VecInit(io.fromExu.map(_.valid))
  private val bypass1Data  = RegNext(forwardData)
  private val bypass1Valid = RegNext(forwardValid)

  for (e <- 0 until nExu) {
    val in  = io.fromDataPath(e)
    val out = io.toExu(e)

    out.valid := in.valid
    out.bits  := in.bits
    in.ready  := out.ready

    for (s <- 0 until numSrc) {
      val dataSource = in.bits.dataSources(s)
      val producerOH = VecInit((0 until nExu).map(p => in.bits.exuSources(s) === (p + 1).U))

      def selectSource(data: Vec[UInt], valid: Vec[Bool]): UInt =
        Mux1H(producerOH, (0 until nExu).map(p => Mux(valid(p), data(p), 0.U(params.xlen.W))))

      val forward = selectSource(forwardData, forwardValid)
      val bypass1 = selectSource(bypass1Data, bypass1Valid)
      val imm     = ImmPacking.extend(in.bits.imm, in.bits.immType, params.xlen)

      out.bits.src(s) := Mux1H(
        Seq(
          DataSource.readRegOH(dataSource)    -> in.bits.src(s),
          DataSource.readRegCache(dataSource) -> io.rcData(e)(s),
          DataSource.readForward(dataSource)  -> forward,
          DataSource.readBypass(dataSource)   -> bypass1,
          DataSource.readImm(dataSource)      -> imm,
          DataSource.readZero(dataSource)     -> 0.U(params.xlen.W),
        )
      )
    }
  }
}
