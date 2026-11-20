#include "dpi-c/softBackend/exec.h"

namespace {

constexpr uint64_t kSstatusWrite = kMstatusSie | kMstatusSpie | kMstatusSpp |
                                   kMstatusFs | kMstatusSum | kMstatusMxr;
constexpr uint64_t kMstatusWrite = kSstatusWrite | kMstatusMie | kMstatusMpie |
                                   kMstatusMpp | kMstatusMprv | kMstatusTvm |
                                   kMstatusTw | kMstatusTsr;
constexpr uint64_t kMedelegMask =
    (1ull << kExcInstMisaligned) | (1ull << kExcInstAccess) |
    (1ull << kExcIllegal) | (1ull << kExcBreakpoint) |
    (1ull << kExcLoadMisaligned) | (1ull << kExcLoadAccess) |
    (1ull << kExcStoreMisaligned) | (1ull << kExcStoreAccess) |
    (1ull << kExcEcallU) | (1ull << kExcEcallS) | (1ull << kExcInstPage) |
    (1ull << kExcLoadPage) | (1ull << kExcStorePage);
constexpr uint64_t kSieMask = (1ull << 1) | (1ull << 5) | (1ull << 9);
constexpr uint64_t kMieMask =
    kSieMask | (1ull << 3) | (1ull << 7) | (1ull << 11);
constexpr uint64_t kCounterMask = 0x7ull;
constexpr uint64_t kMenvcfgStce = 1ull << 63;
constexpr uint64_t kMenvcfgMask = (1ull << 0) | (3ull << 4) | (1ull << 6) |
                                  (1ull << 7) | (1ull << 62) | kMenvcfgStce;
constexpr uint64_t kPmpAddrMask = (1ull << 54) - 1;
constexpr unsigned kPmpEntries = 16;

uint64_t legal_mpp(uint64_t value, uint64_t old) {
  uint64_t mpp = value & kMstatusMpp;
  if (mpp == (2ull << 11)) {
    return old & kMstatusMpp;
  }
  return mpp;
}

uint64_t write_masked(uint64_t old, uint64_t value, uint64_t mask) {
  return (old & ~mask) | (value & mask);
}

void dirty_fp(IdealHart *hart) {
  hart->csr.mstatus = (hart->csr.mstatus & ~kMstatusFs) | kMstatusFsDirty;
}

bool fp_on(const IdealHart *hart) {
  return (hart->csr.mstatus & kMstatusFs) != 0;
}

uint64_t pmp_cfg_read(const IdealHart *hart, unsigned first) {
  uint64_t value = 0;
  for (unsigned i = 0; i < 8; i++) {
    unsigned index = first + i;
    if (index < kPmpEntries) {
      value |= static_cast<uint64_t>(hart->csr.pmpcfg[index]) << (8u * i);
    }
  }
  return value;
}

void pmp_cfg_write(IdealHart *hart, unsigned first, uint64_t value) {
  constexpr uint64_t kKeep = 0x9fu;
  for (unsigned i = 0; i < 8; i++) {
    unsigned index = first + i;
    if (index >= kPmpEntries || (hart->csr.pmpcfg[index] & 0x80u) != 0) {
      continue;
    }
    uint8_t cfg = static_cast<uint8_t>((value >> (8u * i)) & kKeep);
    if ((cfg & 1u) == 0) {
      cfg = static_cast<uint8_t>(cfg & ~2u);
    }
    hart->csr.pmpcfg[index] = cfg;
  }
}

bool pmp_addr_locked(const IdealHart *hart, unsigned index) {
  if ((hart->csr.pmpcfg[index] & 0x80u) != 0) {
    return true;
  }
  if (index + 1 < kPmpEntries &&
      (hart->csr.pmpcfg[index + 1] & 0x98u) == 0x88u) {
    return true;
  }
  return false;
}

bool counter_ok(const IdealHart *hart, unsigned index) {
  if (hart->priv >= 3) {
    return true;
  }
  uint64_t bit = 1ull << index;
  if ((hart->csr.mcounteren & bit) == 0) {
    return false;
  }
  if (hart->priv == 0 && (hart->csr.scounteren & bit) == 0) {
    return false;
  }
  return true;
}

uint64_t tvec_write(uint64_t old, uint64_t value) {
  uint64_t mode = value & 3ull;
  if (mode > 1) {
    mode = old & 3ull;
  }
  return (value & ~3ull) | mode;
}

bool read_csr(IdealHart *hart, uint64_t csr, bool write, uint64_t *value) {
  if (csr > 0xfff) {
    return false;
  }
  unsigned need = static_cast<unsigned>((csr >> 8) & 3u);
  if (hart->priv < need) {
    return false;
  }
  if (write && ((csr >> 10) & 3u) == 3u) {
    return false;
  }
  if (hart->priv == 1 && (hart->csr.mstatus & kMstatusTvm) != 0 &&
      csr == 0x180) {
    return false;
  }

  switch (csr) {
  case 0x001:
    if (!fp_on(hart))
      return false;
    *value = hart->fpr.fcsr & 0x1fu;
    return true;
  case 0x002:
    if (!fp_on(hart))
      return false;
    *value = (hart->fpr.fcsr >> 5) & 7u;
    return true;
  case 0x003:
    if (!fp_on(hart))
      return false;
    *value = hart->fpr.fcsr & 0xffu;
    return true;
  case 0x100:
    *value = hart->csr.mstatus & (kSstatusWrite | kMstatusUxl);
    if ((hart->csr.mstatus & kMstatusFs) == kMstatusFsDirty) {
      *value |= kMstatusSd;
    }
    return true;
  case 0x104:
    *value = hart->csr.mie & hart->csr.mideleg & kSieMask;
    return true;
  case 0x105:
    *value = hart->csr.stvec;
    return true;
  case 0x106:
    *value = hart->csr.scounteren & kCounterMask;
    return true;
  case 0x10A:
    *value = hart->csr.senvcfg;
    return true;
  case 0x140:
    *value = hart->csr.sscratch;
    return true;
  case 0x141:
    *value = hart->csr.sepc;
    return true;
  case 0x142:
    *value = hart->csr.scause;
    return true;
  case 0x143:
    *value = hart->csr.stval;
    return true;
  case 0x144:
    *value = hart->csr.mip & hart->csr.mideleg & kSieMask;
    return true;
  case 0x14D:
    *value = hart->csr.stimecmp;
    return true;
  case 0x180:
    *value = hart->csr.satp;
    return true;
  case 0x300:
    *value = hart->csr.mstatus & (kMstatusWrite | kMstatusUxl | kMstatusSxl);
    if ((hart->csr.mstatus & kMstatusFs) == kMstatusFsDirty) {
      *value |= kMstatusSd;
    }
    return true;
  case 0x301:
    *value = kMisa;
    return true;
  case 0x302:
    *value = hart->csr.medeleg & kMedelegMask;
    return true;
  case 0x303:
    *value = hart->csr.mideleg & kSieMask;
    return true;
  case 0x304:
    *value = hart->csr.mie & kMieMask;
    return true;
  case 0x305:
    *value = hart->csr.mtvec;
    return true;
  case 0x306:
    *value = hart->csr.mcounteren & kCounterMask;
    return true;
  case 0x30A:
    *value = hart->csr.menvcfg & kMenvcfgMask;
    return true;
  case 0x320:
    *value = hart->csr.mcountinhibit & 0x5ull;
    return true;
  case 0x340:
    *value = hart->csr.mscratch;
    return true;
  case 0x341:
    *value = hart->csr.mepc;
    return true;
  case 0x342:
    *value = hart->csr.mcause;
    return true;
  case 0x343:
    *value = hart->csr.mtval;
    return true;
  case 0x344:
    *value = hart->csr.mip & kMieMask;
    return true;
  case 0xB00:
    *value = hart->csr.mcycle;
    return true;
  case 0xB02:
    *value = hart->csr.minstret;
    return true;
  case 0xC00:
    if (!counter_ok(hart, 0))
      return false;
    *value = hart->csr.mcycle;
    return true;
  case 0xC01:
    if (!counter_ok(hart, 1))
      return false;
    *value = ideal_mtime();
    return true;
  case 0xC02:
    if (!counter_ok(hart, 2))
      return false;
    *value = hart->csr.minstret;
    return true;
  case 0xF11:
  case 0xF12:
  case 0xF13:
  case 0xF14:
  case 0xF15:
    *value = 0;
    return true;
  default:
    break;
  }

  if ((csr & 1ull) == 0 && csr >= 0x3A0 && csr <= 0x3AE) {
    unsigned first = static_cast<unsigned>((csr - 0x3A0) * 4u);
    *value = first < kPmpEntries ? pmp_cfg_read(hart, first) : 0;
    return true;
  }
  if (csr >= 0x3B0 && csr <= 0x3EF) {
    unsigned index = static_cast<unsigned>(csr - 0x3B0);
    *value = index < kPmpEntries ? hart->csr.pmpaddr[index] : 0;
    return true;
  }
  return false;
}

bool write_csr(IdealHart *hart, uint64_t csr, uint64_t value) {
  uint64_t ignored = 0;
  if (!read_csr(hart, csr, true, &ignored)) {
    return false;
  }
  switch (csr) {
  case 0x001:
    hart->fpr.fcsr = (hart->fpr.fcsr & ~0x1full) | (value & 0x1fu);
    dirty_fp(hart);
    return true;
  case 0x002:
    hart->fpr.fcsr = (hart->fpr.fcsr & ~0xe0ull) | ((value & 7u) << 5);
    dirty_fp(hart);
    return true;
  case 0x003:
    hart->fpr.fcsr = value & 0xffu;
    dirty_fp(hart);
    return true;
  case 0x100: {
    uint64_t merged = write_masked(hart->csr.mstatus, value, kSstatusWrite);
    hart->csr.mstatus = merged;
    return true;
  }
  case 0x104: {
    uint64_t mask = hart->csr.mideleg & kSieMask;
    hart->csr.mie = (hart->csr.mie & ~mask) | (value & mask);
    return true;
  }
  case 0x105:
    hart->csr.stvec = tvec_write(hart->csr.stvec, value);
    return true;
  case 0x106:
    hart->csr.scounteren = value & kCounterMask;
    return true;
  case 0x10A:
    hart->csr.senvcfg = value;
    return true;
  case 0x140:
    hart->csr.sscratch = value;
    return true;
  case 0x141:
    hart->csr.sepc = value & ~1ull;
    return true;
  case 0x142:
    hart->csr.scause = value;
    return true;
  case 0x143:
    hart->csr.stval = value;
    return true;
  case 0x144: {
    uint64_t mask = hart->csr.mideleg & ((1ull << 1) | (1ull << 5));
    hart->csr.mip = (hart->csr.mip & ~mask) | (value & mask);
    return true;
  }
  case 0x14D:
    hart->csr.stimecmp = value;
    return true;
  case 0x180: {
    uint64_t mode = value & (0xfull << 60);
    if (mode != 0 && mode != (8ull << 60) && mode != (9ull << 60) &&
        mode != (10ull << 60)) {
      mode = hart->csr.satp & (0xfull << 60);
    }
    hart->csr.satp = mode | (value & (((1ull << 16) - 1ull) << 44)) |
                     (value & ((1ull << 44) - 1ull));
    return true;
  }
  case 0x300: {
    uint64_t merged = write_masked(hart->csr.mstatus, value, kMstatusWrite);
    merged = (merged & ~kMstatusMpp) | legal_mpp(merged, hart->csr.mstatus);
    hart->csr.mstatus = merged;
    return true;
  }
  case 0x301:
    return true;
  case 0x302:
    hart->csr.medeleg = value & kMedelegMask;
    return true;
  case 0x303:
    hart->csr.mideleg = value & kSieMask;
    return true;
  case 0x304:
    hart->csr.mie = value & kMieMask;
    return true;
  case 0x305:
    hart->csr.mtvec = tvec_write(hart->csr.mtvec, value);
    return true;
  case 0x306:
    hart->csr.mcounteren = value & kCounterMask;
    return true;
  case 0x30A:
    hart->csr.menvcfg = value & kMenvcfgMask;
    return true;
  case 0x320:
    hart->csr.mcountinhibit = value & 0x5ull;
    return true;
  case 0x340:
    hart->csr.mscratch = value;
    return true;
  case 0x341:
    hart->csr.mepc = value & ~1ull;
    return true;
  case 0x342:
    hart->csr.mcause = value;
    return true;
  case 0x343:
    hart->csr.mtval = value;
    return true;
  case 0x344: {
    uint64_t mask = kSieMask;
    hart->csr.mip = (hart->csr.mip & ~mask) | (value & mask);
    return true;
  }
  case 0xB00:
    hart->csr.mcycle = value;
    return true;
  case 0xB02:
    hart->csr.minstret = value;
    return true;
  case 0xF11:
  case 0xF12:
  case 0xF13:
  case 0xF14:
  case 0xF15:
    return true;
  default:
    break;
  }
  if ((csr & 1ull) == 0 && csr >= 0x3A0 && csr <= 0x3AE) {
    unsigned first = static_cast<unsigned>((csr - 0x3A0) * 4u);
    if (first < kPmpEntries) {
      pmp_cfg_write(hart, first, value);
    }
    return true;
  }
  if (csr >= 0x3B0 && csr <= 0x3EF) {
    unsigned index = static_cast<unsigned>(csr - 0x3B0);
    if (index < kPmpEntries && !pmp_addr_locked(hart, index)) {
      hart->csr.pmpaddr[index] = value & kPmpAddrMask;
    }
    return true;
  }
  return false;
}

} // namespace

