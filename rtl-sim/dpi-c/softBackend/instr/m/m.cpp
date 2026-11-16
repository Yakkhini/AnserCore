#include "dpi-c/softBackend/exec.h"

namespace {

uint64_t sext32(uint64_t value) {
  return static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(value)));
}

ExecResult write_rd(ExecContext* ctx, uint64_t value) {
  ideal_gpr_write(ctx->hart, ctx->operands.rd, value);
  return {};
}

}  // namespace

ExecResult exec_mul(ExecContext* ctx) {
  return write_rd(ctx, ideal_gpr_read(ctx->hart, ctx->operands.rs1) *
                           ideal_gpr_read(ctx->hart, ctx->operands.rs2));
}

ExecResult exec_mulh(ExecContext* ctx) {
  __int128 product = static_cast<__int128>(
                         static_cast<int64_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1))) *
                     static_cast<__int128>(
                         static_cast<int64_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs2)));
  return write_rd(ctx, static_cast<uint64_t>(product >> 64));
}

ExecResult exec_mulhu(ExecContext* ctx) {
  unsigned __int128 product =
      static_cast<unsigned __int128>(ideal_gpr_read(ctx->hart, ctx->operands.rs1)) *
      static_cast<unsigned __int128>(ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  return write_rd(ctx, static_cast<uint64_t>(product >> 64));
}

ExecResult exec_mulhsu(ExecContext* ctx) {
  __int128 product = static_cast<__int128>(
                         static_cast<int64_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1))) *
                     static_cast<__int128>(static_cast<unsigned __int128>(
                         ideal_gpr_read(ctx->hart, ctx->operands.rs2)));
  return write_rd(ctx, static_cast<uint64_t>(product >> 64));
}

ExecResult exec_mulw(ExecContext* ctx) {
  uint32_t product = static_cast<uint32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1)) *
                     static_cast<uint32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  return write_rd(ctx, sext32(product));
}

ExecResult exec_div(ExecContext* ctx) {
  int64_t lhs = static_cast<int64_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1));
  int64_t rhs = static_cast<int64_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  if (rhs == 0) {
    return write_rd(ctx, ~0ull);
  }
  if (lhs == static_cast<int64_t>(1ull << 63) && rhs == -1) {
    return write_rd(ctx, static_cast<uint64_t>(lhs));
  }
  return write_rd(ctx, static_cast<uint64_t>(lhs / rhs));
}

ExecResult exec_divu(ExecContext* ctx) {
  uint64_t lhs = ideal_gpr_read(ctx->hart, ctx->operands.rs1);
  uint64_t rhs = ideal_gpr_read(ctx->hart, ctx->operands.rs2);
  return write_rd(ctx, rhs == 0 ? ~0ull : lhs / rhs);
}

ExecResult exec_rem(ExecContext* ctx) {
  int64_t lhs = static_cast<int64_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1));
  int64_t rhs = static_cast<int64_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  if (rhs == 0) {
    return write_rd(ctx, static_cast<uint64_t>(lhs));
  }
  if (lhs == static_cast<int64_t>(1ull << 63) && rhs == -1) {
    return write_rd(ctx, 0);
  }
  return write_rd(ctx, static_cast<uint64_t>(lhs % rhs));
}

ExecResult exec_remu(ExecContext* ctx) {
  uint64_t lhs = ideal_gpr_read(ctx->hart, ctx->operands.rs1);
  uint64_t rhs = ideal_gpr_read(ctx->hart, ctx->operands.rs2);
  return write_rd(ctx, rhs == 0 ? lhs : lhs % rhs);
}

ExecResult exec_divw(ExecContext* ctx) {
  int32_t lhs = static_cast<int32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1));
  int32_t rhs = static_cast<int32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  if (rhs == 0) {
    return write_rd(ctx, ~0ull);
  }
  if (lhs == static_cast<int32_t>(0x80000000u) && rhs == -1) {
    return write_rd(ctx, sext32(static_cast<uint32_t>(lhs)));
  }
  return write_rd(ctx, sext32(static_cast<uint32_t>(lhs / rhs)));
}

ExecResult exec_divuw(ExecContext* ctx) {
  uint32_t lhs = static_cast<uint32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1));
  uint32_t rhs = static_cast<uint32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  uint32_t result = rhs == 0 ? 0xffffffffu : lhs / rhs;
  return write_rd(ctx, sext32(result));
}

ExecResult exec_remw(ExecContext* ctx) {
  int32_t lhs = static_cast<int32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1));
  int32_t rhs = static_cast<int32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  if (rhs == 0) {
    return write_rd(ctx, sext32(static_cast<uint32_t>(lhs)));
  }
  if (lhs == static_cast<int32_t>(0x80000000u) && rhs == -1) {
    return write_rd(ctx, 0);
  }
  return write_rd(ctx, sext32(static_cast<uint32_t>(lhs % rhs)));
}

ExecResult exec_remuw(ExecContext* ctx) {
  uint32_t lhs = static_cast<uint32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs1));
  uint32_t rhs = static_cast<uint32_t>(ideal_gpr_read(ctx->hart, ctx->operands.rs2));
  uint32_t result = rhs == 0 ? lhs : lhs % rhs;
  return write_rd(ctx, sext32(result));
}
