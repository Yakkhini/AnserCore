package probe

import chisel3._

// One flattened field, in the order the DPI call passes it.
case class ProbeField(name: String, width: Int)

// kind selects the generated function prefix. sink is the C function that
// owns the queue. extPrefix is the Verilog black-box name prefix.
case class ProbeSpec(
    kind: String,
    sink: String,
    extPrefix: String,
    moduleName: String,
    cppName: String,
    fields: Seq[ProbeField]
)

object ProbeDpi {
  // Same limit as kProbeMaxWords in csrc/service/probe/probe.h.
  val maxWords = 160

  def flatten(data: Data): Seq[(String, Data)] = {
    def walk(name: String, node: Data): Seq[(String, Data)] = node match {
      case _: Bool => Seq(name -> node)
      case _: UInt => Seq(name -> node)
      case vec: Vec[_] =>
        vec.zipWithIndex.flatMap { case (elem, index) =>
          walk(s"${name}_$index", elem)
        }
      case bundle: Bundle =>
        // elements is reversed from source order. Put the source order back.
        bundle.elements.toSeq.reverse.flatMap { case (child, elem) =>
          val next = if (name.isEmpty) child else s"${name}_$child"
          walk(next, elem)
        }
      case _ =>
        throw new IllegalArgumentException(
          s"probe field $name must be Bool, UInt, Vec, or Bundle"
        )
    }
    val fields = walk("", data)
    fields.foreach { case (name, node) =>
      require(node.getWidth >= 1 && node.getWidth <= 64, s"$name wider than 64")
    }
    fields
  }

  def svType(width: Int): String = width match {
    case 1 => "bit"
    case w if w <= 8 => "byte unsigned"
    case w if w <= 16 => "shortint unsigned"
    case w if w <= 32 => "int unsigned"
    case _ => "longint unsigned"
  }

  def cType(width: Int): String = width match {
    case w if w <= 8 => "unsigned char"
    case w if w <= 16 => "unsigned short"
    case w if w <= 32 => "unsigned int"
    case _ => "uint64_t"
  }

  def functionName(spec: ProbeSpec): String =
    s"v_${spec.kind}_${spec.moduleName}"

  def extName(spec: ProbeSpec): String =
    s"${spec.extPrefix}${spec.moduleName}"

  // Verilog black box. Port names match the Chisel IO: clock, enable, then
  // each field. The DPI call happens on this module's clock.
  def verilog(spec: ProbeSpec): String = {
    val args = spec.fields.map(f => s"input ${svType(f.width)} ${f.name}")
    val ports = Seq("input clock", "input enable") ++ args
    val call = spec.fields.map(_.name).mkString(", ")
    val declArgs = spec.fields.map(f => s"input ${svType(f.width)} ${f.name}")
    s"""import "DPI-C" function void ${functionName(spec)}(
       |  ${declArgs.mkString(",\n  ")}
       |);
       |module ${extName(spec)}(
       |  ${ports.mkString(",\n  ")}
       |);
       |  always @(posedge clock) begin
       |    if (enable)
       |      ${functionName(spec)}($call);
       |  end
       |endmodule
       |""".stripMargin
  }

  def cpp(spec: ProbeSpec): String = {
    val args = spec.fields.map(f => s"${cType(f.width)} ${f.name}")
    val words = spec.fields.zipWithIndex.map { case (f, i) =>
      s"  words[$i] = ${f.name};"
    }
    s"""extern "C" void ${functionName(spec)}(
       |    ${args.mkString(",\n    ")}) {
       |  uint64_t words[${spec.fields.length}];
       |${words.mkString("\n")}
       |  ${spec.sink}("${spec.cppName}", words, ${spec.fields.length});
       |}
       |""".stripMargin
  }
}

// Black box instantiated next to the probe. It does not add a port to the CPU.
class ProbeExt(spec: ProbeSpec) extends ExtModule {
  override def desiredName: String = ProbeDpi.extName(spec)

  val clock = IO(Input(Clock()))
  val enable = IO(Input(Bool()))
  val fields = spec.fields.map { field =>
    val port = IO(Input(UInt(field.width.W)))
    port.suggestName(field.name)
    field.name -> port
  }

  setInline(s"${desiredName}.sv", ProbeDpi.verilog(spec))
}

object ProbeExt {
  def attach(spec: ProbeSpec, hw: Seq[(String, Data)]): Unit = {
    val ext = Module(new ProbeExt(spec))
    ext.clock := Module.clock
    val valid = hw.collectFirst { case ("valid", data: Bool) => data }
    ext.enable := valid.getOrElse(true.B) && !Module.reset.asBool
    ext.fields.foreach { case (name, port) =>
      val src = hw.collectFirst { case (`name`, data) => data }.get
      port := src.asUInt
    }
  }
}

object ProbeFiles {
  def write(cpu: String, specs: Seq[ProbeSpec], outDir: String): Unit = {
    val dir = java.nio.file.Paths.get(outDir)
    java.nio.file.Files.createDirectories(dir)
    // Drop the previous elaboration, including the old difftest_probes.cpp.
    val stream = java.nio.file.Files.newDirectoryStream(dir)
    try {
      val it = stream.iterator()
      while (it.hasNext) {
        val path = it.next()
        val name = path.getFileName.toString
        val stale = name == "probes.cpp" || name == "difftest_probes.cpp" ||
          (name.startsWith("DiffExt") && name.endsWith(".sv"))
        if (stale) {
          java.nio.file.Files.delete(path)
        }
      }
    } finally {
      stream.close()
    }
    val cpp = new StringBuilder
    cpp ++= s"// Generated by ProbeModule.collect for $cpu. Do not edit.\n"
    cpp ++= "#include <cstdint>\n\n"
    specs.map(_.sink).distinct.foreach { sink =>
      cpp ++=
        s"""extern "C" void $sink(const char* name, const uint64_t* words, int nwords);\n"""
    }
    cpp ++= "\n"
    specs.foreach { spec =>
      cpp ++= ProbeDpi.cpp(spec)
      cpp ++= "\n"
      val sv = dir.resolve(s"${ProbeDpi.extName(spec)}.sv")
      java.nio.file.Files.writeString(sv, ProbeDpi.verilog(spec))
    }
    java.nio.file.Files.writeString(dir.resolve("probes.cpp"), cpp.result())
  }
}
