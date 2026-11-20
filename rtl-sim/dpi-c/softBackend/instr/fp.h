#ifndef SIM_SERVICE_IDEAL_INSTR_FP_H
#define SIM_SERVICE_IDEAL_INSTR_FP_H

#include "dpi-c/softBackend/exec.h"

#include "softfloat.h"

namespace {

constexpr unsigned kFp32Bits = 32;
constexpr uint64_t kFp32Sign = 1ull << 31;
constexpr uint64_t kFp64Sign = 1ull << 63;
constexpr uint64_t kFp32Box = ~0ull << 32;

inline bool fp_on(const IdealHart *hart) {
  return (hart->csr.mstatus & kMstatusFs) != 0;
}

inline void fp_dirty(IdealHart *hart) {
  hart->csr.mstatus = (hart->csr.mstatus & ~kMstatusFs) | kMstatusFsDirty;
}

inline bool fp_round(ExecContext *ctx) {
  unsigned rm = ctx->operands.rm;
  if (rm == 7) {
    rm = static_cast<unsigned>((ctx->hart->fpr.fcsr >> 5) & 7u);
  }
  if (rm > 4) {
    return false;
  }
  softfloat_roundingMode = static_cast<uint_fast8_t>(rm);
  softfloat_detectTininess = softfloat_tininess_afterRounding;
  return true;
}

inline void fp_begin() { softfloat_exceptionFlags = 0; }

inline void fp_finish(ExecContext *ctx, bool writes_fpr) {
  uint_fast8_t flags =
      static_cast<uint_fast8_t>(softfloat_exceptionFlags & 0x1fu);
  if (flags != 0) {
    ctx->hart->fpr.fcsr |= flags;
    fp_dirty(ctx->hart);
  }
  if (writes_fpr) {
    fp_dirty(ctx->hart);
  }
  softfloat_exceptionFlags = 0;
}

inline ExecResult fp_require(ExecContext *ctx, bool rounding) {
  if (!fp_on(ctx->hart) || (rounding && !fp_round(ctx))) {
    return exec_fault(kExcIllegal, ctx->inst);
  }
  return {};
}

inline uint64_t fp32_read(const IdealHart *hart, uint8_t rs) {
  uint64_t value = hart->fpr[rs];
  if ((value >> kFp32Bits) != 0xffffffffu) {
    return 0x7fc00000ull;
  }
  return static_cast<uint32_t>(value);
}

inline void fp32_write(IdealHart *hart, uint8_t rd, uint64_t value) {
  hart->fpr[rd] = kFp32Box | static_cast<uint32_t>(value);
}

inline uint64_t fp64_read(const IdealHart *hart, uint8_t rs) {
  return hart->fpr[rs];
}

inline void fp64_write(IdealHart *hart, uint8_t rd, uint64_t value) {
  hart->fpr[rd] = value;
}

inline uint64_t fp_sign(uint64_t bits, unsigned mant, unsigned exp) {
  return 1ull << (mant + exp);
}

inline uint64_t fp_exp_mask(unsigned mant, unsigned exp) {
  return ((1ull << exp) - 1ull) << mant;
}

inline uint64_t fp_mant_mask(unsigned mant) { return (1ull << mant) - 1ull; }

inline bool fp_nan(uint64_t bits, unsigned mant, unsigned exp) {
  uint64_t exp_mask = fp_exp_mask(mant, exp);
  return (bits & exp_mask) == exp_mask && (bits & fp_mant_mask(mant)) != 0;
}

inline bool fp_snan(uint64_t bits, unsigned mant, unsigned exp) {
  return fp_nan(bits, mant, exp) && (bits & (1ull << (mant - 1))) == 0;
}

inline uint64_t fp_canonical(unsigned mant, unsigned exp) {
  return fp_exp_mask(mant, exp) | (1ull << (mant - 1));
}

inline uint64_t fp_class(uint64_t bits, unsigned mant, unsigned exp) {
  uint64_t exp_max = (1ull << exp) - 1ull;
  bool sign = (bits & fp_sign(bits, mant, exp)) != 0;
  uint64_t exponent = (bits >> mant) & exp_max;
  uint64_t fraction = bits & fp_mant_mask(mant);
  int cls = 0;
  if (exponent == exp_max) {
    if (fraction != 0) {
      cls = fp_snan(bits, mant, exp) ? 8 : 9;
    } else {
      cls = sign ? 0 : 7;
    }
  } else if (exponent == 0) {
    if (fraction != 0) {
      cls = sign ? 2 : 5;
    } else {
      cls = sign ? 3 : 4;
    }
  } else {
    cls = sign ? 1 : 6;
  }
  return 1ull << cls;
}

inline uint64_t fp_minmax(uint64_t lhs, uint64_t rhs, bool max, unsigned mant,
                          unsigned exp, bool (*less)(uint64_t, uint64_t)) {
  uint64_t sign = fp_sign(lhs, mant, exp);
  if (fp_snan(lhs, mant, exp) || fp_snan(rhs, mant, exp)) {
    softfloat_exceptionFlags |= softfloat_flag_invalid;
  }
  bool lhs_nan = fp_nan(lhs, mant, exp);
  bool rhs_nan = fp_nan(rhs, mant, exp);
  if (lhs_nan && rhs_nan) {
    return fp_canonical(mant, exp);
  }
  if (lhs_nan) {
    return rhs;
  }
  if (rhs_nan) {
    return lhs;
  }
  if ((lhs & ~sign) == 0 && (rhs & ~sign) == 0) {
    return max ? (lhs & rhs) : (lhs | rhs);
  }
  bool lhs_less = less(lhs, rhs);
  return max ? (lhs_less ? rhs : lhs) : (lhs_less ? lhs : rhs);
}

inline uint64_t inject(uint64_t lhs, uint64_t rhs, uint64_t sign, bool negate,
                       bool xor_sign) {
  uint64_t selected = xor_sign ? (lhs ^ rhs) : (negate ? ~rhs : rhs);
  return (lhs & ~sign) | (selected & sign);
}

inline bool f32_less(uint64_t lhs, uint64_t rhs) {
  return f32_lt_quiet(float32_t{static_cast<uint32_t>(lhs)},
                      float32_t{static_cast<uint32_t>(rhs)});
}

inline bool f64_less(uint64_t lhs, uint64_t rhs) {
  return f64_lt_quiet(float64_t{lhs}, float64_t{rhs});
}

inline ExecResult load_fp(ExecContext *ctx, unsigned size, bool single) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  uint64_t addr =
      ideal_gpr_read(ctx->hart, ctx->operands.rs1) + ctx->operands.imm;
  uint64_t raw = 0;
  fault = ideal_load_data(ctx, addr, size, &raw);
  if (fault.fault)
    return fault;
  if (single) {
    fp32_write(ctx->hart, ctx->operands.rd, raw);
  } else {
    fp64_write(ctx->hart, ctx->operands.rd, raw);
  }
  fp_dirty(ctx->hart);
  return fault;
}

