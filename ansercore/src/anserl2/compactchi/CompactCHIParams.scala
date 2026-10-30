/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package anserl2.compactchi

import chisel3.util.log2Ceil

/** Single-core requester node IDs used by the Anser integration profile. */
object CompactCHIUpstreamNodeId {
  val ICache  = 0
  val PTW     = 1
  val DCache  = 2
  val UnCache = 3
}

/** Widths taken from Compact CHI 0828 section 9.1. */
final case class CompactCHIParams(
  addrBits:             Int = 48,
  upstreamNodeIdBits:   Int = 7,
  downstreamNodeIdBits: Int = 7,
  txnIdBits:            Int = 8,
  dbidBits:             Int = 8,
  tagAliasBits:         Int,
  dataBits:             Int = 256,
  dataIdBits:           Int = 1,
  downReqOpcodeBits:    Int = 7,
  reqOpcodeBits:        Int = 6,
  evtOpcodeBits:        Int = 1,
  snpOpcodeBits:        Int = 2,
  upRspOpcodeBits:      Int = 1,
  downRspOpcodeBits:    Int = 3,
  upDatOpcodeBits:      Int = 2,
  downDatOpcodeBits:    Int = 1,
  sizeBits:             Int = 3,
  respErrBits:          Int = 2,
  respBits:             Int = 3,
  cBusyBits:            Int = 3,
  dataSourceBits:       Int = 5,
) {
  require(addrBits > 6, "Compact CHI addresses must contain a cache-line offset")
  require(upstreamNodeIdBits > 0 && downstreamNodeIdBits > 0)
  require(txnIdBits > 0 && dbidBits > 0 && tagAliasBits >= 0)
  require(reqOpcodeBits > 0 && reqOpcodeBits <= 6)
  require(evtOpcodeBits == 1 && snpOpcodeBits > 0 && snpOpcodeBits <= 2)
  require(upRspOpcodeBits == 1 && downRspOpcodeBits > 0 && downRspOpcodeBits <= 3)
  require(upDatOpcodeBits > 0 && upDatOpcodeBits <= 2 && downDatOpcodeBits == 1)
  require(sizeBits == 3 && respErrBits == 2 && respBits == 3)
  require(cBusyBits == 3 && dataSourceBits == 5)
  require(dataBits == 64 || dataBits == 128 || dataBits == 256 || dataBits == 512)
  require(dataBits % 8 == 0)
  require(dataIdBits == log2Ceil(512 / dataBits), "Compact CHI DataID width is log2(512 / Data_Width)")
  val byteEnableBits: Int = dataBits / 8
  val dataBytes: Int      = dataBits / 8
  val snoopAddrBits: Int  = addrBits - 3
}
