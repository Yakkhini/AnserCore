#include "dpi-c/softBackend/instr/fp.h"

ExecResult exec_flw(ExecContext *ctx) { return load_fp(ctx, 4, true); }

ExecResult exec_fsw(ExecContext *ctx) { return store_fp(ctx, 4, true); }

ExecResult exec_fmadd_s(ExecContext *ctx) {
  return fma(ctx, true, false, false);
}

ExecResult exec_fmsub_s(ExecContext *ctx) {
  return fma(ctx, true, false, true);
}

ExecResult exec_fnmsub_s(ExecContext *ctx) {
  return fma(ctx, true, true, false);
}

ExecResult exec_fnmadd_s(ExecContext *ctx) {
  return fma(ctx, true, true, true);
}

ExecResult exec_fadd_s(ExecContext *ctx) {
  return binary_fp(ctx, true, [](uint64_t a, uint64_t b) {
    return static_cast<uint64_t>(f32_add(float32_t{static_cast<uint32_t>(a)},
                                         float32_t{static_cast<uint32_t>(b)})
                                     .v);
  });
}

ExecResult exec_fsub_s(ExecContext *ctx) {
  return binary_fp(ctx, true, [](uint64_t a, uint64_t b) {
    return static_cast<uint64_t>(f32_sub(float32_t{static_cast<uint32_t>(a)},
                                         float32_t{static_cast<uint32_t>(b)})
                                     .v);
  });
}

ExecResult exec_fmul_s(ExecContext *ctx) {
  return binary_fp(ctx, true, [](uint64_t a, uint64_t b) {
    return static_cast<uint64_t>(f32_mul(float32_t{static_cast<uint32_t>(a)},
                                         float32_t{static_cast<uint32_t>(b)})
                                     .v);
  });
}

ExecResult exec_fdiv_s(ExecContext *ctx) {
  return binary_fp(ctx, true, [](uint64_t a, uint64_t b) {
    return static_cast<uint64_t>(f32_div(float32_t{static_cast<uint32_t>(a)},
                                         float32_t{static_cast<uint32_t>(b)})
                                     .v);
  });
}

ExecResult exec_fsqrt_s(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, true);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result = f32_sqrt(float32_t{static_cast<uint32_t>(
                                 fp32_read(ctx->hart, ctx->operands.rs1))})
                        .v;
  fp32_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, true);
  return {};
}

ExecResult exec_fsgnj_s(ExecContext *ctx) {
  return sgnj(ctx, true, false, false);
}

ExecResult exec_fsgnjn_s(ExecContext *ctx) {
  return sgnj(ctx, true, true, false);
}

ExecResult exec_fsgnjx_s(ExecContext *ctx) {
  return sgnj(ctx, true, false, true);
}

ExecResult exec_fmin_s(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result = fp_minmax(fp32_read(ctx->hart, ctx->operands.rs1),
                              fp32_read(ctx->hart, ctx->operands.rs2), false,
                              23, 8, f32_less);
  fp32_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, true);
  return {};
}

ExecResult exec_fmax_s(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  fp_begin();
  uint64_t result =
      fp_minmax(fp32_read(ctx->hart, ctx->operands.rs1),
                fp32_read(ctx->hart, ctx->operands.rs2), true, 23, 8, f32_less);
  fp32_write(ctx->hart, ctx->operands.rd, result);
  fp_finish(ctx, true);
  return {};
}

ExecResult exec_fcvt_w_s(ExecContext *ctx) {
  return to_int(ctx, true, [&] {
    return static_cast<uint64_t>(static_cast<int32_t>(
        f32_to_i32(float32_t{static_cast<uint32_t>(
                       fp32_read(ctx->hart, ctx->operands.rs1))},
                   softfloat_roundingMode, true)));
  });
}

ExecResult exec_fcvt_wu_s(ExecContext *ctx) {
  return to_int(ctx, true, [&] {
    return static_cast<uint64_t>(
        f32_to_ui32(float32_t{static_cast<uint32_t>(
                        fp32_read(ctx->hart, ctx->operands.rs1))},
                    softfloat_roundingMode, true));
  });
}

ExecResult exec_fcvt_l_s(ExecContext *ctx) {
  return to_int(ctx, false, [&] {
    return static_cast<uint64_t>(
        f32_to_i64(float32_t{static_cast<uint32_t>(
                       fp32_read(ctx->hart, ctx->operands.rs1))},
                   softfloat_roundingMode, true));
  });
}

ExecResult exec_fcvt_lu_s(ExecContext *ctx) {
  return to_int(ctx, false, [&] {
    return static_cast<uint64_t>(
        f32_to_ui64(float32_t{static_cast<uint32_t>(
                        fp32_read(ctx->hart, ctx->operands.rs1))},
                    softfloat_roundingMode, true));
  });
}

ExecResult exec_fcvt_s_w(ExecContext *ctx) {
  return from_int(ctx, true, [](uint64_t value) {
    return static_cast<uint64_t>(i32_to_f32(static_cast<int32_t>(value)).v);
  });
}

ExecResult exec_fcvt_s_wu(ExecContext *ctx) {
  return from_int(ctx, true, [](uint64_t value) {
    return static_cast<uint64_t>(ui32_to_f32(static_cast<uint32_t>(value)).v);
  });
}

ExecResult exec_fcvt_s_l(ExecContext *ctx) {
  return from_int(ctx, true, [](uint64_t value) {
    return static_cast<uint64_t>(i64_to_f32(static_cast<int64_t>(value)).v);
  });
}

ExecResult exec_fcvt_s_lu(ExecContext *ctx) {
  return from_int(ctx, true, [](uint64_t value) {
    return static_cast<uint64_t>(ui64_to_f32(value).v);
  });
}

ExecResult exec_feq_s(ExecContext *ctx) { return cmp(ctx, true, eq32); }

ExecResult exec_flt_s(ExecContext *ctx) { return cmp(ctx, true, lt32); }

ExecResult exec_fle_s(ExecContext *ctx) { return cmp(ctx, true, le32); }

ExecResult exec_fclass_s(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  ideal_gpr_write(ctx->hart, ctx->operands.rd,
                  fp_class(fp32_read(ctx->hart, ctx->operands.rs1), 23, 8));
  return {};
}

ExecResult exec_fmv_x_w(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  uint32_t raw = static_cast<uint32_t>(ctx->hart->fpr[ctx->operands.rs1]);
  ideal_gpr_write(
      ctx->hart, ctx->operands.rd,
      static_cast<uint64_t>(static_cast<int64_t>(static_cast<int32_t>(raw))));
  return {};
}

ExecResult exec_fmv_w_x(ExecContext *ctx) {
  ExecResult fault = fp_require(ctx, false);
  if (fault.fault)
    return fault;
  fp32_write(ctx->hart, ctx->operands.rd,
             ideal_gpr_read(ctx->hart, ctx->operands.rs1));
  fp_dirty(ctx->hart);
  return {};
}
