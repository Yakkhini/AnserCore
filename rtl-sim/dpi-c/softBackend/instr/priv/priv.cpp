#include "dpi-c/softBackend/exec.h"

ExecResult exec_wfi(ExecContext* ctx) {
  // No interrupt is delivered, so WFI does not wait.
  if (ctx->hart->priv == 0 ||
      (ctx->hart->priv != 3 && (ctx->hart->csr.mstatus & kMstatusTw) != 0)) {
    return exec_fault(kExcIllegal, ctx->inst);
  }
  return {};
}

ExecResult exec_sret(ExecContext* ctx) {
  IdealHart* hart = ctx->hart;
  if (hart->priv < 1 ||
      (hart->priv == 1 && (hart->csr.mstatus & kMstatusTsr) != 0)) {
    return exec_fault(kExcIllegal, ctx->inst);
  }
  uint64_t status = hart->csr.mstatus;
  unsigned previous = (status & kMstatusSpp) != 0 ? 1 : 0;
  if ((status & kMstatusSpie) != 0) {
    status |= kMstatusSie;
  } else {
    status &= ~kMstatusSie;
  }
  status |= kMstatusSpie;
  status &= ~(kMstatusSpp | kMstatusMprv);
  hart->csr.mstatus = status;
  hart->priv = static_cast<unsigned char>(previous);
  ExecResult result;
  result.taken = true;
  result.target = hart->csr.sepc;
  return result;
}

ExecResult exec_sfence_vma(ExecContext* ctx) {
  if (ctx->hart->priv < 1 ||
      (ctx->hart->priv == 1 && (ctx->hart->csr.mstatus & kMstatusTvm) != 0)) {
    return exec_fault(kExcIllegal, ctx->inst);
  }
  return {};
}

ExecResult exec_fence_i(ExecContext*) { return {}; }

ExecResult exec_ecall(ExecContext* ctx) {
  uint64_t cause = kExcEcallM;
  if (ctx->hart->priv == 0) {
    cause = kExcEcallU;
  } else if (ctx->hart->priv == 1) {
    cause = kExcEcallS;
  } else if (ctx->hart->priv != 3) {
    return exec_fault(kExcIllegal, ctx->inst);
  }
  return exec_fault(cause, 0);
}

ExecResult exec_ebreak(ExecContext* ctx) {
  return exec_fault(kExcBreakpoint, ctx->hart->pc);
}

ExecResult exec_mret(ExecContext* ctx) {
  IdealHart* hart = ctx->hart;
  if (hart->priv != 3) {
    return exec_fault(kExcIllegal, ctx->inst);
  }
  uint64_t status = hart->csr.mstatus;
  unsigned previous = static_cast<unsigned>((status >> 11) & 3u);
  bool pie = (status & kMstatusMpie) != 0;
  if (pie) {
    status |= kMstatusMie;
  } else {
    status &= ~kMstatusMie;
  }
  status |= kMstatusMpie;
  status &= ~kMstatusMpp;
  if (previous != 3) {
    status &= ~kMstatusMprv;
  }
  hart->csr.mstatus = status;
  hart->priv = static_cast<unsigned char>(previous);
  ExecResult result;
  result.taken = true;
  result.target = hart->csr.mepc;
  return result;
}

ExecResult exec_csrrw(ExecContext* ctx) { return ideal_csr(ctx, 0, false); }
ExecResult exec_csrrs(ExecContext* ctx) { return ideal_csr(ctx, 1, false); }
ExecResult exec_csrrc(ExecContext* ctx) { return ideal_csr(ctx, 2, false); }
ExecResult exec_csrrwi(ExecContext* ctx) { return ideal_csr(ctx, 0, true); }
ExecResult exec_csrrsi(ExecContext* ctx) { return ideal_csr(ctx, 1, true); }
ExecResult exec_csrrci(ExecContext* ctx) { return ideal_csr(ctx, 2, true); }
