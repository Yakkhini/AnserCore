#include "service/memory/memory.h"

#include <sys/stat.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <utility>
#include <vector>

#include "service/memory/dram/dram.h"
#include "session/options.h"

namespace {

MemoryConfig config;

}  // namespace

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
  dram_ = create_dram(config_.ini, config_.outdir);
}

void MemoryService::check_config() {
  if (config_.ini.empty()) {
    throw std::runtime_error("missing --dramsim3-ini");
  }
  FILE* file = std::fopen(config_.ini.c_str(), "r");
  if (file == nullptr) {
    throw std::runtime_error("cannot open DRAMSim3 config file " + config_.ini);
  }
  std::fclose(file);
  if (config_.outdir.empty()) {
    throw std::runtime_error("missing --dramsim3-outdir");
  }
  if (mkdir(config_.outdir.c_str(), 0755) != 0 && errno != EEXIST) {
    throw std::runtime_error("cannot create DRAMSim3 output directory " +
                             config_.outdir);
  }
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
  return got == static_cast<size_t>(size);
}

MemoryService::~MemoryService() = default;

bool MemoryService::on_posedge() {
  dram_->tick();
  return true;
}

void register_memory_options(OptionSet& options) {
  options.add("dramsim3-ini", &config.ini, "DRAMSim3 configuration file");
  options.add("dramsim3-outdir", &config.outdir, "DRAMSim3 output directory");
  options.add("ram-size", &config.ram_size,
              "functional RAM size, for example 256MB (default 256MB)");
  options.add("image", &config.image,
              "raw binary loaded at the start of RAM (required)");
}

std::unique_ptr<SimService> create_memory() {
  return std::make_unique<MemoryService>(config);
}
