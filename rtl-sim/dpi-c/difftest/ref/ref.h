#ifndef SIM_DPI_DIFFTEST_REF_H
#define SIM_DPI_DIFFTEST_REF_H

#include <cstddef>
#include <cstdint>
#include <string>

// ABI a reference shared library must export. Field order is part of that ABI.
enum { DIFFTEST_TO_DUT = 0, DIFFTEST_TO_REF = 1 };

struct IsaState {
  uint64_t gpr[32];
  uint64_t fpr[32];
  uint64_t fcsr;
  uint64_t pc;
  uint64_t priv;
  uint64_t mstatus;
  uint64_t medeleg;
  uint64_t mideleg;
  uint64_t mie;
  uint64_t mip;
  uint64_t mtvec;
  uint64_t mscratch;
  uint64_t mepc;
  uint64_t mcause;
  uint64_t mtval;
  uint64_t mcounteren;
  uint64_t mcountinhibit;
  uint64_t mcycle;
  uint64_t minstret;
  uint64_t mhartid;
  uint64_t stvec;
  uint64_t scounteren;
  uint64_t sscratch;
  uint64_t sepc;
  uint64_t scause;
  uint64_t stval;
  uint64_t satp;
  uint64_t pmpcfg[16];
  uint64_t pmpaddr[16];
};

// A constructed library has been dlopen'd and difftest_init has returned.
class RefLibrary {
 public:
  explicit RefLibrary(const std::string& path);
  ~RefLibrary();

  RefLibrary(const RefLibrary&) = delete;
  RefLibrary& operator=(const RefLibrary&) = delete;

  void memcpy(uint64_t addr, void* buf, size_t n, bool direction);
  void regcpy(IsaState* state, bool direction);
  void exec(uint64_t n);
  void raise_trap(uint64_t cause, uint64_t tval);

 private:
  void* handle_;
  void (*memcpy_)(uint64_t, void*, size_t, bool);
  void (*regcpy_)(void*, bool);
  void (*exec_)(uint64_t);
  void (*raise_trap_)(uint64_t, uint64_t);
};

#endif
