#include <cstdint>

#include "dpi-c/softBackend/exec.h"

#define INSTR(name, pattern, fmt) ExecResult exec_##name(ExecContext *ctx);
#include "instr/a/a.def"
#include "instr/d/d.def"
#include "instr/f/f.def"
#include "instr/i/i.def"
#include "instr/m/m.def"
#include "instr/priv/priv.def"
#undef INSTR

namespace {

struct Entry {
  const char *pattern;
  const char *fmt;
  IdealExec exec;
  uint64_t key;
  uint64_t mask;
  uint8_t shift;
};

#define INSTR(name, pattern, fmt) {pattern, #fmt, exec_##name, 0, 0, 0},
Entry table[] = {
#include "instr/a/a.def"
#include "instr/d/d.def"
#include "instr/f/f.def"
#include "instr/i/i.def"
#include "instr/m/m.def"
#include "instr/priv/priv.def"
#undef INSTR
};

constexpr int kCount = sizeof(table) / sizeof(table[0]);
bool ready = false;

uint64_t bits(uint32_t value, unsigned hi, unsigned lo) {
  return (static_cast<uint64_t>(value) >> lo) & (~0ull >> (63 - (hi - lo)));
}

uint64_t sext(uint64_t value, unsigned len) {
  uint64_t sign = 1ull << (len - 1);
  uint64_t mask = (len == 64) ? ~0ull : (1ull << len) - 1ull;
  value &= mask;
  if ((value & sign) != 0) {
    return value | ~mask;
  }
  return value;
}

uint64_t imm_i(uint32_t inst) { return sext(bits(inst, 31, 20), 12); }

uint64_t imm_s(uint32_t inst) {
  return sext((bits(inst, 31, 25) << 5) | bits(inst, 11, 7), 12);
}

uint64_t imm_b(uint32_t inst) {
  uint64_t raw = (bits(inst, 31, 31) << 12) | (bits(inst, 7, 7) << 11) |
                 (bits(inst, 30, 25) << 5) | (bits(inst, 11, 8) << 1);
  return sext(raw, 13);
}

uint64_t imm_u(uint32_t inst) {
  return static_cast<uint64_t>(static_cast<int32_t>(inst & 0xfffff000u));
}

uint64_t imm_j(uint32_t inst) {
  uint64_t raw = (bits(inst, 31, 31) << 20) | (bits(inst, 19, 12) << 12) |
                 (bits(inst, 20, 20) << 11) | (bits(inst, 30, 21) << 1);
  return sext(raw, 21);
}

uint64_t format_imm(uint32_t inst, const char *fmt) {
  if (fmt[0] == 'I' && fmt[1] == '\0') {
    return imm_i(inst);
  }
  if (fmt[0] == 'S' && fmt[1] == '\0') {
    return imm_s(inst);
  }
  if (fmt[0] == 'B' && fmt[1] == '\0') {
    return imm_b(inst);
  }
  if (fmt[0] == 'U' && fmt[1] == '\0') {
    return imm_u(inst);
  }
  if (fmt[0] == 'J' && fmt[1] == '\0') {
    return imm_j(inst);
  }
  // CSR address in inst[31:20]. Not sign-extended.
  if (fmt[0] == 'C' && fmt[1] == '\0') {
    return bits(inst, 31, 20);
  }
  return 0;
}

void pattern_decode(const char *pat, uint64_t *key, uint64_t *mask,
                    uint8_t *shift) {
  uint64_t k = 0;
  uint64_t m = 0;
  int trailing_wild = 0;
  for (const char *p = pat; *p != '\0'; p++) {
    if (*p == ' ') {
      continue;
    }
    if (*p == '0') {
      k <<= 1;
      m = (m << 1) | 1u;
      trailing_wild = 0;
    } else if (*p == '1') {
      k = (k << 1) | 1u;
      m = (m << 1) | 1u;
      trailing_wild = 0;
    } else {
      k <<= 1;
      m <<= 1;
      trailing_wild++;
    }
  }
  *shift = static_cast<uint8_t>(trailing_wild);
  *key = k >> trailing_wild;
  *mask = m >> trailing_wild;
}

} // namespace

void ideal_decode_init() {
  if (ready) {
    return;
  }
  for (int i = 0; i < kCount; i++) {
    pattern_decode(table[i].pattern, &table[i].key, &table[i].mask,
                   &table[i].shift);
  }
  ready = true;
}

bool ideal_decode(uint32_t inst, IdealDecoded *out) {
  ideal_decode_init();
  out->exec = nullptr;
  for (int i = 0; i < kCount; i++) {
    const Entry &entry = table[i];
    if (((inst >> entry.shift) & entry.mask) != entry.key) {
      continue;
    }
    out->exec = entry.exec;
    out->operands.rd = static_cast<uint8_t>(bits(inst, 11, 7));
    out->operands.rs1 = static_cast<uint8_t>(bits(inst, 19, 15));
    out->operands.rs2 = static_cast<uint8_t>(bits(inst, 24, 20));
    out->operands.rs3 = static_cast<uint8_t>(bits(inst, 31, 27));
    out->operands.rm = static_cast<uint8_t>(bits(inst, 14, 12));
    out->operands.imm = format_imm(inst, entry.fmt);
    return true;
  }
  return false;
}
