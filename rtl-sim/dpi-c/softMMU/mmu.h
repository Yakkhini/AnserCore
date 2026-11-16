#ifndef SIM_DPI_MMU_H
#define SIM_DPI_MMU_H

#include <array>
#include <cstdint>
#include <memory>

#include "dpi-c/dpi.h"

class OptionSet;

// One soft MMU for the process. Privilege, satp, and PMP change on each
// translation, so they are arguments of translate(), not constructor state.
// A reserved satp MODE stops. It is not a page fault.

enum class SatpMode : unsigned char {
  kBare = 0,
  kSv39 = 8,
  kSv48 = 9,
  kSv57 = 10,
};

enum class AccessType : unsigned char {
  kFetch = 0,
  kLoad = 1,
  kStore = 2,
};

enum class Priv : unsigned char {
  kUser = 0,
  kSuper = 1,
  kMachine = 3,
};

enum class PmpMatch : unsigned char {
  kOff = 0,
  kTor = 1,
  kNa4 = 2,
  kNapot = 3,
};

struct PmpCfg {
  bool l;
  PmpMatch a;
  bool x;
  bool w;
  bool r;
};

struct PmpEntry {
  PmpCfg cfg;
  uint64_t addr = 0;
};

// Built from one satp CSR. MODE must be Bare, Sv39, Sv48, or Sv57.
// Anything else stops. This class does not report a page fault.
class Satp {
 public:
  Satp();
  explicit Satp(uint64_t raw);

  SatpMode mode() const { return mode_; }
  uint64_t ppn() const { return ppn_; }
  uint16_t asid() const { return asid_; }
  int levels() const;

 private:
  SatpMode mode_;
  uint64_t ppn_;
  uint16_t asid_;
};

class Pmp {
 public:
  static constexpr unsigned kNumEntries = 16;
  static constexpr unsigned kgranule = 0;
  static_assert(kNumEntries == 0 || kNumEntries == 16 || kNumEntries == 64,
                "RISC-V PMP supports only 0, 16, or 64 entries.");

  Pmp() = default;
  // cfg packs 8 entries per RV64 pmpcfg word. addr is pmpaddr[0..).
  explicit Pmp(const uint64_t* cfg, const uint64_t* addr);
  // True when [addr, addr + len) may do this access.
  bool check(uint64_t addr, unsigned len, AccessType access, Priv priv) const;

 private:
  std::array<PmpEntry, kNumEntries> entries_{};
  unsigned granule = kgranule;
  unsigned numEntries = kNumEntries;
};

struct MmuReq {
  unsigned char priv;
  unsigned char mpp;
  unsigned char mprv;
  unsigned char sum;
  unsigned char mxr;
  unsigned char kind;
  uint64_t satp;
  uint64_t pmpcfg0;
  uint64_t pmpcfg2;
  uint64_t pmpaddr[16];
  uint64_t vaddr;
};

struct MmuResp {
  uint64_t paddr;
  unsigned char page_fault;
  unsigned char access_fault;
};

struct MmuConfig {
  bool enable = false;
};

// Created once with the DPI module. CSRs stay on the call that supplied them.
class Mmu {
 public:
  MmuResp translate(const MmuReq& req) const;
  bool check_pmp(uint8_t priv, uint8_t mpp, uint8_t mprv, uint8_t kind,
                 uint64_t addr, unsigned len, const uint64_t cfg[2],
                 const uint64_t pmpaddr[16]) const;

 private:
  struct Access {
    Priv priv;
    Priv mpp;
    bool mprv;
    bool sum;
    bool mxr;
    AccessType kind;
    Satp satp;
    Pmp pmp;
  };

  MmuResp translate(const Access& access, uint64_t vaddr) const;
  bool pmp_ok(const Pmp& pmp, uint64_t addr, unsigned len, AccessType access,
              Priv priv) const;
  Priv effective(Priv priv, Priv mpp, bool mprv, AccessType access) const;
  MmuResp physical(uint64_t addr) const;
  MmuResp fault(unsigned char page, unsigned char access) const;
  MmuResp walk(const Access& access, uint64_t vaddr) const;
  bool read_pte(const Pmp& pmp, uint64_t addr, uint64_t* pte) const;
};

class SoftMmu final : public DpiModule {
 public:
  SoftMmu();
  ~SoftMmu() override;

  MmuResp translate(const MmuReq& req);
  bool check_pmp(uint8_t priv, uint8_t mpp, uint8_t mprv, uint8_t kind,
                 uint64_t addr, unsigned len, const uint64_t cfg[2],
                 const uint64_t pmpaddr[16]);

 private:
  Mmu mmu_;
};

void register_mmu_options(OptionSet& options);
std::unique_ptr<DpiModule> create_mmu();

#endif
