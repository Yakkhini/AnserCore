/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

#include <cstdint>

#include "service/memory/memory.h"

extern "C" unsigned char memory_request(uint64_t offset, unsigned int id,
                                        unsigned char is_write) {
  return current_memory()->accept(offset, id, is_write);
}

extern "C" void memory_write(uint64_t offset, uint64_t data,
                             unsigned char strobe) {
  current_memory()->write_word(offset, data, strobe);
}

extern "C" uint64_t memory_read(uint64_t offset) {
  return current_memory()->read_word(offset);
}

extern "C" uint64_t memory_response(unsigned char is_write) {
  return current_memory()->take_response(is_write);
}
