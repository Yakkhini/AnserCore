#ifndef SIM_SERVICE_IDEAL_EXEC_H
#define SIM_SERVICE_IDEAL_EXEC_H

#include <cstdint>

struct Gpr {
  uint64_t read(uint8_t rs) const { return rs == 0 ? 0 : x[rs]; }
  void write(uint8_t rd, uint64_t value) {
    if (rd != 0) {
      x[rd] = value;
    }
  }

  uint64_t x[32];
};

struct Fpr {
  uint64_t& operator[](unsigned i) { return v[i]; }
  const uint64_t& operator[](unsigned i) const { return v[i]; }

  uint64_t v[32];
  uint64_t fcsr;
};

// Architectural CSRs and PMP. time follows ideal_mtime(). stimecmp is
// stored and does not raise an interrupt. CLINT mtime is that same counter.
// PLIC is a plain read/write window and does not raise one.
struct Csr {
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
  uint64_t menvcfg;
  uint64_t mcycle;
  uint64_t minstret;
  uint64_t stvec;
  uint64_t scounteren;
  uint64_t senvcfg;
  uint64_t sscratch;
  uint64_t sepc;
  uint64_t scause;
  uint64_t stval;
  uint64_t satp;
  uint64_t stimecmp;
  uint64_t time;
  uint64_t pmpaddr[16];
  uint8_t pmpcfg[16];
};

struct Reservation {
  bool valid;
  uint64_t addr;
  uint8_t len;
};

// Architectural state of one hart.
struct IdealHart {
  Gpr gpr{};
  Fpr fpr{};
  Csr csr{};
  uint64_t pc = 0;
  unsigned char priv = 3;
  Reservation lr{};
};

constexpr uint64_t kExcInstMisaligned = 0;
constexpr uint64_t kExcInstAccess = 1;
constexpr uint64_t kExcIllegal = 2;
constexpr uint64_t kExcBreakpoint = 3;
constexpr uint64_t kExcLoadMisaligned = 4;
constexpr uint64_t kExcLoadAccess = 5;
constexpr uint64_t kExcStoreMisaligned = 6;
constexpr uint64_t kExcStoreAccess = 7;
constexpr uint64_t kExcEcallU = 8;
constexpr uint64_t kExcEcallS = 9;
constexpr uint64_t kExcEcallM = 11;
constexpr uint64_t kExcInstPage = 12;
constexpr uint64_t kExcLoadPage = 13;
constexpr uint64_t kExcStorePage = 15;

constexpr uint64_t kMstatusSie = 1ull << 1;
constexpr uint64_t kMstatusMie = 1ull << 3;
constexpr uint64_t kMstatusSpie = 1ull << 5;
constexpr uint64_t kMstatusMpie = 1ull << 7;
constexpr uint64_t kMstatusSpp = 1ull << 8;
constexpr uint64_t kMstatusMpp = 3ull << 11;
constexpr uint64_t kMstatusFs = 3ull << 13;
constexpr uint64_t kMstatusFsDirty = 3ull << 13;
constexpr uint64_t kMstatusMprv = 1ull << 17;
constexpr uint64_t kMstatusSum = 1ull << 18;
constexpr uint64_t kMstatusMxr = 1ull << 19;
constexpr uint64_t kMstatusTvm = 1ull << 20;
constexpr uint64_t kMstatusTw = 1ull << 21;
constexpr uint64_t kMstatusTsr = 1ull << 22;
constexpr uint64_t kMstatusUxl = 2ull << 32;
constexpr uint64_t kMstatusSxl = 2ull << 34;
constexpr uint64_t kMstatusSd = 1ull << 63;

constexpr uint64_t kMisa = (2ull << 62) | (1ull << 0) | (1ull << 2) |
                           (1ull << 3) | (1ull << 5) | (1ull << 8) |
                           (1ull << 12) | (1ull << 18) | (1ull << 20);

struct IdealOperands {
  uint8_t rd;
  uint8_t rs1;
  uint8_t rs2;
  uint8_t rs3;
  uint8_t rm;
  uint64_t imm;
};

// What one instruction did. step() applies it: a fault becomes the trap,
// taken selects target as the next pc, and mmio means a device was touched.
struct ExecResult {
  bool fault = false;
  uint64_t cause = 0;
  uint64_t tval = 0;
  bool taken = false;
  uint64_t target = 0;
  bool mmio = false;
};

inline ExecResult exec_fault(uint64_t cause, uint64_t tval) {
  ExecResult result;
  result.fault = true;
  result.cause = cause;
  result.tval = tval;
  return result;
}

inline ExecResult exec_branch(bool taken, uint64_t target) {
  ExecResult result;
  result.taken = taken;
  result.target = target;
  return result;
}

// Inputs for one instruction. inst is the widened encoding. len is 2 or 4.
struct ExecContext {
  IdealHart* hart;
  uint32_t inst;
  unsigned len;
  IdealOperands operands;
};

typedef ExecResult (*IdealExec)(ExecContext* ctx);

struct IdealDecoded {
  IdealExec exec;
  IdealOperands operands;
};

void ideal_decode_init();
bool ideal_decode(uint32_t inst, IdealDecoded* out);
// Low two bits not 11: expand a legal RVC parcel into `widened` and return 2.
// A 32-bit encoding is copied through and returns 4. Reserved parcels return 0.
int ideal_widen(uint32_t inst, uint32_t* widened);

inline uint64_t ideal_gpr_read(const IdealHart* hart, uint8_t rs) {
  return hart->gpr.read(rs);
}
inline void ideal_gpr_write(IdealHart* hart, uint8_t rd, uint64_t value) {
  hart->gpr.write(rd, value);
}

// Writes epc, cause, tval, and privilege. Returns the handler pc.
uint64_t ideal_raise(IdealHart* hart, uint64_t cause, uint64_t tval);
void ideal_fix_sd(IdealHart* hart);

// CSR ops. op 0 writes, 1 sets, 2 clears. imm selects the 5-bit zimm.
ExecResult ideal_csr(ExecContext* ctx, int op, bool imm);

// Retired instructions advance this once. CSR time and CLINT mtime read it.
uint64_t ideal_mtime();
void ideal_mtime_tick();
void ideal_mtime_write(uint64_t value);

// Raw instruction at pc. A compressed encoding is returned in the low 16 bits.
bool ideal_fetch_inst(const IdealHart* hart, uint64_t pc, uint32_t* inst);

// Data access. mmu_req translates the address. The physical address then
// hits UART, the CLINT/PLIC windows, or RAM through memory_read and
// memory_write. A fault does not write the destination or memory.
ExecResult ideal_load_data(ExecContext* ctx, uint64_t addr, unsigned size,
                           uint64_t* value);
ExecResult ideal_store_data(ExecContext* ctx, uint64_t addr, unsigned size,
                            uint64_t value);

#endif
