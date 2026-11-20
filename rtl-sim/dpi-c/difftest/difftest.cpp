#include "dpi-c/difftest/difftest.h"

#include <cassert>
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
Difftest *current = nullptr;

struct Scalar {
  const char *name;
  uint64_t IsaState::*field;
};

const Scalar kScalars[] = {
    {"pc", &IsaState::pc},
    {"priv", &IsaState::priv},
    {"mstatus", &IsaState::mstatus},
    {"sstatus", &IsaState::sstatus},
    {"mepc", &IsaState::mepc},
    {"sepc", &IsaState::sepc},
    {"mtval", &IsaState::mtval},
    {"stval", &IsaState::stval},
    {"mtvec", &IsaState::mtvec},
    {"stvec", &IsaState::stvec},
    {"mcause", &IsaState::mcause},
    {"scause", &IsaState::scause},
    {"satp", &IsaState::satp},
    {"mip", &IsaState::mip},
    {"mie", &IsaState::mie},
    {"mscratch", &IsaState::mscratch},
    {"sscratch", &IsaState::sscratch},
    {"mideleg", &IsaState::mideleg},
    {"medeleg", &IsaState::medeleg},
    {"fcsr", &IsaState::fcsr},
};

void note(std::vector<std::string> *bad, const char *name, uint64_t dut,
          uint64_t ref) {
  if (dut != ref) {
    bad->push_back(name);
  }
}

void note_at(std::vector<std::string> *bad, const char *format, int index,
             uint64_t dut, uint64_t ref) {
  if (dut == ref) {
    return;
  }
  char name[32];
  std::snprintf(name, sizeof(name), format, index);
  bad->push_back(name);
}

void print_state(const char *title, const IsaState &state) {
  std::fprintf(stderr, "%s\n", title);
  for (int i = 0; i < 32; ++i) {
    std::fprintf(stderr, "  x%-2d %016" PRIx64 "\n", i, state.gpr[i]);
  }
  for (int i = 0; i < 32; ++i) {
    std::fprintf(stderr, "  f%-2d %016" PRIx64 "\n", i, state.fpr[i]);
  }
  for (const Scalar &scalar : kScalars) {
    std::fprintf(stderr, "  %-14s %016" PRIx64 "\n", scalar.name,
                 state.*(scalar.field));
  }
}

} // namespace

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

void Difftest::copy_to_ref(const IsaState &dut) {
  IsaState image = dut;
  ref_->regcpy(&image, DIFFTEST_TO_REF);
}

void Difftest::exec(uint64_t n) { ref_->exec(n); }

void Difftest::isa_compare(const IsaState &dut) {
  IsaState ref{};
  ref_->regcpy(&ref, DIFFTEST_TO_DUT);

  std::vector<std::string> bad;
  for (int i = 0; i < 32; ++i) {
    note_at(&bad, "x%d", i, dut.gpr[i], ref.gpr[i]);
    note_at(&bad, "f%d", i, dut.fpr[i], ref.fpr[i]);
  }
  for (const Scalar &scalar : kScalars) {
    note(&bad, scalar.name, dut.*(scalar.field), ref.*(scalar.field));
  }
  if (bad.empty()) {
    return;
  }

  print_state("ref", ref);
  print_state("dut", dut);
  std::fprintf(stderr, "difftest mismatch:");
  for (const std::string &name : bad) {
    std::fprintf(stderr, " %s", name.c_str());
  }
  std::fprintf(stderr, "\n");
  session_fail();
}

void register_difftest_options(OptionSet &options) {
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

namespace {

constexpr int kCommitWidth = 1;
constexpr int kCommitFields = 2;
constexpr int kCsrWords = 19;
constexpr int kArchWords =
    kCommitWidth * kCommitFields + 32 + 32 + kCsrWords + 1;

struct CommitSlot {
  bool valid = false;
  bool skip = false;
};

bool take_word(const uint64_t *words, int nwords, int *index, uint64_t *out) {
  if (*index >= nwords) {
    return false;
  }
  *out = words[*index];
  *index += 1;
  return true;
}

void handle_arch_commit(const uint64_t *words, int nwords) {
  if (nwords != kArchWords) {
    std::fprintf(stderr, "DiffArchCommit has %d words, expected %d\n", nwords,
                 kArchWords);
    session_fail();
    return;
  }
  int index = 0;
  CommitSlot commits[kCommitWidth];
  for (int slot = 0; slot < kCommitWidth; ++slot) {
    uint64_t valid = 0;
    uint64_t skip = 0;
    if (!take_word(words, nwords, &index, &valid) ||
        !take_word(words, nwords, &index, &skip)) {
      session_fail();
      return;
    }
    commits[slot].valid = valid != 0;
    commits[slot].skip = skip != 0;
  }

  IsaState dut{};
  for (int i = 0; i < 32; ++i) {
    if (!take_word(words, nwords, &index, &dut.gpr[i])) {
      session_fail();
      return;
    }
  }
  for (int i = 0; i < 32; ++i) {
    if (!take_word(words, nwords, &index, &dut.fpr[i])) {
      session_fail();
      return;
    }
  }
  uint64_t *csr[] = {
      &dut.priv,     &dut.mstatus, &dut.sstatus, &dut.mepc,  &dut.sepc,
      &dut.mtval,    &dut.stval,   &dut.mtvec,   &dut.stvec, &dut.mcause,
      &dut.scause,   &dut.satp,    &dut.mip,     &dut.mie,   &dut.mscratch,
      &dut.sscratch, &dut.mideleg, &dut.medeleg, &dut.fcsr,
  };
  static_assert(sizeof(csr) / sizeof(csr[0]) == kCsrWords, "csr count");
  for (uint64_t *field : csr) {
    if (!take_word(words, nwords, &index, field)) {
      session_fail();
      return;
    }
  }
  if (!take_word(words, nwords, &index, &dut.pc)) {
    session_fail();
    return;
  }

  bool skip = false;
  uint64_t steps = 0;
  for (const CommitSlot &slot : commits) {
    if (!slot.valid) {
      continue;
    }
    if (slot.skip) {
      skip = true;
    } else {
      steps += 1;
    }
  }
  if (skip) {
    current->copy_to_ref(dut);
    return;
  }
  if (steps == 0) {
    return;
  }
  current->exec(steps);
  current->isa_compare(dut);
}

struct ProbeHandler {
  const char *name;
  void (*handle)(const uint64_t *words, int nwords);
};

const ProbeHandler kHandlers[] = {
    {"DiffArchCommit", handle_arch_commit},
};

} // namespace

extern "C" void difftest_probe_push(const char *name, const uint64_t *words,
                                    int nwords) {
  assert(current != nullptr);
  assert(name != nullptr);
  assert(words != nullptr);
  for (const ProbeHandler &handler : kHandlers) {
    if (std::strcmp(name, handler.name) == 0) {
      handler.handle(words, nwords);
      return;
    }
  }
  std::fprintf(stderr, "unknown difftest probe %s\n", name);
  assert(false);
}