inline ExecResult store_fp(ExecContext *ctx, unsigned size, bool single) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  uint64_t addr =
      ideal_gpr_read(ctx->hart, ctx->operands.rs1) + ctx->operands.imm;
  uint64_t raw = ctx->hart->fpr[ctx->operands.rs2];
  if (single) {
    raw = static_cast<uint32_t>(raw);
  }
  return ideal_store_data(ctx, addr, size, raw);
}

template <typename Op>
inline ExecResult binary_fp(ExecContext *ctx, bool single, Op op) {
  ExecResult fault = fp_require(ctx, true);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t lhs = single ? fp32_read(ctx->hart, ctx->operands.rs1)
                        : fp64_read(ctx->hart, ctx->operands.rs1);
  uint64_t rhs = single ? fp32_read(ctx->hart, ctx->operands.rs2)
                        : fp64_read(ctx->hart, ctx->operands.rs2);
  uint64_t result = op(lhs, rhs);
  if (single) {
    fp32_write(ctx->hart, ctx->operands.rd, result);
  } else {
    fp64_write(ctx->hart, ctx->operands.rd, result);
  }
  fp_finish(ctx, true);
  return {};
}

inline ExecResult fma(ExecContext *ctx, bool single, bool neg_product,
                      bool neg_addend) {
  ExecResult fault = fp_require(ctx, true);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t sign = single ? kFp32Sign : kFp64Sign;
  uint64_t lhs = single ? fp32_read(ctx->hart, ctx->operands.rs1)
                        : fp64_read(ctx->hart, ctx->operands.rs1);
  uint64_t rhs = single ? fp32_read(ctx->hart, ctx->operands.rs2)
                        : fp64_read(ctx->hart, ctx->operands.rs2);
  uint64_t add = single ? fp32_read(ctx->hart, ctx->operands.rs3)
                        : fp64_read(ctx->hart, ctx->operands.rs3);
  if (neg_product) {
    lhs ^= sign;
  }
  if (neg_addend) {
    add ^= sign;
  }
  uint64_t result = 0;
  if (single) {
    result = f32_mulAdd(float32_t{static_cast<uint32_t>(lhs)},
                        float32_t{static_cast<uint32_t>(rhs)},
                        float32_t{static_cast<uint32_t>(add)})
                 .v;
    fp32_write(ctx->hart, ctx->operands.rd, result);
  } else {
    result = f64_mulAdd(float64_t{lhs}, float64_t{rhs}, float64_t{add}).v;
    fp64_write(ctx->hart, ctx->operands.rd, result);
  }
  fp_finish(ctx, true);
  return {};
}

