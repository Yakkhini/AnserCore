#include "service/memory/memory.h"

#include <sys/stat.h>

#include <cerrno>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <utility>

#include "service/memory/dram/dram.h"
#include "session/options.h"

namespace {

MemoryConfig config;
MemoryService* current = nullptr;

}  // namespace

MemoryService* current_memory() { return current; }

MemoryService::MemoryService(MemoryConfig parsed) : config_(std::move(parsed)) {
  check_config();
  uint64_t ram_bytes = 0;
  if (!parse_ram_size(&ram_bytes)) {
    throw std::runtime_error("invalid --ram-size " + config_.ram_size);
  }
  words_.assign(static_cast<size_t>(ram_bytes / 8), 0);
  if (!load_image()) {
    throw std::runtime_error("failed to load image " + config_.image);
  }
#ifdef EMU_DRAMSIM3
  dram_ = create_dram(config_.ini, config_.outdir);
#else
  dram_ = create_dram();
#endif
  current = this;
}

void MemoryService::check_config() {
  if (config_.image.empty()) {
    throw std::runtime_error("missing --image");
  }
  struct stat image_stat;
  if (stat(config_.image.c_str(), &image_stat) != 0) {
    throw std::runtime_error("image path does not exist " + config_.image);
  }
  if (config_.ram_size.empty() || config_.ram_size[0] == '-') {
    throw std::runtime_error("invalid --ram-size");
  }
}

bool MemoryService::parse_ram_size(uint64_t* bytes) {
  errno = 0;
  char* end = nullptr;
  unsigned long long value = std::strtoull(config_.ram_size.c_str(), &end, 10);
  if (errno != 0 || end == config_.ram_size.c_str() || value == 0) {
    return false;
  }
  uint64_t scale = 1;
  if (std::strcmp(end, "") == 0 || std::strcmp(end, "B") == 0 ||
      std::strcmp(end, "b") == 0) {
    scale = 1;
  } else if (std::strcmp(end, "KB") == 0 || std::strcmp(end, "kb") == 0) {
    scale = 1024ull;
  } else if (std::strcmp(end, "MB") == 0 || std::strcmp(end, "mb") == 0) {
    scale = 1024ull * 1024ull;
  } else if (std::strcmp(end, "GB") == 0 || std::strcmp(end, "gb") == 0) {
    scale = 1024ull * 1024ull * 1024ull;
  } else {
    return false;
  }
  if (value > UINT64_MAX / scale) {
    return false;
  }
  uint64_t parsed = static_cast<uint64_t>(value) * scale;
  if (parsed % 8 != 0) {
    return false;
  }
  *bytes = parsed;
  return true;
}

bool MemoryService::load_image() {
  FILE* file = std::fopen(config_.image.c_str(), "rb");
  if (file == nullptr) {
    return false;
  }
  if (std::fseek(file, 0, SEEK_END) != 0) {
    std::fclose(file);
    return false;
  }
  long size = std::ftell(file);
  if (size < 0 || std::fseek(file, 0, SEEK_SET) != 0) {
    std::fclose(file);
    return false;
  }
  uint64_t bytes = static_cast<uint64_t>(words_.size()) * 8ull;
  if (static_cast<uint64_t>(size) > bytes) {
    std::fclose(file);
    return false;
  }
  size_t got =
      size == 0 ? 0
                : std::fread(words_.data(), 1, static_cast<size_t>(size), file);
  std::fclose(file);
  if (got != static_cast<size_t>(size)) {
    return false;
  }
  image_bytes_ = static_cast<uint64_t>(size);
  return true;
}

MemoryService::~MemoryService() {
  if (current == this) {
    current = nullptr;
  }
}

bool MemoryService::word_index(uint64_t offset, uint64_t* index) const {
  if (words_.empty() || offset % 8 != 0) {
    return false;
  }
  uint64_t word = offset / 8;
  if (word >= words_.size()) {
    return false;
  }
  *index = word;
  return true;
}

void MemoryService::write_word(uint64_t offset, uint64_t data,
                               unsigned char strobe) {
  uint64_t index = 0;
  if (offset % 8 != 0) {
    std::fprintf(stderr, "memory write is not 8-byte aligned: offset 0x%" PRIx64 "\n",
                 offset);
    failed_ = true;
    return;
  }
  if (!word_index(offset, &index)) {
    std::fprintf(stderr, "memory write out of range: offset 0x%" PRIx64 "\n",
                 offset);
    failed_ = true;
    return;
  }
  uint64_t mask = 0;
  for (int byte = 0; byte < 8; ++byte) {
    if ((strobe & (1u << byte)) != 0) {
      mask |= 0xffull << (8 * byte);
    }
  }
  words_[static_cast<size_t>(index)] =
      (words_[static_cast<size_t>(index)] & ~mask) | (data & mask);
}

uint64_t MemoryService::read_word(uint64_t offset) {
  uint64_t index = 0;
  if (offset % 8 != 0) {
    std::fprintf(stderr, "memory read is not 8-byte aligned: offset 0x%" PRIx64 "\n",
                 offset);
    failed_ = true;
    return 0;
  }
  if (!word_index(offset, &index)) {
    std::fprintf(stderr, "memory read out of range: offset 0x%" PRIx64 "\n",
                 offset);
    failed_ = true;
    return 0;
  }
  return words_[static_cast<size_t>(index)];
}

unsigned char MemoryService::accept(uint64_t offset, unsigned int id,
                                    unsigned char is_write) {
  constexpr uint64_t kLineBytes = 64;
  uint64_t bytes = static_cast<uint64_t>(words_.size()) * 8ull;
  if (offset % kLineBytes != 0) {
    std::fprintf(stderr,
                 "memory request is not 64-byte aligned: offset 0x%" PRIx64 "\n",
                 offset);
    failed_ = true;
    return 0;
  }
  if (bytes < kLineBytes || offset > bytes - kLineBytes) {
    std::fprintf(stderr, "memory request out of range: offset 0x%" PRIx64 "\n",
                 offset);
    failed_ = true;
    return 0;
  }
  bool write = is_write != 0;
  if (!dram_->accept(offset, write) ||
      !dram_->request(offset, write, static_cast<uint32_t>(id))) {
    return 0;
  }
  return 1;
}

uint64_t MemoryService::take_response(unsigned char is_write) {
  uint32_t id = 0;
  if (!dram_->complete(is_write != 0, &id)) {
    return 0;
  }
  return static_cast<uint64_t>(id) | (1ull << 32);
}

bool MemoryService::on_posedge() {
  dram_->tick();
  return !failed_;
}

void register_memory_options(OptionSet& options) {
#ifdef EMU_DRAMSIM3
  options.add("dramsim3-ini", &config.ini, "DRAMSim3 configuration file");
  options.add("dramsim3-outdir", &config.outdir, "DRAMSim3 output directory");
#endif
  options.add("ram-size", &config.ram_size,
              "functional RAM size, for example 256MB (default 256MB)");
  options.add("image", &config.image,
              "raw binary loaded at the start of RAM (required)");
}

std::unique_ptr<SimService> create_memory() {
  return std::make_unique<MemoryService>(config);
}
