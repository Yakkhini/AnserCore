#include "dpi-c/difftest/difftest.h"

#include <cinttypes>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "session/options.h"
#include "session/session.h"

namespace {

DifftestConfig config;
Difftest* current = nullptr;

struct Scalar {
  const char* name;
  uint64_t IsaState::* field;
};

const Scalar kScalars[] = {
    {"fcsr", &IsaState::fcsr},
    {"pc", &IsaState::pc},
    {"priv", &IsaState::priv},
    {"mstatus", &IsaState::mstatus},
    {"medeleg", &IsaState::medeleg},
    {"mideleg", &IsaState::mideleg},
    {"mie", &IsaState::mie},
    {"mip", &IsaState::mip},
    {"mtvec", &IsaState::mtvec},
    {"mscratch", &IsaState::mscratch},
    {"mepc", &IsaState::mepc},
    {"mcause", &IsaState::mcause},
    {"mtval", &IsaState::mtval},
    {"mcounteren", &IsaState::mcounteren},
    {"mcountinhibit", &IsaState::mcountinhibit},
    {"mcycle", &IsaState::mcycle},
    {"minstret", &IsaState::minstret},
    {"mhartid", &IsaState::mhartid},
    {"stvec", &IsaState::stvec},
    {"scounteren", &IsaState::scounteren},
    {"sscratch", &IsaState::sscratch},
    {"sepc", &IsaState::sepc},
    {"scause", &IsaState::scause},
    {"stval", &IsaState::stval},
    {"satp", &IsaState::satp},
};

void note(std::vector<std::string>* bad, const char* name, uint64_t dut,
          uint64_t ref) {
  if (dut != ref) {
    bad->push_back(name);
  }
}

void note_at(std::vector<std::string>* bad, const char* format, int index,
             uint64_t dut, uint64_t ref) {
  if (dut == ref) {
    return;
  }
  char name[32];
  std::snprintf(name, sizeof(name), format, index);
  bad->push_back(name);
}

void print_state(const char* title, const IsaState& state) {
  std::fprintf(stderr, "%s\n", title);
  for (int i = 0; i < 32; ++i) {
    std::fprintf(stderr, "  x%-2d %016" PRIx64 "\n", i, state.gpr[i]);
  }
  for (int i = 0; i < 32; ++i) {
    std::fprintf(stderr, "  f%-2d %016" PRIx64 "\n", i, state.fpr[i]);
  }
  for (const Scalar& scalar : kScalars) {
    std::fprintf(stderr, "  %-14s %016" PRIx64 "\n", scalar.name,
                 state.*(scalar.field));
  }
  for (int i = 0; i < 16; ++i) {
    std::fprintf(stderr, "  pmpcfg%-8d %016" PRIx64 "\n", i, state.pmpcfg[i]);
    std::fprintf(stderr, "  pmpaddr%-7d %016" PRIx64 "\n", i, state.pmpaddr[i]);
  }
}

}  // namespace

Difftest::Difftest(DifftestConfig parsed)
    : config_(std::move(parsed)),
      ref_(std::make_unique<RefLibrary>(config_.ref_path)) {
  current = this;
}

Difftest::~Difftest() {
  if (current == this) {
    current = nullptr;
  }
}

void Difftest::copy_to_ref(const IsaState& dut) {
  IsaState image = dut;
  ref_->regcpy(&image, DIFFTEST_TO_REF);
}

void Difftest::isa_compare(const IsaState& dut) {
  IsaState ref{};
  ref_->regcpy(&ref, DIFFTEST_TO_DUT);

  std::vector<std::string> bad;
  for (int i = 0; i < 32; ++i) {
    note_at(&bad, "x%d", i, dut.gpr[i], ref.gpr[i]);
    note_at(&bad, "f%d", i, dut.fpr[i], ref.fpr[i]);
  }
  for (const Scalar& scalar : kScalars) {
    note(&bad, scalar.name, dut.*(scalar.field), ref.*(scalar.field));
  }
  for (int i = 0; i < 16; ++i) {
    note_at(&bad, "pmpcfg%d", i, dut.pmpcfg[i], ref.pmpcfg[i]);
    note_at(&bad, "pmpaddr%d", i, dut.pmpaddr[i], ref.pmpaddr[i]);
  }
  if (bad.empty()) {
    return;
  }

  print_state("ref", ref);
  print_state("dut", dut);
  std::fprintf(stderr, "difftest mismatch:");
  for (const std::string& name : bad) {
    std::fprintf(stderr, " %s", name.c_str());
  }
  std::fprintf(stderr, "\n");
  session_fail();
}