template <typename Op>
inline ExecResult to_int(ExecContext *ctx, bool word, Op op) {
  ExecResult fault = fp_require(ctx, true);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result = op();
  if (word) {
    result = static_cast<uint64_t>(
        static_cast<int64_t>(static_cast<int32_t>(result)));
  }
  ideal_gpr_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, false);
  return {};
}

template <typename Op>
inline ExecResult from_int(ExecContext *ctx, bool single, Op op) {
  ExecResult fault = fp_require(ctx, true);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t value = ideal_gpr_read(ctx->hart, ctx->operands.rs1);
  uint64_t result = op(value);
  if (single) {
    fp32_write(ctx->hart, ctx->operands.rd, result);
  } else {
    fp64_write(ctx->hart, ctx->operands.rd, result);
  }
  fp_finish(ctx, true);
  return {};
}

inline ExecResult cmp(ExecContext *ctx, bool single,
                      bool (*op)(uint64_t, uint64_t)) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t lhs = single ? fp32_read(ctx->hart, ctx->operands.rs1)
                        : fp64_read(ctx->hart, ctx->operands.rs1);
  uint64_t rhs = single ? fp32_read(ctx->hart, ctx->operands.rs2)
                        : fp64_read(ctx->hart, ctx->operands.rs2);
  ideal_gpr_write(ctx->hart, ctx->operands.rd, op(lhs, rhs) ? 1 : 0);
  fp_finish(ctx, false);
  return {};
}

inline ExecResult sgnj(ExecContext *ctx, bool single, bool negate,
                       bool xor_sign) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  uint64_t sign = single ? kFp32Sign : kFp64Sign;
  uint64_t lhs = single ? fp32_read(ctx->hart, ctx->operands.rs1)
                        : fp64_read(ctx->hart, ctx->operands.rs1);
  uint64_t rhs = single ? fp32_read(ctx->hart, ctx->operands.rs2)
                        : fp64_read(ctx->hart, ctx->operands.rs2);
  uint64_t result = inject(lhs, rhs, sign, negate, xor_sign);
  if (single) {
    fp32_write(ctx->hart, ctx->operands.rd, result);
  } else {
    fp64_write(ctx->hart, ctx->operands.rd, result);
  }
  fp_dirty(ctx->hart);
  return {};
}

inline bool eq32(uint64_t lhs, uint64_t rhs) {
  return f32_eq(float32_t{static_cast<uint32_t>(lhs)},
                float32_t{static_cast<uint32_t>(rhs)});
}
inline bool lt32(uint64_t lhs, uint64_t rhs) {
  return f32_lt(float32_t{static_cast<uint32_t>(lhs)},
                float32_t{static_cast<uint32_t>(rhs)});
}
inline bool le32(uint64_t lhs, uint64_t rhs) {
  return f32_le(float32_t{static_cast<uint32_t>(lhs)},
                float32_t{static_cast<uint32_t>(rhs)});
}
inline bool eq64(uint64_t lhs, uint64_t rhs) {
  return f64_eq(float64_t{lhs}, float64_t{rhs});
}
inline bool lt64(uint64_t lhs, uint64_t rhs) {
  return f64_lt(float64_t{lhs}, float64_t{rhs});
}
inline bool le64(uint64_t lhs, uint64_t rhs) {
  return f64_le(float64_t{lhs}, float64_t{rhs});
}

} // namespace

#endif
