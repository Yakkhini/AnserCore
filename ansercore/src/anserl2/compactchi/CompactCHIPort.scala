/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package anserl2.compactchi

import chisel3._
import chisel3.util.Decoupled

/** Compact CHI REQ flit independent of any cache implementation parameters. */
class CompactCHIReqFlit(c: CompactCHIParams, wayBits: Int) extends Bundle {
  require(wayBits >= 0, "Compact CHI Way width cannot be negative")
  val txnID        = UInt(c.txnIdBits.W)
  val srcID        = UInt(c.upstreamNodeIdBits.W)
  val tgtID        = UInt(c.downstreamNodeIdBits.W)
  val opcode       = UInt(c.reqOpcodeBits.W)
  val size         = UInt(c.sizeBits.W)
  val addr         = UInt(c.addrBits.W)
  val tagAlias     = UInt(c.tagAliasBits.W)
  val ns           = Bool()
  val order        = UInt(2.W)
  val memAttr      = UInt(4.W)
  val excl         = Bool()
  val expCompData  = Bool()
  val expCompStash = Bool()
  val wayValid     = Bool()
  val way          = UInt(wayBits.W)
  val traceTag     = Bool()
}

/** Compact CHI EVT flit independent of any cache implementation parameters. */
class CompactCHIEvtFlit(c: CompactCHIParams, wayBits: Int) extends Bundle {
  require(wayBits >= 0, "Compact CHI Way width cannot be negative")
  val txnID    = UInt(c.txnIdBits.W)
  val srcID    = UInt(c.upstreamNodeIdBits.W)
  val tgtID    = UInt(c.downstreamNodeIdBits.W)
  val opcode   = UInt(c.evtOpcodeBits.W)
  val addr     = UInt(c.addrBits.W)
  val ns       = Bool()
  val memAttr  = Bool()
  val wayValid = Bool()
  val way      = UInt(wayBits.W)
  val traceTag = Bool()
}

/** Compact CHI upstream RSP flit emitted by a Requester. */
class CompactCHIUpRspFlit(c: CompactCHIParams) extends Bundle {
  val txnID    = UInt(c.dbidBits.W)
  val srcID    = UInt(c.upstreamNodeIdBits.W)
  val tgtID    = UInt(c.downstreamNodeIdBits.W)
  val opcode   = UInt(c.upRspOpcodeBits.W)
  val respErr  = UInt(c.respErrBits.W)
  val resp     = UInt(c.respBits.W)
  val traceTag = Bool()
}

/** Compact CHI upstream DAT flit emitted by a Requester. */
class CompactCHIUpDatFlit(c: CompactCHIParams) extends Bundle {
  val txnID      = UInt(c.dbidBits.W)
  val srcID      = UInt(c.upstreamNodeIdBits.W)
  val tgtID      = UInt(c.downstreamNodeIdBits.W)
  val opcode     = UInt(c.upDatOpcodeBits.W)
  val respErr    = UInt(c.respErrBits.W)
  val resp       = UInt(c.respBits.W)
  val dataID     = UInt(c.dataIdBits.W)
  val data       = UInt(c.dataBits.W)
  val byteEnable = UInt(c.byteEnableBits.W)
  val traceTag   = Bool()
}

/** Compact CHI downstream RSP flit received by a Requester. */
class CompactCHIDownRspFlit(c: CompactCHIParams) extends Bundle {
  val txnID    = UInt(c.txnIdBits.W)
  val srcID    = UInt(c.downstreamNodeIdBits.W)
  val tgtID    = UInt(c.upstreamNodeIdBits.W)
  val opcode   = UInt(c.downRspOpcodeBits.W)
  val dbID     = UInt(c.dbidBits.W)
  val respErr  = UInt(c.respErrBits.W)
  val resp     = UInt(c.respBits.W)
  val cBusy    = UInt(c.cBusyBits.W)
  val traceTag = Bool()
}

/** Compact CHI downstream DAT flit received by a Requester. */
class CompactCHIDownDatFlit(c: CompactCHIParams) extends Bundle {
  val txnID      = UInt(c.txnIdBits.W)
  val srcID      = UInt(c.downstreamNodeIdBits.W)
  val tgtID      = UInt(c.upstreamNodeIdBits.W)
  val opcode     = UInt(c.downDatOpcodeBits.W)
  val dbID       = UInt(c.dbidBits.W)
  val respErr    = UInt(c.respErrBits.W)
  val resp       = UInt(c.respBits.W)
  val dataSource = UInt(c.dataSourceBits.W)
  val cBusy      = UInt(c.cBusyBits.W)
  val dataID     = UInt(c.dataIdBits.W)
  val data       = UInt(c.dataBits.W)
  val traceTag   = Bool()
}

/** Compact CHI SNP flit received by a Requester. */
class CompactCHISnpFlit(c: CompactCHIParams) extends Bundle {
  val txnID    = UInt(c.dbidBits.W)
  val srcID    = UInt(c.downstreamNodeIdBits.W)
  val tgtID    = UInt(c.upstreamNodeIdBits.W)
  val opcode   = UInt(c.snpOpcodeBits.W)
  val addr     = UInt(c.snoopAddrBits.W)
  val tagAlias = UInt(c.tagAliasBits.W)
  val ns       = Bool()
  val traceTag = Bool()
}

/** All channels visible at a Compact CHI Requester endpoint. */
class CompactCHIRequesterIO(c: CompactCHIParams, wayBits: Int) extends Bundle {
  val req   = Decoupled(new CompactCHIReqFlit(c, wayBits))
  val evt   = Decoupled(new CompactCHIEvtFlit(c, wayBits))
  val txRsp = Decoupled(new CompactCHIUpRspFlit(c))
  val txDat = Decoupled(new CompactCHIUpDatFlit(c))
  val rxRsp = Flipped(Decoupled(new CompactCHIDownRspFlit(c)))
  val rxDat = Flipped(Decoupled(new CompactCHIDownDatFlit(c)))
  val rxSnp = Flipped(Decoupled(new CompactCHISnpFlit(c)))
}
