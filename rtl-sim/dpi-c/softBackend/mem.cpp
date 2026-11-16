#include "dpi-c/softBackend/exec.h"

#include <cstdint>
#include <cstdio>
#include <unordered_map>

namespace {

constexpr uint64_t kRamBase = 0x80000000ull;
constexpr uint64_t kUartBase = 0x10000000ull;
constexpr uint64_t kUartBytes = 0x100ull;
constexpr uint64_t kClintBase = 0x02000000ull;
constexpr uint64_t kClintBytes = 0x10000ull;
constexpr uint64_t kPlicBase = 0x0c000000ull;
constexpr uint64_t kPlicBytes = 0x4000000ull;

uint8_t uart_lcr = 0;
uint8_t uart_ier = 0;
// Bytes written to CLINT or PLIC. mtime is not stored here.
std::unordered_map<uint64_t, uint8_t> mmio_bytes;
constexpr uint64_t kMtimeAddr = kClintBase + 0xbff8ull;

uint64_t mtime = 0;

uint64_t mmio_load(uint64_t addr, unsigned size) {
  uint64_t value = 0;
  for (unsigned i = 0; i < size; i++) {
    auto found = mmio_bytes.find(addr + i);
    if (found != mmio_bytes.end()) {
      value |= static_cast<uint64_t>(found->second) << (8u * i);
    }
  }
  return value;
}

void mmio_store(uint64_t addr, unsigned size, uint64_t value) {
  for (unsigned i = 0; i < size; i++) {
    mmio_bytes[addr + i] = static_cast<uint8_t>(value >> (8u * i));
  }
}

extern "C" uint64_t memory_read(uint64_t offset);
extern "C" void memory_write(uint64_t offset, uint64_t data,
                             unsigned char strobe);
extern "C" void mmu_req(uint8_t priv, uint8_t mpp, uint8_t mprv, uint8_t sum,
                        uint8_t mxr, uint8_t kind, uint64_t satp, uint64_t vaddr,
                        uint64_t pmpcfg0, uint64_t pmpcfg2, uint64_t pmpaddr0,
                        uint64_t pmpaddr1, uint64_t pmpaddr2, uint64_t pmpaddr3,
                        uint64_t pmpaddr4, uint64_t pmpaddr5, uint64_t pmpaddr6,
                        uint64_t pmpaddr7, uint64_t pmpaddr8, uint64_t pmpaddr9,
                        uint64_t pmpaddr10, uint64_t pmpaddr11,
                        uint64_t pmpaddr12, uint64_t pmpaddr13,
                        uint64_t pmpaddr14, uint64_t pmpaddr15,
                        uint8_t* page_fault, uint8_t* access_fault,
                        uint64_t* paddr);

bool translate(const IdealHart* hart, uint64_t addr, uint8_t kind, uint64_t* pa,
               bool* page_fault, bool* access_fault) {
  *page_fault = false;
  *access_fault = false;
  *pa = 0;
  uint64_t cfg0 = 0;
  uint64_t cfg2 = 0;
  for (unsigned i = 0; i < 8; i++) {
    cfg0 |= static_cast<uint64_t>(hart->csr.pmpcfg[i]) << (8u * i);
    cfg2 |= static_cast<uint64_t>(hart->csr.pmpcfg[8u + i]) << (8u * i);
  }
  uint8_t page = 0;
  uint8_t access = 0;
  uint64_t translated = 0;
  const uint64_t* pmp = hart->csr.pmpaddr;
  mmu_req(hart->priv, static_cast<uint8_t>((hart->csr.mstatus >> 11) & 3u),
          (hart->csr.mstatus & kMstatusMprv) != 0 ? 1 : 0,
          (hart->csr.mstatus & kMstatusSum) != 0 ? 1 : 0,
          (hart->csr.mstatus & kMstatusMxr) != 0 ? 1 : 0, kind,
          hart->csr.satp, addr, cfg0, cfg2, pmp[0], pmp[1], pmp[2], pmp[3],
          pmp[4], pmp[5], pmp[6], pmp[7], pmp[8], pmp[9], pmp[10], pmp[11],
          pmp[12], pmp[13], pmp[14], pmp[15], &page, &access, &translated);
  if (page != 0) {
    *page_fault = true;
    return false;
  }
  if (access != 0) {
    *access_fault = true;
    return false;
  }
  *pa = translated;
  return true;
}

bool uart_access(uint64_t addr, unsigned size, bool store, uint64_t* data,
                 ExecResult* result) {
  if (addr < kUartBase || addr >= kUartBase + kUartBytes) {
    return false;
  }
  result->mmio = true;
  if (size != 1 || (addr & (static_cast<uint64_t>(size) - 1ull)) != 0) {
    *result = exec_fault(store ? kExcStoreMisaligned : kExcLoadMisaligned, addr);
    result->mmio = true;
    return true;
  }
  uint64_t offset = addr - kUartBase;
  if (offset >= 8) {
    *result = exec_fault(store ? kExcStoreAccess : kExcLoadAccess, addr);
    result->mmio = true;
    return true;
  }
  if (store) {
    uint8_t byte = static_cast<uint8_t>(*data);
    if (offset == 0 && (uart_lcr & 0x80u) == 0) {
      std::fputc(byte, stdout);
      std::fflush(stdout);
    } else if (offset == 1) {
      uart_ier = static_cast<uint8_t>(byte & 0x0fu);
    } else if (offset == 3) {
      uart_lcr = byte;
    }
    return true;
  }
  if (offset == 1) {
    *data = uart_ier;
  } else if (offset == 2) {
    *data = 0x01;
  } else if (offset == 3) {
    *data = uart_lcr;
  } else if (offset == 5) {
    *data = 0x60;
  } else {
    *data = 0;
  }
  return true;
}

// The 8-byte mtime at 0x200bff8. 4-byte accesses hit the low or high half.
bool mtime_access(uint64_t addr, unsigned size, bool store, uint64_t* data) {
  if (size == 0 || addr < kMtimeAddr || addr + size > kMtimeAddr + 8) {
    return false;
  }
  unsigned shift = static_cast<unsigned>(addr - kMtimeAddr) * 8u;
  uint64_t mask =
      size == 8 ? ~uint64_t{0} : (uint64_t{1} << (size * 8u)) - 1ull;
  if (store) {
    uint64_t wide = mask << shift;
    mtime = (mtime & ~wide) | ((*data & mask) << shift);
  } else {
    *data = (mtime >> shift) & mask;
  }
  return true;
}

bool device_window(uint64_t addr, unsigned size, bool store, uint64_t* data,
                   ExecResult* result) {
  bool clint = addr >= kClintBase && addr < kClintBase + kClintBytes;
  bool plic = addr >= kPlicBase && addr < kPlicBase + kPlicBytes;
  if (!clint && !plic) {
    return false;
  }
  uint64_t base = clint ? kClintBase : kPlicBase;
  uint64_t bytes = clint ? kClintBytes : kPlicBytes;
  result->mmio = true;
  if ((addr & (static_cast<uint64_t>(size) - 1ull)) != 0) {
    *result = exec_fault(store ? kExcStoreMisaligned : kExcLoadMisaligned, addr);
    result->mmio = true;
    return true;
  }
  if (addr - base > bytes - size) {
    *result = exec_fault(store ? kExcStoreAccess : kExcLoadAccess, addr);
    result->mmio = true;
    return true;
  }
  if (clint && mtime_access(addr, size, store, data)) {
    return true;
  }
  if (store) {
    mmio_store(addr, size, *data);
  } else {
    *data = mmio_load(addr, size);
  }
  return true;
}

bool ram_access(uint64_t addr, unsigned size, bool store, uint64_t* data) {
  if (addr < kRamBase) {
    return false;
  }
  uint64_t offset = addr - kRamBase;
  uint64_t aligned = offset & ~7ull;
  unsigned shift = static_cast<unsigned>(addr & 7u) * 8u;
  if (!store) {
    uint64_t word = memory_read(aligned);
    uint64_t mask = size == 8 ? ~0ull : (1ull << (size * 8)) - 1ull;
    *data = (word >> shift) & mask;
    return true;
  }
  unsigned char strobe =
      static_cast<unsigned char>(((1u << size) - 1u) << (addr & 7u));
  memory_write(aligned, *data << shift, strobe);
  return true;
}

ExecResult access(ExecContext* ctx, uint64_t addr, unsigned size, bool store,
                  uint64_t* data) {
  if (size != 1 && size != 2 && size != 4 && size != 8) {
    return exec_fault(store ? kExcStoreAccess : kExcLoadAccess, addr);
  }
  if ((addr & (static_cast<uint64_t>(size) - 1ull)) != 0) {
    return exec_fault(store ? kExcStoreMisaligned : kExcLoadMisaligned, addr);
  }
  uint64_t pa = 0;
  bool page = false;
  bool access_fault = false;
  if (!translate(ctx->hart, addr, store ? 2 : 1, &pa, &page, &access_fault)) {
    if (page) {
      return exec_fault(store ? kExcStorePage : kExcLoadPage, addr);
    }
    return exec_fault(store ? kExcStoreAccess : kExcLoadAccess, addr);
  }
  ExecResult result{};
  if (uart_access(pa, size, store, data, &result) ||
      device_window(pa, size, store, data, &result)) {
    if (!result.fault && store) {
      ctx->hart->lr.valid = false;
    }
    return result;
  }
  if (!ram_access(pa, size, store, data)) {
    return exec_fault(store ? kExcStoreAccess : kExcLoadAccess, addr);
  }
  if (store) {
    ctx->hart->lr.valid = false;
  }
  return {};
}

bool read_half(const IdealHart* hart, uint64_t addr, uint16_t* data) {
  uint64_t pa = 0;
  bool page = false;
  bool access_fault = false;
  if (!translate(hart, addr, 0, &pa, &page, &access_fault)) {
    return false;
  }
  uint64_t value = 0;
  if (!ram_access(pa, 2, false, &value)) {
    return false;
  }
  *data = static_cast<uint16_t>(value);
  return true;
}

}  // namespace

bool ideal_fetch_inst(const IdealHart* hart, uint64_t pc, uint32_t* inst) {
  uint16_t lo = 0;
  if (!read_half(hart, pc, &lo)) {
    return false;
  }
  if ((lo & 0x3u) != 0x3u) {
    *inst = lo;
    return true;
  }
  uint16_t hi = 0;
  if (!read_half(hart, pc + 2, &hi)) {
    return false;
  }
  *inst = static_cast<uint32_t>(lo) | (static_cast<uint32_t>(hi) << 16);
  return true;
}

uint64_t ideal_mtime() { return mtime; }

void ideal_mtime_tick() { mtime += 1; }

void ideal_mtime_write(uint64_t value) { mtime = value; }

ExecResult ideal_load_data(ExecContext* ctx, uint64_t addr, unsigned size,
                           uint64_t* value) {
  return access(ctx, addr, size, false, value);
}

ExecResult ideal_store_data(ExecContext* ctx, uint64_t addr, unsigned size,
                            uint64_t value) {
  return access(ctx, addr, size, true, &value);
}

