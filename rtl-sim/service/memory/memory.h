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

 private:
  void check_config();
  bool parse_ram_size(uint64_t* bytes);
  bool load_image();

  MemoryConfig config_;
  std::vector<uint64_t> words_;
  std::unique_ptr<DramModel> dram_;
};

void register_memory_options(OptionSet& options);
std::unique_ptr<SimService> create_memory();

#endif
