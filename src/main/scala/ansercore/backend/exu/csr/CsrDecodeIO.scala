/* CSR state summary consumed by the scalar decoder. */
package ansercore.backend.exu.csr

import chisel3._

/** CSR state already reduced to predicates used by Decode. */
class AnsCsrDecodeState extends Bundle {
  val fsOff                = Bool()
  val frmIllegal           = Bool()
  val wfiIllegal           = Bool()
  val wrsNtoIllegal        = Bool()
  val sretIllegal          = Bool()
  val mretIllegal          = Bool()
  val sfenceVmaIllegal     = Bool()
  val cboZeroIllegal       = Bool()
  val cboCleanFlushIllegal = Bool()
  val cboInvalIllegal      = Bool()
}
