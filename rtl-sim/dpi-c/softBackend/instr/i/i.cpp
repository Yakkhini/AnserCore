#include "dpi-c/softBackend/exec.h"

#include <cstdint>

namespace {

uint64_t src1(const ExecContext* ctx) {
  return ideal_gpr_read(ctx->hart, ctx->operands.rs1);
}

uint64_t src2(const ExecContext* ctx) {
  return ideal_gpr_read(ctx->hart, ctx->operands.rs2);
}

uint64_t sext32(uint64_t value) {
  return static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(value)));
}

uint64_t sra64(uint64_t value, unsigned shamt) {
  if (shamt == 0) {
    return value;
  }
  uint64_t result = value >> shamt;
  if ((value >> 63) != 0) {
    result |= ~0ull << (64 - shamt);
  }
  return result;
}

uint32_t sra32(uint32_t value, unsigned shamt) {
  if (shamt == 0) {
    return value;
  }
  uint32_t result = value >> shamt;
  if ((value >> 31) != 0) {
    result |= 0xffffffffu << (32 - shamt);
  }
  return result;
}

ExecResult load_ext(ExecContext* ctx, unsigned size, bool sign) {
  uint64_t raw = 0;
  uint64_t addr = src1(ctx) + ctx->operands.imm;
  ExecResult result = ideal_load_data(ctx, addr, size, &raw);
  if (result.fault) return result;
  if (sign && size < 8) {
    uint64_t sign_bit = 1ull << (size * 8 - 1);
    uint64_t mask = (1ull << (size * 8)) - 1ull;
    raw &= mask;
    if ((raw & sign_bit) != 0) {
      raw |= ~mask;
    }
  }
  ideal_gpr_write(ctx->hart, ctx->operands.rd, raw);
  return result;
}

ExecResult store_len(ExecContext* ctx, unsigned size) {
  uint64_t addr = src1(ctx) + ctx->operands.imm;
  return ideal_store_data(ctx, addr, size, src2(ctx));
}

ExecResult branch(ExecContext* ctx, bool taken) {
  uint64_t target = ctx->hart->pc + ctx->operands.imm;
  if (taken && (target & 1ull) != 0) {
    return exec_fault(kExcInstMisaligned, target);
  }
  return exec_branch(taken, target);
}

}  // namespace

ExecResult exec_lui(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, ctx->operands.imm);
  return {};
}

ExecResult exec_auipc(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, ctx->hart->pc + ctx->operands.imm);
  return {};
}

ExecResult exec_jal(ExecContext* ctx) {
  uint64_t target = ctx->hart->pc + ctx->operands.imm;
  if ((target & 1ull) != 0) {
    return exec_fault(kExcInstMisaligned, target);
  }
  ideal_gpr_write(ctx->hart, ctx->operands.rd, ctx->hart->pc + ctx->len);
  return exec_branch(true, target);
}

ExecResult exec_jalr(ExecContext* ctx) {
  uint64_t target = (src1(ctx) + ctx->operands.imm) & ~1ull;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, ctx->hart->pc + ctx->len);
  return exec_branch(true, target);
}

ExecResult exec_beq(ExecContext* ctx) {
  return branch(ctx, src1(ctx) == src2(ctx));
}
ExecResult exec_bne(ExecContext* ctx) {
  return branch(ctx, src1(ctx) != src2(ctx));
}
ExecResult exec_blt(ExecContext* ctx) {
  return branch(ctx, static_cast<int64_t>(src1(ctx)) < static_cast<int64_t>(src2(ctx)));
}
ExecResult exec_bge(ExecContext* ctx) {
  return branch(ctx, static_cast<int64_t>(src1(ctx)) >= static_cast<int64_t>(src2(ctx)));
}
ExecResult exec_bltu(ExecContext* ctx) {
  return branch(ctx, src1(ctx) < src2(ctx));
}
ExecResult exec_bgeu(ExecContext* ctx) {
  return branch(ctx, src1(ctx) >= src2(ctx));
}

ExecResult exec_lb(ExecContext* ctx) { return load_ext(ctx, 1, true); }
ExecResult exec_lh(ExecContext* ctx) { return load_ext(ctx, 2, true); }
ExecResult exec_lw(ExecContext* ctx) { return load_ext(ctx, 4, true); }
ExecResult exec_ld(ExecContext* ctx) { return load_ext(ctx, 8, false); }
ExecResult exec_lbu(ExecContext* ctx) { return load_ext(ctx, 1, false); }
ExecResult exec_lhu(ExecContext* ctx) { return load_ext(ctx, 2, false); }
ExecResult exec_lwu(ExecContext* ctx) { return load_ext(ctx, 4, false); }