void register_difftest_options(OptionSet& options) {
  options.add("difftest", &config.enable,
              "enable difftest and load the reference");
  options.add("difftest-ref", &config.ref_path,
              "reference shared library loaded with dlopen");
}

std::unique_ptr<DpiModule> create_difftest() {
  if (!config.enable) {
    return nullptr;
  }
  return std::make_unique<Difftest>(config);
}

IsaState make_dut(const uint64_t gpr[32], const uint64_t fpr[32], uint64_t fcsr,
                  uint64_t pc, uint64_t priv, uint64_t mstatus,
                  uint64_t medeleg, uint64_t mideleg, uint64_t mie,
                  uint64_t mip, uint64_t mtvec, uint64_t mscratch,
                  uint64_t mepc, uint64_t mcause, uint64_t mtval,
                  uint64_t mcounteren, uint64_t mcountinhibit, uint64_t mcycle,
                  uint64_t minstret, uint64_t mhartid, uint64_t stvec,
                  uint64_t scounteren, uint64_t sscratch, uint64_t sepc,
                  uint64_t scause, uint64_t stval, uint64_t satp,
                  const uint64_t pmpcfg[16], const uint64_t pmpaddr[16]) {
  IsaState dut{};
  std::memcpy(dut.gpr, gpr, sizeof(dut.gpr));
  std::memcpy(dut.fpr, fpr, sizeof(dut.fpr));
  dut.fcsr = fcsr;
  dut.pc = pc;
  dut.priv = priv;
  dut.mstatus = mstatus;
  dut.medeleg = medeleg;
  dut.mideleg = mideleg;
  dut.mie = mie;
  dut.mip = mip;
  dut.mtvec = mtvec;
  dut.mscratch = mscratch;
  dut.mepc = mepc;
  dut.mcause = mcause;
  dut.mtval = mtval;
  dut.mcounteren = mcounteren;
  dut.mcountinhibit = mcountinhibit;
  dut.mcycle = mcycle;
  dut.minstret = minstret;
  dut.mhartid = mhartid;
  dut.stvec = stvec;
  dut.scounteren = scounteren;
  dut.sscratch = sscratch;
  dut.sepc = sepc;
  dut.scause = scause;
  dut.stval = stval;
  dut.satp = satp;
  std::memcpy(dut.pmpcfg, pmpcfg, sizeof(dut.pmpcfg));
  std::memcpy(dut.pmpaddr, pmpaddr, sizeof(dut.pmpaddr));
  return dut;
}

extern "C" void difftest_isa(
    const uint64_t gpr[32], const uint64_t fpr[32], uint64_t fcsr, uint64_t pc,
    uint64_t priv, uint64_t mstatus, uint64_t medeleg, uint64_t mideleg,
    uint64_t mie, uint64_t mip, uint64_t mtvec, uint64_t mscratch,
    uint64_t mepc, uint64_t mcause, uint64_t mtval, uint64_t mcounteren,
    uint64_t mcountinhibit, uint64_t mcycle, uint64_t minstret,
    uint64_t mhartid, uint64_t stvec, uint64_t scounteren, uint64_t sscratch,
    uint64_t sepc, uint64_t scause, uint64_t stval, uint64_t satp,
    const uint64_t pmpcfg[16], const uint64_t pmpaddr[16], unsigned char skip) {
  if (current == nullptr) {
    return;
  }
  IsaState dut =
      make_dut(gpr, fpr, fcsr, pc, priv, mstatus, medeleg, mideleg, mie, mip,
               mtvec, mscratch, mepc, mcause, mtval, mcounteren, mcountinhibit,
               mcycle, minstret, mhartid, stvec, scounteren, sscratch, sepc,
               scause, stval, satp, pmpcfg, pmpaddr);
  if (skip) {
    current->copy_to_ref(dut);
    return;
  }
  current->isa_compare(dut);
}