void ideal_fix_sd(IdealHart *hart) {
  if ((hart->csr.mstatus & kMstatusFs) == kMstatusFsDirty) {
    hart->csr.mstatus |= kMstatusSd;
  } else {
    hart->csr.mstatus &= ~kMstatusSd;
  }
}

uint64_t ideal_raise(IdealHart *hart, uint64_t cause, uint64_t tval) {
  uint64_t code = cause & ((1ull << 63) - 1ull);
  bool to_s =
      hart->priv <= 1 && code < 64 && ((hart->csr.medeleg >> code) & 1ull) != 0;
  uint64_t status = hart->csr.mstatus;
  uint64_t vector = 0;
  if (to_s) {
    hart->csr.sepc = hart->pc & ~1ull;
    hart->csr.scause = cause;
    hart->csr.stval = tval;
    uint64_t spie = (status & kMstatusSie) != 0 ? kMstatusSpie : 0;
    status &= ~(kMstatusSpp | kMstatusSpie | kMstatusSie);
    if (hart->priv == 1) {
      status |= kMstatusSpp;
    }
    status |= spie;
    hart->priv = 1;
    vector = hart->csr.stvec;
  } else {
    hart->csr.mepc = hart->pc & ~1ull;
    hart->csr.mcause = cause;
    hart->csr.mtval = tval;
    uint64_t pie = (status & kMstatusMie) != 0 ? kMstatusMpie : 0;
    status &= ~(kMstatusMpp | kMstatusMpie | kMstatusMie);
    status |= (static_cast<uint64_t>(hart->priv) << 11) | pie;
    hart->priv = 3;
    vector = hart->csr.mtvec;
  }
  hart->csr.mstatus = status;
  return vector & ~3ull;
}

ExecResult ideal_csr(ExecContext *ctx, int op, bool imm) {
  uint64_t csr = ctx->operands.imm;
  uint64_t src =
      imm ? ctx->operands.rs1 : ideal_gpr_read(ctx->hart, ctx->operands.rs1);
  bool writing = op == 0 || src != 0;
  uint64_t old = 0;
  if (!read_csr(ctx->hart, csr, writing, &old)) {
    return exec_fault(kExcIllegal, ctx->inst);
  }
  if (writing) {
    uint64_t next = src;
    if (op == 1) {
      next = old | src;
    } else if (op == 2) {
      next = old & ~src;
    }
    if (!write_csr(ctx->hart, csr, next)) {
      return exec_fault(kExcIllegal, ctx->inst);
    }
  }
  ideal_gpr_write(ctx->hart, ctx->operands.rd, old);
  return {};
}