ExecResult exec_sb(ExecContext* ctx) { return store_len(ctx, 1); }
ExecResult exec_sh(ExecContext* ctx) { return store_len(ctx, 2); }
ExecResult exec_sw(ExecContext* ctx) { return store_len(ctx, 4); }
ExecResult exec_sd(ExecContext* ctx) { return store_len(ctx, 8); }

ExecResult exec_addi(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) + ctx->operands.imm);
  return {};
}
ExecResult exec_slti(ExecContext* ctx) {
  uint64_t result = static_cast<int64_t>(src1(ctx)) <
                            static_cast<int64_t>(ctx->operands.imm)
                        ? 1
                        : 0;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, result);
  return {};
}
ExecResult exec_sltiu(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd,
                  src1(ctx) < ctx->operands.imm ? 1 : 0);
  return {};
}
ExecResult exec_xori(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) ^ ctx->operands.imm);
  return {};
}
ExecResult exec_ori(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) | ctx->operands.imm);
  return {};
}
ExecResult exec_andi(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) & ctx->operands.imm);
  return {};
}
ExecResult exec_addiw(ExecContext* ctx) {
  uint32_t result =
      static_cast<uint32_t>(src1(ctx)) + static_cast<uint32_t>(ctx->operands.imm);
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}
ExecResult exec_slli(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(ctx->operands.imm) & 0x3fu;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) << shamt);
  return {};
}
ExecResult exec_srli(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(ctx->operands.imm) & 0x3fu;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) >> shamt);
  return {};
}
ExecResult exec_srai(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(ctx->operands.imm) & 0x3fu;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sra64(src1(ctx), shamt));
  return {};
}
ExecResult exec_slliw(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(ctx->operands.imm) & 0x1fu;
  uint32_t result = static_cast<uint32_t>(src1(ctx)) << shamt;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}
ExecResult exec_srliw(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(ctx->operands.imm) & 0x1fu;
  uint32_t result = static_cast<uint32_t>(src1(ctx)) >> shamt;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}
ExecResult exec_sraiw(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(ctx->operands.imm) & 0x1fu;
  uint32_t result = sra32(static_cast<uint32_t>(src1(ctx)), shamt);
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}

ExecResult exec_add(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) + src2(ctx));
  return {};
}
ExecResult exec_sub(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) - src2(ctx));
  return {};
}
ExecResult exec_sll(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(src2(ctx)) & 0x3fu;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) << shamt);
  return {};
}
ExecResult exec_slt(ExecContext* ctx) {
  uint64_t result =
      static_cast<int64_t>(src1(ctx)) < static_cast<int64_t>(src2(ctx)) ? 1 : 0;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, result);
  return {};
}
ExecResult exec_sltu(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) < src2(ctx) ? 1 : 0);
  return {};
}
ExecResult exec_xor(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) ^ src2(ctx));
  return {};
}
ExecResult exec_srl(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(src2(ctx)) & 0x3fu;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) >> shamt);
  return {};
}
ExecResult exec_sra(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(src2(ctx)) & 0x3fu;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sra64(src1(ctx), shamt));
  return {};
}
ExecResult exec_or(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) | src2(ctx));
  return {};
}
ExecResult exec_and(ExecContext* ctx) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, src1(ctx) & src2(ctx));
  return {};
}
ExecResult exec_addw(ExecContext* ctx) {
  uint32_t result =
      static_cast<uint32_t>(src1(ctx)) + static_cast<uint32_t>(src2(ctx));
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}
ExecResult exec_subw(ExecContext* ctx) {
  uint32_t result =
      static_cast<uint32_t>(src1(ctx)) - static_cast<uint32_t>(src2(ctx));
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}
ExecResult exec_sllw(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(src2(ctx)) & 0x1fu;
  uint32_t result = static_cast<uint32_t>(src1(ctx)) << shamt;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}
ExecResult exec_srlw(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(src2(ctx)) & 0x1fu;
  uint32_t result = static_cast<uint32_t>(src1(ctx)) >> shamt;
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}
ExecResult exec_sraw(ExecContext* ctx) {
  unsigned shamt = static_cast<unsigned>(src2(ctx)) & 0x1fu;
  uint32_t result = sra32(static_cast<uint32_t>(src1(ctx)), shamt);
  ideal_gpr_write(ctx->hart, ctx->operands.rd, sext32(result));
  return {};
}

ExecResult exec_fence(ExecContext*) { return {}; }
ExecResult exec_fence_tso(ExecContext*) { return {}; }
ExecResult exec_pause(ExecContext*) { return {}; }

