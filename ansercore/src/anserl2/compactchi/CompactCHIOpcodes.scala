/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package anserl2.compactchi

import chisel3._

object CompactCHIRespErr {
  val NoError = 0.U(2.W)
  val NDERR   = 3.U(2.W)
  val DERR    = 2.U(2.W)
}

/** Compact CHI Resp encodings from guide section 9.2.17. */
object CompactCHIResp {
  val I     = 0.U(3.W)
  val SC    = 1.U(3.W)
  val UC    = 2.U(3.W)
  val I_PD  = 4.U(3.W)
  val SC_PD = 5.U(3.W)
  val UC_PD = 6.U(3.W)
}

object CompactCHIOpcode {
  object Evt {
    val Evict         = 0.U(1.W)
    val WriteBackFull = 1.U(1.W)
  }
  object Req {
    val StashShared     = 0x00.U(6.W)
    val StashUnique     = 0x01.U(6.W)
    val ReadNoSnp       = 0x02.U(6.W)
    val ReadOnce        = 0x03.U(6.W)
    val ReadShared      = 0x04.U(6.W)
    val WriteNoSnpPtl   = 0x08.U(6.W)
    val WriteNoSnpFull  = 0x09.U(6.W)
    val WriteUniquePtl  = 0x0a.U(6.W)
    val WriteUniqueFull = 0x0b.U(6.W)
    val CleanShared     = 0x0c.U(6.W)
    val CleanInvalid    = 0x0d.U(6.W)
    val MakeInvalid     = 0x0e.U(6.W)
    val ReadUnique      = 0x10.U(6.W)
    val MakeUnique      = 0x12.U(6.W)
  }
  object Snp {
    val SnpMakeInvalid = 0.U(2.W)
    val SnpToInvalid   = 1.U(2.W)
    val SnpToShared    = 2.U(2.W)
    val SnpToClean     = 3.U(2.W)
  }
  object Rsp {
    val CompStash    = 0.U(3.W)
    val Comp         = 1.U(3.W)
    val DBIDResp     = 2.U(3.W)
    val CompDBIDResp = 3.U(3.W)
    val CompCMO      = 4.U(3.W)
    val CompAck      = 0.U(1.W)
    val SnpResp      = 1.U(1.W)
  }
  object Dat {
    val CompData          = 0.U(1.W)
    val NonCopyBackWrData = 0.U(2.W)
    val CopyBackWrData    = 2.U(2.W)
    val SnpRespData       = 3.U(2.W)
  }
}
