#ifdef EMU_DRAMSIM3

#include <sys/stat.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>

#include "cosimulation.h"
#include "service/memory/dram/dram.h"

namespace {

class DramSim3Model final : public DramModel {
 public:
  DramSim3Model(std::string ini, std::string outdir);

  bool accept(uint64_t address, bool is_write) override {
    return model_->will_accept(address, is_write);
  }
  bool request(uint64_t address, bool is_write, uint32_t id) override;
  void tick() override { model_->tick(); }
  bool complete(bool is_write, uint32_t* id) override;

 private:
  std::unique_ptr<CoDRAMsim3> model_;
};

struct DramMeta {
  uint32_t id;
};

DramSim3Model::DramSim3Model(std::string ini, std::string outdir) {
  if (ini.empty()) {
    throw std::runtime_error("missing --dramsim3-ini");
  }
  FILE* file = std::fopen(ini.c_str(), "r");
  if (file == nullptr) {
    throw std::runtime_error("cannot open DRAMSim3 config file " + ini);
  }
  std::fclose(file);
  if (outdir.empty()) {
    throw std::runtime_error("missing --dramsim3-outdir");
  }
  if (mkdir(outdir.c_str(), 0755) != 0 && errno != EEXIST) {
    throw std::runtime_error("cannot create DRAMSim3 output directory " +
                             outdir);
  }
  model_ = std::make_unique<ComplexCoDRAMsim3>(std::move(ini), std::move(outdir));
}

bool DramSim3Model::request(uint64_t address, bool is_write, uint32_t id) {
  if (!model_->will_accept(address, is_write)) {
    return false;
  }
  auto* meta = new DramMeta;
  meta->id = id;
  auto* dram_request = new CoDRAMRequest(address, is_write, meta);
  if (!model_->add_request(dram_request)) {
    delete meta;
    delete dram_request;
    return false;
  }
  return true;
}

bool DramSim3Model::complete(bool is_write, uint32_t* id) {
  CoDRAMResponse* response =
      is_write ? model_->check_write_response() : model_->check_read_response();
  if (response == nullptr) {
    return false;
  }
  auto* meta = static_cast<DramMeta*>(response->req->meta);
  *id = meta->id;
  delete meta;
  delete response->req;
  delete response;
  return true;
}

}  // namespace

std::unique_ptr<DramModel> create_dram(const std::string& ini,
                                       const std::string& outdir) {
  return std::make_unique<DramSim3Model>(ini, outdir);
}

#endif
