#include "dpi-c/softBackend/instr/fp.h"

ExecResult exec_fld(ExecContext *ctx) { return load_fp(ctx, 8, false); }

ExecResult exec_fsd(ExecContext *ctx) { return store_fp(ctx, 8, false); }

ExecResult exec_fmadd_d(ExecContext *ctx) {
  return fma(ctx, false, false, false);
}

ExecResult exec_fmsub_d(ExecContext *ctx) {
  return fma(ctx, false, false, true);
}

ExecResult exec_fnmsub_d(ExecContext *ctx) {
  return fma(ctx, false, true, false);
}

ExecResult exec_fnmadd_d(ExecContext *ctx) {
  return fma(ctx, false, true, true);
}

ExecResult exec_fadd_d(ExecContext *ctx) {
  return binary_fp(ctx, false, [](uint64_t a, uint64_t b) {
    return f64_add(float64_t{a}, float64_t{b}).v;
  });
}

ExecResult exec_fsub_d(ExecContext *ctx) {
  return binary_fp(ctx, false, [](uint64_t a, uint64_t b) {
    return f64_sub(float64_t{a}, float64_t{b}).v;
  });
}

ExecResult exec_fmul_d(ExecContext *ctx) {
  return binary_fp(ctx, false, [](uint64_t a, uint64_t b) {
    return f64_mul(float64_t{a}, float64_t{b}).v;
  });
}

ExecResult exec_fdiv_d(ExecContext *ctx) {
  return binary_fp(ctx, false, [](uint64_t a, uint64_t b) {
    return f64_div(float64_t{a}, float64_t{b}).v;
  });
}

ExecResult exec_fsqrt_d(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, true);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result =
      f64_sqrt(float64_t{fp64_read(ctx->hart, ctx->operands.rs1)}).v;
  fp64_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, true);
  return {};
}

ExecResult exec_fsgnj_d(ExecContext *ctx) {
  return sgnj(ctx, false, false, false);
}

ExecResult exec_fsgnjn_d(ExecContext *ctx) {
  return sgnj(ctx, false, true, false);
}

ExecResult exec_fsgnjx_d(ExecContext *ctx) {
  return sgnj(ctx, false, false, true);
}

ExecResult exec_fmin_d(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result = fp_minmax(fp64_read(ctx->hart, ctx->operands.rs1),
                              fp64_read(ctx->hart, ctx->operands.rs2), false,
                              52, 11, f64_less);
  fp64_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, true);
  return {};
}

ExecResult exec_fmax_d(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result = fp_minmax(fp64_read(ctx->hart, ctx->operands.rs1),
                              fp64_read(ctx->hart, ctx->operands.rs2), true, 52,
                              11, f64_less);
  fp64_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, true);
  return {};
}

ExecResult exec_fcvt_w_d(ExecContext *ctx) {
  return to_int(ctx, true, [&] {
    return static_cast<uint64_t>(static_cast<int32_t>(
        f64_to_i32(float64_t{fp64_read(ctx->hart, ctx->operands.rs1)},
                   softfloat_roundingMode, true)));
  });
}

ExecResult exec_fcvt_wu_d(ExecContext *ctx) {
  return to_int(ctx, true, [&] {
    return static_cast<uint64_t>(
        f64_to_ui32(float64_t{fp64_read(ctx->hart, ctx->operands.rs1)},
                    softfloat_roundingMode, true));
  });
}

ExecResult exec_fcvt_l_d(ExecContext *ctx) {
  return to_int(ctx, false, [&] {
    return static_cast<uint64_t>(
        f64_to_i64(float64_t{fp64_read(ctx->hart, ctx->operands.rs1)},
                   softfloat_roundingMode, true));
  });
}

ExecResult exec_fcvt_lu_d(ExecContext *ctx) {
  return to_int(ctx, false, [&] {
    return static_cast<uint64_t>(
        f64_to_ui64(float64_t{fp64_read(ctx->hart, ctx->operands.rs1)},
                    softfloat_roundingMode, true));
  });
}

ExecResult exec_fcvt_d_w(ExecContext *ctx) {
  return from_int(ctx, false, [](uint64_t value) {
    return i32_to_f64(static_cast<int32_t>(value)).v;
  });
}

ExecResult exec_fcvt_d_wu(ExecContext *ctx) {
  return from_int(ctx, false, [](uint64_t value) {
    return ui32_to_f64(static_cast<uint32_t>(value)).v;
  });
}

ExecResult exec_fcvt_d_l(ExecContext *ctx) {
  return from_int(ctx, false, [](uint64_t value) {
    return i64_to_f64(static_cast<int64_t>(value)).v;
  });
}

ExecResult exec_fcvt_d_lu(ExecContext *ctx) {
  return from_int(ctx, false,
                  [](uint64_t value) { return ui64_to_f64(value).v; });
}

ExecResult exec_fcvt_s_d(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, true);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result =
      f64_to_f32(float64_t{fp64_read(ctx->hart, ctx->operands.rs1)}).v;
  fp32_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, true);
  return {};
}

ExecResult exec_fcvt_d_s(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, true);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result = f32_to_f64(float32_t{static_cast<uint32_t>(
                                   fp32_read(ctx->hart, ctx->operands.rs1))})
                        .v;
  fp64_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, true);
  return {};
}

ExecResult exec_feq_d(ExecContext *ctx) { return cmp(ctx, false, eq64); }

ExecResult exec_flt_d(ExecContext *ctx) { return cmp(ctx, false, lt64); }

ExecResult exec_fle_d(ExecContext *ctx) { return cmp(ctx, false, le64); }

ExecResult exec_fclass_d(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  ideal_gpr_write(ctx->hart, ctx->operands.rd,
                  fp_class(fp64_read(ctx->hart, ctx->operands.rs1), 52, 11));
  return {};
}

ExecResult exec_fmv_x_d(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  ideal_gpr_write(ctx->hart, ctx->operands.rd,
                  ctx->hart->fpr[ctx->operands.rs1]);
  return {};
}

ExecResult exec_fmv_d_x(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  fp64_write(ctx->hart, ctx->operands.rd,
             ideal_gpr_read(ctx->hart, ctx->operands.rs1));
  fp_dirty(ctx->hart);
  return {};
}
