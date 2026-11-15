#ifndef SIM_SERVICE_MEMORY_DRAM_H
#define SIM_SERVICE_MEMORY_DRAM_H

#include <cstdint>
#include <memory>
#include <string>

class DramModel {
 public:
  virtual ~DramModel() = default;
  virtual bool accept(uint64_t address, bool is_write) = 0;
  virtual bool request(uint64_t address, bool is_write) = 0;
  virtual void tick() = 0;
  virtual bool complete(bool is_write, uint64_t* address) = 0;
};

std::unique_ptr<DramModel> create_dram(const std::string& ini,
                                       const std::string& outdir);

#endif
