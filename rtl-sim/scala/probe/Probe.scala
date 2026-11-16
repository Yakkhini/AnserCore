package probe

import chisel3._
import chisel3.util._

import scala.collection.mutable

// Turns one ProbeBundle into a DPI black box. The wrapper chooses kind, sink,
// and the Verilog module prefix. This object does not know about difftest.
object ProbeModule {
  private val specs = mutable.LinkedHashMap.empty[String, ProbeSpec]

  def apply[T <: ProbeBundle](
      gen: T,
      kind: String,
      sink: String,
      extPrefix: String,
      dontCare: Boolean = false,
      delay: Int = 0
  ): T = {
    require(kind.nonEmpty && sink.nonEmpty && extPrefix.nonEmpty)
    val probe = Wire(gen)
    probe.suggestName(gen.desiredCppName)
    if (dontCare) {
      probe := DontCare
      probe.elements.get("valid").foreach {
        case valid: Bool => valid := false.B
        case _ =>
      }
    }

    var delayed: T = probe
    for (_ <- 0 until delay) {
      delayed = RegNext(delayed)
    }

    val hw = ProbeDpi.flatten(delayed)
    require(
      hw.nonEmpty && hw.length <= ProbeDpi.maxWords,
      s"${gen.desiredCppName} has ${hw.length} words; limit is ${ProbeDpi.maxWords}"
    )
    val spec = ProbeSpec(
      kind,
      sink,
      extPrefix,
      gen.desiredModuleName,
      gen.desiredCppName,
      hw.map { case (name, data) => ProbeField(name, data.getWidth) }
    )
    val key = s"$kind.${spec.moduleName}"
    specs.get(key).foreach { old =>
      require(old == spec, s"$key changed")
    }
    specs += key -> spec
    ProbeExt.attach(spec, hw)
    probe
  }

  // Writes probes.cpp and one Verilog black box per probe type.
  def collect(cpu: String, outDir: String = "build/generated-src"): Unit = {
    ProbeFiles.write(cpu, specs.values.toSeq, outDir)
  }
}
