#include "dpi-c/softBackend/exec.h"

namespace {

uint64_t sext_len(uint64_t value, unsigned size) {
  if (size == 4) {
    return static_cast<uint64_t>(
        static_cast<int64_t>(static_cast<int32_t>(value)));
  }
  return value;
}

uint64_t mask_len(unsigned size) {
  return size == 8 ? ~0ull : (1ull << (size * 8)) - 1ull;
}

void reserve(IdealHart *hart, uint64_t addr, unsigned size) {
  hart->lr.valid = true;
  hart->lr.addr = addr;
  hart->lr.len = static_cast<uint8_t>(size);
}

bool reserved(const IdealHart *hart, uint64_t addr, unsigned size) {
  return hart->lr.valid && hart->lr.addr == addr && hart->lr.len == size;
}

ExecResult amo(ExecContext *ctx, unsigned size, int op) {
  uint64_t addr = ideal_gpr_read(ctx->hart, ctx->operands.rs1);
  uint64_t src = ideal_gpr_read(ctx->hart, ctx->operands.rs2) & mask_len(size);
  ctx->hart->lr.valid = false;
  uint64_t loaded = 0;
  ExecResult fault = ideal_load_data(ctx, addr, size, &loaded);
  if (fault.fault)
    return fault;
  loaded &= mask_len(size);
  int64_t signed_old = static_cast<int64_t>(sext_len(loaded, size));
  int64_t signed_src = static_cast<int64_t>(sext_len(src, size));
  uint64_t result = 0;
  switch (op) {
  case 0:
    result = loaded + src;
    break;
  case 1:
    result = src;
    break;
  case 4:
    result = loaded ^ src;
    break;
  case 8:
    result = loaded | src;
    break;
  case 12:
    result = loaded & src;
    break;
  case 16:
    result = signed_old < signed_src ? loaded : src;
    break;
  case 20:
    result = signed_old > signed_src ? loaded : src;
    break;
  case 24:
    result = loaded < src ? loaded : src;
    break;
  case 28:
    result = loaded > src ? loaded : src;
    break;
  default:
    return exec_fault(kExcIllegal, ctx->inst);
  }
  bool saw_mmio = fault.mmio;
  fault = ideal_store_data(ctx, addr, size, result & mask_len(size));
  fault.mmio = fault.mmio || saw_mmio;
  if (fault.fault)
    return fault;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext_len(loaded, size));
  return fault;
}

ExecResult lr(ExecContext *ctx, unsigned size) {
  uint64_t addr = ideal_gpr_read(ctx->hart, ctx->operands.rs1);
  uint64_t loaded = 0;
  ExecResult fault = ideal_load_data(ctx, addr, size, &loaded);
  if (fault.fault)
    return fault;
  reserve(ctx->hart, addr, size);
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext_len(loaded, size));
  return fault;
}

ExecResult sc(ExecContext *ctx, unsigned size) {
  uint64_t addr = ideal_gpr_read(ctx->hart, ctx->operands.rs1);
  bool hit = reserved(ctx->hart, addr, size);
  ctx->hart->lr.valid = false;
  if (!hit) {
    uint64_t ignored = 0;
    ExecResult fault = ideal_load_data(ctx, addr, size, &ignored);
    if (fault.fault) {
      return fault;
    }
    ideal_gpr_write(ctx->hart, ctx->operands.rd, 1);
    return fault;
  }
  ExecResult fault = ideal_store_data(
      ctx, addr, size, ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  if (fault.fault) {
    return fault;
  }
  ideal_gpr_write(ctx->hart, ctx->operands.rd, 0);
  return fault;
}

} // namespace

ExecResult exec_lr_w(ExecContext *ctx) { return lr(ctx, 4); }
ExecResult exec_lr_d(ExecContext *ctx) { return lr(ctx, 8); }
ExecResult exec_sc_w(ExecContext *ctx) { return sc(ctx, 4); }
ExecResult exec_sc_d(ExecContext *ctx) { return sc(ctx, 8); }
ExecResult exec_amoadd_w(ExecContext *ctx) { return amo(ctx, 4, 0); }
ExecResult exec_amoadd_d(ExecContext *ctx) { return amo(ctx, 8, 0); }
ExecResult exec_amoswap_w(ExecContext *ctx) { return amo(ctx, 4, 1); }
ExecResult exec_amoswap_d(ExecContext *ctx) { return amo(ctx, 8, 1); }
ExecResult exec_amoxor_w(ExecContext *ctx) { return amo(ctx, 4, 4); }
ExecResult exec_amoxor_d(ExecContext *ctx) { return amo(ctx, 8, 4); }
ExecResult exec_amoor_w(ExecContext *ctx) { return amo(ctx, 4, 8); }
ExecResult exec_amoor_d(ExecContext *ctx) { return amo(ctx, 8, 8); }
ExecResult exec_amoand_w(ExecContext *ctx) { return amo(ctx, 4, 12); }
ExecResult exec_amoand_d(ExecContext *ctx) { return amo(ctx, 8, 12); }
ExecResult exec_amomin_w(ExecContext *ctx) { return amo(ctx, 4, 16); }
ExecResult exec_amomin_d(ExecContext *ctx) { return amo(ctx, 8, 16); }
ExecResult exec_amomax_w(ExecContext *ctx) { return amo(ctx, 4, 20); }
ExecResult exec_amomax_d(ExecContext *ctx) { return amo(ctx, 8, 20); }
ExecResult exec_amominu_w(ExecContext *ctx) { return amo(ctx, 4, 24); }
ExecResult exec_amominu_d(ExecContext *ctx) { return amo(ctx, 8, 24); }
ExecResult exec_amomaxu_w(ExecContext *ctx) { return amo(ctx, 4, 28); }
ExecResult exec_amomaxu_d(ExecContext *ctx) { return amo(ctx, 8, 28); }
