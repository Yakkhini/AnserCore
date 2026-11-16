package difftest

import chisel3._
import chisel3.reflect.DataMirror

// chisel3._ also brings in chisel3.probe. Use the root package.
import _root_.probe.ProbeModule

// Wrapper around ProbeModule. CPU code still calls DifftestModule(new Commit).
// Generated DPI calls difftest_probe_push. It does not call a shared gateway.
object DifftestModule {
  private val kind = "difftest"
  private val sink = "difftest_probe_push"
  private val extPrefix = "DiffExt"

  def apply[T <: DifftestBundle](
      gen: T,
      dontCare: Boolean = false,
      delay: Int = 0
  ): T = {
    ProbeModule(gen, kind, sink, extPrefix, dontCare, delay)
  }

  def collect(cpu: String, outDir: String = "build/generated-src"): Unit = {
    ProbeModule.collect(cpu, outDir)
  }

  def top[T <: Module with HasDiffTestInterfaces](cpuGen: => T): SimTop[T] =
    new SimTop(() => cpuGen)
}

// Mix into the CPU module that owns the clock the probes should sample.
trait HasDiffTestInterfaces {
  this: Module =>
}

// Wraps the CPU. collect() runs after the CPU is built, so every probe
// registered during that build is in the generated DPI file.
class SimTop[T <: Module with HasDiffTestInterfaces](gen: () => T)
    extends Module {
  override def desiredName: String = "SimTop"

  val cpu = Module(gen())
  DifftestModule.collect("dut")

  DataMirror.modulePorts(cpu).foreach { case (name, data) =>
    if (name != "clock" && name != "reset") {
      val port = IO(chiselTypeOf(data)).suggestName(name)
      port <> data
    }
  }
}
