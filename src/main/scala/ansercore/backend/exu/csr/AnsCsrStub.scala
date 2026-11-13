/* CSR 占位模块。
 *
 * Backend 内部使用：向 Decode 提供全 0 的状态摘要，接收 trap 上报。
 * 真实 CSR 落地后直接替换该模块，AnsBackend 的连接不变。
 */
package ansercore.backend.exu.csr

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles.{BundleParams, TrapInstInfo}

class AnsCsrStubIO(val params: BundleParams = BundleParams()) extends Bundle {
  val decodeState   = Output(new AnsCsrDecodeState)
  val singleStep    = Output(Bool())
  val disableFusion = Output(Bool())
  val trapInstInfo  = Input(Valid(new TrapInstInfo(params)))
}

class AnsCsrStub(val params: BundleParams = BundleParams()) extends Module {
  val io = IO(new AnsCsrStubIO(params))

  io.decodeState   := 0.U.asTypeOf(new AnsCsrDecodeState)
  io.singleStep    := false.B
  io.disableFusion := false.B

  // Keep the report visible in waveforms until the real CSR consumes it.
  dontTouch(io.trapInstInfo)
}
