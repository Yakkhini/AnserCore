#include "dpi-c/softBackend/exec.h"

namespace {

uint64_t bits(uint32_t value, unsigned hi, unsigned lo) {
  return (static_cast<uint64_t>(value) >> lo) & (~0ull >> (63 - (hi - lo)));
}

uint64_t bit(uint32_t value, unsigned index) {
  return bits(value, index, index);
}

int32_t sext(uint32_t value, unsigned len) {
  uint32_t sign = 1u << (len - 1);
  uint32_t mask = (len == 32) ? 0xffffffffu : (1u << len) - 1u;
  value &= mask;
  if ((value & sign) != 0) {
    return static_cast<int32_t>(value | ~mask);
  }
  return static_cast<int32_t>(value);
}

uint32_t imm_op(unsigned opcode, unsigned funct3, unsigned rd, unsigned rs1,
                int32_t imm) {
  return ((static_cast<uint32_t>(imm) & 0xfffu) << 20) | (rs1 << 15) |
         (funct3 << 12) | (rd << 7) | opcode;
}

uint32_t reg_op(unsigned opcode, unsigned funct3, unsigned funct7, unsigned rd,
                unsigned rs1, unsigned rs2) {
  return (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) |
         (rd << 7) | opcode;
}

uint32_t store(unsigned funct3, unsigned rs1, unsigned rs2, unsigned imm) {
  return ((imm >> 5) << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) |
         ((imm & 31u) << 7) | 0x23u;
}

uint32_t branch(unsigned funct3, unsigned rs1, uint32_t imm) {
  return (((imm >> 12) & 1u) << 31) | (((imm >> 5) & 63u) << 25) | (rs1 << 15) |
         (funct3 << 12) | (((imm >> 1) & 15u) << 8) |
         (((imm >> 11) & 1u) << 7) | 0x63u;
}

uint32_t jump(uint32_t imm) {
  return (((imm >> 20) & 1u) << 31) | (((imm >> 1) & 1023u) << 21) |
         (((imm >> 11) & 1u) << 20) | (((imm >> 12) & 255u) << 12) | 0x6fu;
}

enum Kind {
  kAddi4spn,
  kFld,
  kFsd,
  kFldsp,
  kFsdsp,
  kLw,
  kLd,
  kSw,
  kSd,
  kNop,
  kAddi,
  kAddiw,
  kLi,
  kAddi16sp,
  kLui,
  kSrli,
  kSrai,
  kAndi,
  kSub,
  kXor,
  kOr,
  kAnd,
  kSubw,
  kAddw,
  kJ,
  kBeqz,
  kBnez,
  kSlli,
  kLwsp,
  kLdsp,
  kJr,
  kMv,
  kEbreak,
  kJalr,
  kAdd,
  kSwsp,
  kSdsp
};

struct Entry {
  uint16_t key;
  uint16_t mask;
  Kind kind;
};

constexpr Entry kEntries[] = {
    {0x0000, 0xe003, kAddi4spn}, {0x2000, 0xe003, kFld},
    {0xa000, 0xe003, kFsd},      {0x2002, 0xe003, kFldsp},
    {0xa002, 0xe003, kFsdsp},    {0x4000, 0xe003, kLw},
    {0x6000, 0xe003, kLd},       {0xc000, 0xe003, kSw},
    {0xe000, 0xe003, kSd},       {0x0001, 0xffff, kNop},
    {0x0001, 0xe003, kAddi},     {0x2001, 0xe003, kAddiw},
    {0x4001, 0xe003, kLi},       {0x6101, 0xef83, kAddi16sp},
    {0x6001, 0xe003, kLui},      {0x8001, 0xec03, kSrli},
    {0x8401, 0xec03, kSrai},     {0x8801, 0xec03, kAndi},
    {0x8c01, 0xfc63, kSub},      {0x8c21, 0xfc63, kXor},
    {0x8c41, 0xfc63, kOr},       {0x8c61, 0xfc63, kAnd},
    {0x9c01, 0xfc63, kSubw},     {0x9c21, 0xfc63, kAddw},
    {0xa001, 0xe003, kJ},        {0xc001, 0xe003, kBeqz},
    {0xe001, 0xe003, kBnez},     {0x0002, 0xe003, kSlli},
    {0x4002, 0xe003, kLwsp},     {0x6002, 0xe003, kLdsp},
    {0x8002, 0xf07f, kJr},       {0x8002, 0xf003, kMv},
    {0x9002, 0xffff, kEbreak},   {0x9002, 0xf07f, kJalr},
    {0x9002, 0xf003, kAdd},      {0xc002, 0xe003, kSwsp},
    {0xe002, 0xe003, kSdsp},
};

bool expand(uint16_t parcel, Kind kind, uint32_t *out) {
  unsigned rd = static_cast<unsigned>(bits(parcel, 11, 7));
  unsigned rs2 = static_cast<unsigned>(bits(parcel, 6, 2));
  unsigned rp = 8u + static_cast<unsigned>(bits(parcel, 9, 7));
  unsigned r2p = 8u + static_cast<unsigned>(bits(parcel, 4, 2));
  unsigned ci = (static_cast<unsigned>(bit(parcel, 12)) << 5) |
                static_cast<unsigned>(bits(parcel, 6, 2));
  int32_t simm = sext(ci, 6);
  unsigned word_offset = (static_cast<unsigned>(bits(parcel, 12, 10)) << 3) |
                         (static_cast<unsigned>(bit(parcel, 6)) << 2) |
                         (static_cast<unsigned>(bit(parcel, 5)) << 6);
  unsigned double_offset = (static_cast<unsigned>(bits(parcel, 12, 10)) << 3) |
                           (static_cast<unsigned>(bits(parcel, 6, 5)) << 6);
  unsigned imm = 0;
  uint32_t expanded = 0;
  switch (kind) {
  case kAddi4spn:
    imm = (static_cast<unsigned>(bits(parcel, 12, 11)) << 4) |
          (static_cast<unsigned>(bits(parcel, 10, 7)) << 6) |
          (static_cast<unsigned>(bit(parcel, 6)) << 2) |
          (static_cast<unsigned>(bit(parcel, 5)) << 3);
    if (imm == 0)
      return false;
    expanded = imm_op(0x13, 0, r2p, 2, static_cast<int32_t>(imm));
    break;
  case kFld:
    expanded = imm_op(0x07, 3, r2p, rp, static_cast<int32_t>(double_offset));
    break;
  case kFsd:
    expanded = (store(3, rp, r2p, double_offset) & ~0x7fu) | 0x27u;
    break;
  case kFldsp:
    imm = (static_cast<unsigned>(bit(parcel, 12)) << 5) |
          (static_cast<unsigned>(bits(parcel, 6, 5)) << 3) |
          (static_cast<unsigned>(bits(parcel, 4, 2)) << 6);
    expanded = imm_op(0x07, 3, rd, 2, static_cast<int32_t>(imm));
    break;
  case kFsdsp:
    imm = (static_cast<unsigned>(bits(parcel, 12, 10)) << 3) |
          (static_cast<unsigned>(bits(parcel, 9, 7)) << 6);
    expanded = (store(3, 2, rs2, imm) & ~0x7fu) | 0x27u;
    break;
  case kLw:
    expanded = imm_op(0x03, 2, r2p, rp, static_cast<int32_t>(word_offset));
    break;
  case kLd:
    expanded = imm_op(0x03, 3, r2p, rp, static_cast<int32_t>(double_offset));
    break;
  case kSw:
    expanded = store(2, rp, r2p, word_offset);
    break;
  case kSd:
    expanded = store(3, rp, r2p, double_offset);
    break;
  case kNop:
    expanded = 0x13;
    break;
  case kAddi:
    expanded = imm_op(0x13, 0, rd, rd, simm);
    break;
  case kAddiw:
    if (rd == 0)
      return false;
    expanded = imm_op(0x1b, 0, rd, rd, simm);
    break;
  case kLi:
    expanded = imm_op(0x13, 0, rd, 0, simm);
    break;
  case kAddi16sp:
    imm = (static_cast<unsigned>(bit(parcel, 12)) << 9) |
          (static_cast<unsigned>(bit(parcel, 6)) << 4) |
          (static_cast<unsigned>(bit(parcel, 5)) << 6) |
          (static_cast<unsigned>(bits(parcel, 4, 3)) << 7) |
          (static_cast<unsigned>(bit(parcel, 2)) << 5);
    if (imm == 0)
      return false;
    expanded = imm_op(0x13, 0, 2, 2, sext(imm, 10));
    break;
  case kLui:
    if (ci == 0 || rd == 0)
      return false;
    expanded =
        ((static_cast<uint32_t>(simm) & 0xfffffu) << 12) | (rd << 7) | 0x37u;
    break;
  case kSrli:
    if (ci == 0)
      return false;
    expanded = imm_op(0x13, 5, rp, rp, static_cast<int32_t>(ci));
    break;
  case kSrai:
    if (ci == 0)
      return false;
    expanded = imm_op(0x13, 5, rp, rp, static_cast<int32_t>(0x400u | ci));
    break;
  case kAndi:
    expanded = imm_op(0x13, 7, rp, rp, simm);
    break;
  case kSub:
    expanded = reg_op(0x33, 0, 0x20, rp, rp, r2p);
    break;
  case kXor:
    expanded = reg_op(0x33, 4, 0, rp, rp, r2p);
    break;
  case kOr:
    expanded = reg_op(0x33, 6, 0, rp, rp, r2p);
    break;
  case kAnd:
    expanded = reg_op(0x33, 7, 0, rp, rp, r2p);
    break;
  case kSubw:
    expanded = reg_op(0x3b, 0, 0x20, rp, rp, r2p);
    break;
  case kAddw:
    expanded = reg_op(0x3b, 0, 0, rp, rp, r2p);
    break;
  case kJ:
    imm = (static_cast<unsigned>(bit(parcel, 12)) << 11) |
          (static_cast<unsigned>(bit(parcel, 11)) << 4) |
          (static_cast<unsigned>(bits(parcel, 10, 9)) << 8) |
          (static_cast<unsigned>(bit(parcel, 8)) << 10) |
          (static_cast<unsigned>(bit(parcel, 7)) << 6) |
          (static_cast<unsigned>(bit(parcel, 6)) << 7) |
          (static_cast<unsigned>(bits(parcel, 5, 3)) << 1) |
          (static_cast<unsigned>(bit(parcel, 2)) << 5);
    expanded = jump(static_cast<uint32_t>(sext(imm, 12)));
    break;
  case kBeqz:
  case kBnez:
    imm = (static_cast<unsigned>(bit(parcel, 12)) << 8) |
          (static_cast<unsigned>(bits(parcel, 11, 10)) << 3) |
          (static_cast<unsigned>(bits(parcel, 6, 5)) << 6) |
          (static_cast<unsigned>(bits(parcel, 4, 3)) << 1) |
          (static_cast<unsigned>(bit(parcel, 2)) << 5);
    expanded =
        branch(kind == kBeqz ? 0 : 1, rp, static_cast<uint32_t>(sext(imm, 9)));
    break;
  case kSlli:
    if (ci == 0)
      return false;
    expanded = imm_op(0x13, 1, rd, rd, static_cast<int32_t>(ci));
    break;
  case kLwsp:
    if (rd == 0)
      return false;
    imm = (static_cast<unsigned>(bit(parcel, 12)) << 5) |
          (static_cast<unsigned>(bits(parcel, 6, 4)) << 2) |
          (static_cast<unsigned>(bits(parcel, 3, 2)) << 6);
    expanded = imm_op(0x03, 2, rd, 2, static_cast<int32_t>(imm));
    break;
  case kLdsp:
    if (rd == 0)
      return false;
    imm = (static_cast<unsigned>(bit(parcel, 12)) << 5) |
          (static_cast<unsigned>(bits(parcel, 6, 5)) << 3) |
          (static_cast<unsigned>(bits(parcel, 4, 2)) << 6);
    expanded = imm_op(0x03, 3, rd, 2, static_cast<int32_t>(imm));
    break;
  case kJr:
    if (rd == 0)
      return false;
    expanded = imm_op(0x67, 0, 0, rd, 0);
    break;
  case kMv:
    if (rd == 0 || rs2 == 0)
      return false;
    expanded = reg_op(0x33, 0, 0, rd, 0, rs2);
    break;
  case kEbreak:
    expanded = 0x00100073u;
    break;
  case kJalr:
    if (rd == 0)
      return false;
    expanded = imm_op(0x67, 0, 1, rd, 0);
    break;
  case kAdd:
    if (rd == 0 || rs2 == 0)
      return false;
    expanded = reg_op(0x33, 0, 0, rd, rd, rs2);
    break;
  case kSwsp:
    imm = (static_cast<unsigned>(bits(parcel, 12, 9)) << 2) |
          (static_cast<unsigned>(bits(parcel, 8, 7)) << 6);
    expanded = store(2, 2, rs2, imm);
    break;
  case kSdsp:
    imm = (static_cast<unsigned>(bits(parcel, 12, 10)) << 3) |
          (static_cast<unsigned>(bits(parcel, 9, 7)) << 6);
    expanded = store(3, 2, rs2, imm);
    break;
  }
  *out = expanded;
  return true;
}

} // namespace

int ideal_widen(uint32_t inst, uint32_t *widened) {
  if ((inst & 0x3u) == 0x3u) {
    *widened = inst;
    return 4;
  }
  uint16_t parcel = static_cast<uint16_t>(inst);
  for (const Entry &entry : kEntries) {
    if ((parcel & entry.mask) == entry.key) {
      if (!expand(parcel, entry.kind, widened)) {
        return 0;
      }
      return 2;
    }
  }
  return 0;
}
