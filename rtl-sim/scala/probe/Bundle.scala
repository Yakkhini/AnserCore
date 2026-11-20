package probe

import chisel3._

// Hardware recorded by one DPI call. A subclass adds the fields.
// desiredCppName is the name stored with the sample. It is the class name.
abstract class ProbeBundle extends Bundle {
  def desiredModuleName: String = {
    val parts = getClass.getName.split("[.$]").filter(_.nonEmpty)
    parts.last
  }

  def desiredCppName: String = desiredModuleName
}
