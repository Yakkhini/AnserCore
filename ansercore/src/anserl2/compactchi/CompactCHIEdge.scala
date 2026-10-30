/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package anserl2.compactchi

import chisel3._

/** Protocol-level legality and response mapping for a Compact CHI endpoint. */
object CompactCHIEdge {
  private def oneOf(value: UInt, values: UInt*): Bool =
    values.map(value === _).reduce(_ || _)

  // Encoding legality (guide 9.2.5) is separate from endpoint support.
  // Atomic encodings are assigned, but their transaction semantics remain TBD
  // in the supplied draft; this library does not implement Atomic transactions.
  def legalReq(opcode: UInt): Bool = oneOf(
    opcode,
    CompactCHIOpcode.Req.StashShared,
    CompactCHIOpcode.Req.StashUnique,
    CompactCHIOpcode.Req.ReadNoSnp,
    CompactCHIOpcode.Req.ReadOnce,
    CompactCHIOpcode.Req.ReadShared,
    CompactCHIOpcode.Req.WriteNoSnpPtl,
    CompactCHIOpcode.Req.WriteNoSnpFull,
    CompactCHIOpcode.Req.WriteUniquePtl,
    CompactCHIOpcode.Req.WriteUniqueFull,
    CompactCHIOpcode.Req.CleanShared,
    CompactCHIOpcode.Req.CleanInvalid,
    CompactCHIOpcode.Req.MakeInvalid,
    CompactCHIOpcode.Req.ReadUnique,
    CompactCHIOpcode.Req.MakeUnique,
  ) ||
    // 0x20-0x27 AtomicLoad, 0x28-0x2F AtomicStore, 0x30 AtomicSwap, 0x31 AtomicCompare.
    (opcode >= 0x20.U && opcode <= 0x31.U)

  def legalEvt(opcode: UInt): Bool = oneOf(opcode, CompactCHIOpcode.Evt.Evict, CompactCHIOpcode.Evt.WriteBackFull)

  def legalUpRsp(opcode: UInt): Bool = oneOf(opcode, CompactCHIOpcode.Rsp.CompAck, CompactCHIOpcode.Rsp.SnpResp)

  def legalUpDat(opcode: UInt): Bool = oneOf(
    opcode,
    CompactCHIOpcode.Dat.NonCopyBackWrData,
    CompactCHIOpcode.Dat.CopyBackWrData,
    CompactCHIOpcode.Dat.SnpRespData,
  )

  def legalDownRsp(opcode: UInt): Bool = oneOf(
    opcode,
    CompactCHIOpcode.Rsp.CompStash,
    CompactCHIOpcode.Rsp.Comp,
    CompactCHIOpcode.Rsp.DBIDResp,
    CompactCHIOpcode.Rsp.CompDBIDResp,
    CompactCHIOpcode.Rsp.CompCMO,
  )

  def legalDownDat(opcode: UInt): Bool = opcode === CompactCHIOpcode.Dat.CompData

  def legalSnp(opcode: UInt): Bool = oneOf(
    opcode,
    CompactCHIOpcode.Snp.SnpMakeInvalid,
    CompactCHIOpcode.Snp.SnpToInvalid,
    CompactCHIOpcode.Snp.SnpToShared,
    CompactCHIOpcode.Snp.SnpToClean,
  )

  def responseNeedsAck(opcode: UInt): Bool =
    oneOf(opcode, CompactCHIOpcode.Req.ReadShared, CompactCHIOpcode.Req.ReadUnique, CompactCHIOpcode.Req.MakeUnique)

  // ReadUnique may complete with Comp when the requester still has data.
  def mayReturnData(opcode: UInt): Bool = oneOf(
    opcode,
    CompactCHIOpcode.Req.ReadNoSnp,
    CompactCHIOpcode.Req.ReadOnce,
    CompactCHIOpcode.Req.ReadShared,
    CompactCHIOpcode.Req.ReadUnique,
  )

  private def compAck(c: CompactCHIParams, dbid: UInt, requester: UInt, completer: UInt, traceTag: Bool)
    : CompactCHIUpRspFlit = {
    val result = Wire(new CompactCHIUpRspFlit(c))
    result          := 0.U.asTypeOf(new CompactCHIUpRspFlit(c))
    result.opcode   := CompactCHIOpcode.Rsp.CompAck
    result.txnID    := dbid
    result.srcID    := requester
    result.tgtID    := completer
    result.traceTag := traceTag
    result
  }

  // The caller schedules this only after the cache accepts the completed state/data.
  def compAck(c: CompactCHIParams, completion: CompactCHIDownRspFlit): CompactCHIUpRspFlit =
    compAck(c, completion.dbID, completion.tgtID, completion.srcID, completion.traceTag)

  def compAck(c: CompactCHIParams, completion: CompactCHIDownDatFlit): CompactCHIUpRspFlit =
    compAck(c, completion.dbID, completion.tgtID, completion.srcID, completion.traceTag)

  // Snoop final state and errors are supplied by the Core's cache controller.
  def snoopResponse(c: CompactCHIParams, snoop: CompactCHISnpFlit, resp: UInt, respErr: UInt): CompactCHIUpRspFlit = {
    val result = Wire(new CompactCHIUpRspFlit(c))
    result.opcode   := CompactCHIOpcode.Rsp.SnpResp
    result.txnID    := snoop.txnID
    result.srcID    := snoop.tgtID
    result.tgtID    := snoop.srcID
    result.traceTag := snoop.traceTag
    result.resp     := resp
    result.respErr  := respErr
    result
  }
}
