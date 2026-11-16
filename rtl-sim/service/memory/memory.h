#ifndef SIM_SERVICE_MEMORY_H
#define SIM_SERVICE_MEMORY_H

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "service/service.h"

class DramModel;
class OptionSet;

struct MemoryConfig {
  std::string ini;
  std::string outdir;
  std::string ram_size = "256MB";
  std::string image;
};

class MemoryService final : public SimService {
 public:
  explicit MemoryService(MemoryConfig config);
  ~MemoryService() override;

  const char* name() const override { return "memory"; }
  bool on_posedge() override;

  // offset is the first byte of a 64-byte line.
  unsigned char accept(uint64_t offset, unsigned int id,
                       unsigned char is_write);
  // offset is an 8-byte-aligned word.
  void write_word(uint64_t offset, uint64_t data, unsigned char strobe);
  uint64_t read_word(uint64_t offset);
  // 0 means no response. Otherwise bit 32 is set and bits [31:0] are the id.
  uint64_t take_response(unsigned char is_write);
  const uint8_t* image() const {
    return reinterpret_cast<const uint8_t*>(words_.data());
  }
  uint64_t image_bytes() const { return image_bytes_; }

 private:
  void check_config();
  bool parse_ram_size(uint64_t* bytes);
  bool load_image();
  bool word_index(uint64_t offset, uint64_t* index) const;

  MemoryConfig config_;
  std::vector<uint64_t> words_;
  uint64_t image_bytes_ = 0;
  std::unique_ptr<DramModel> dram_;
  bool failed_ = false;
};

MemoryService* current_memory();

void register_memory_options(OptionSet& options);
std::unique_ptr<SimService> create_memory();

#endif
