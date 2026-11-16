#include "dpi-c/softBackend/ideal.h"

#include <cinttypes>
#include <cstdio>
#include <cstdlib>

#include "session/options.h"
#include "session/session.h"

namespace {

constexpr uint64_t kResetPc = 0x80000000ull;

struct IdealConfig {
  bool enable = false;
};

IdealConfig config;
SoftBackend* current = nullptr;

bool same_inst(uint32_t dut, uint32_t mem) {
  if ((mem & 0x3u) != 0x3u) {
    return (dut & 0xffffu) == (mem & 0xffffu);
  }
  return dut == mem;
}

}  // namespace

SoftBackend::SoftBackend() {
  reset();
  ideal_decode_init();
  current = this;
}

SoftBackend::~SoftBackend() {
  if (current == this) {
    current = nullptr;
  }
}

void SoftBackend::reset() {
  hart_ = {};
  hart_.pc = kResetPc;
  hart_.priv = 3;
  hart_.csr.mstatus = kMstatusUxl | kMstatusSxl;
  ideal_mtime_write(0);
}

bool SoftBackend::decode(ExecContext* ctx, uint32_t raw,
                         IdealDecoded* decoded) {
  uint32_t widened = 0;
  int len = ideal_widen(raw, &widened);
  if (len == 0 || !ideal_decode(widened, decoded) || decoded->exec == nullptr) {
    return false;
  }
  ctx->len = static_cast<unsigned>(len);
  ctx->inst = widened;
  ctx->operands = decoded->operands;
  return true;
}

IsaState SoftBackend::isa_state() const {
  IsaState state{};
  for (unsigned i = 0; i < 32; ++i) {
    state.gpr[i] = ideal_gpr_read(&hart_, static_cast<uint8_t>(i));
    state.fpr[i] = hart_.fpr[i];
  }
  for (unsigned i = 0; i < 16; ++i) {
    state.pmpcfg[i] = hart_.csr.pmpcfg[i];
    state.pmpaddr[i] = hart_.csr.pmpaddr[i];
  }
  state.fcsr = hart_.fpr.fcsr;
  state.pc = hart_.pc;
  state.priv = hart_.priv;
  state.mstatus = hart_.csr.mstatus;
  state.medeleg = hart_.csr.medeleg;
  state.mideleg = hart_.csr.mideleg;
  state.mie = hart_.csr.mie;
  state.mip = hart_.csr.mip;
  state.mtvec = hart_.csr.mtvec;
  state.mscratch = hart_.csr.mscratch;
  state.mepc = hart_.csr.mepc;
  state.mcause = hart_.csr.mcause;
  state.mtval = hart_.csr.mtval;
  state.mcounteren = hart_.csr.mcounteren;
  state.mcountinhibit = hart_.csr.mcountinhibit;
  state.mcycle = hart_.csr.mcycle;
  state.minstret = hart_.csr.minstret;
  state.stvec = hart_.csr.stvec;
  state.scounteren = hart_.csr.scounteren;
  state.sscratch = hart_.csr.sscratch;
  state.sepc = hart_.csr.sepc;
  state.scause = hart_.csr.scause;
  state.stval = hart_.csr.stval;
  state.satp = hart_.csr.satp;
  return state;
}

StepResult SoftBackend::step(uint64_t pc, uint32_t inst,
                             unsigned char page_fault,
                             unsigned char access_fault) {
  if (pc != hart_.pc) {
    std::fprintf(stderr,
                 "softbackend pc mismatch: pc=0x%" PRIx64 " hart=0x%" PRIx64 "\n",
                 pc, hart_.pc);
    session_fail();
    return {};
  }
  if (page_fault == 0 && access_fault == 0 && (pc & 1ull) == 0) {
    uint32_t fetched = 0;
    if (!ideal_fetch_inst(&hart_, pc, &fetched)) {
      std::fprintf(stderr, "softbackend fetch failed: pc=0x%" PRIx64 "\n", pc);
      session_fail();
      return {};
    }
    if (!same_inst(inst, fetched)) {
      std::fprintf(stderr,
                   "softbackend inst mismatch: pc=0x%" PRIx64
                   " dut=0x%x mem=0x%x\n",
                   pc, inst, fetched);
      session_fail();
      return {};
    }
  }

  ExecContext ctx{};
  ctx.hart = &hart_;
  ctx.inst = inst;
  ctx.len = 4;

  ExecResult result{};
  if (page_fault != 0) {
    result = exec_fault(kExcInstPage, pc);
  } else if (access_fault != 0) {
    result = exec_fault(kExcInstAccess, pc);
  } else if ((pc & 1ull) != 0) {
    result = exec_fault(kExcInstMisaligned, pc);
  } else {
    IdealDecoded decoded;
    if (!decode(&ctx, inst, &decoded)) {
      result = exec_fault(kExcIllegal, inst);
    } else {
      result = decoded.exec(&ctx);
    }
  }
  // 执行
  if (result.fault) {
    hart_.pc = ideal_raise(&hart_, result.cause, result.tval);
  } else if (result.taken) {
    hart_.pc = result.target;
  } else {
    hart_.pc = pc + ctx.len;
  }

  // A committed instruction, including a trap, retires one time tick.
  ideal_mtime_tick();
  ideal_fix_sd(&hart_);
  StepResult step_result;
  if (!result.fault) {
    step_result.taken = result.taken;
    step_result.target = result.target;
  }
  step_result.mmio = result.mmio;
  return step_result;
}

void register_ideal_options(OptionSet& options) {
  options.add("ideal", &config.enable, "enable the soft backend");
}

std::unique_ptr<DpiModule> create_ideal() {
  if (!config.enable) {
    return nullptr;
  }
  return std::make_unique<SoftBackend>();
}

extern "C" void ideal_backend_inst(uint64_t pc, unsigned int inst,
                                   unsigned char page_fault,
                                   unsigned char access_fault,
                                   unsigned char* taken, uint64_t* target,
                                   IsaState* state) {
  if (current == nullptr) {
    std::fprintf(stderr, "ideal_backend_inst called without --ideal\n");
    std::abort();
  }
  StepResult step_result = current->step(pc, inst, page_fault, access_fault);
  *state = current->isa_state();
  *taken = step_result.taken ? 1 : 0;
  *target = step_result.target;
}
