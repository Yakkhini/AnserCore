#include <memory>
#include <string>

#include "cosimulation.h"
#include "service/memory/dram/dram.h"

namespace {

class DramSim3Model final : public DramModel {
 public:
  DramSim3Model(const std::string& ini, const std::string& outdir)
      : model_(std::make_unique<ComplexCoDRAMsim3>(ini, outdir)) {}

  bool accept(uint64_t address, bool is_write) override {
    return model_->will_accept(address, is_write);
  }
  bool request(uint64_t address, bool is_write) override;
  void tick() override { model_->tick(); }
  bool complete(bool is_write, uint64_t* address) override;

 private:
  std::unique_ptr<CoDRAMsim3> model_;
};

bool DramSim3Model::request(uint64_t address, bool is_write) {
  if (!model_->will_accept(address, is_write)) {
    return false;
  }
  auto* dram_request = new CoDRAMRequest(address, is_write, nullptr);
  if (!model_->add_request(dram_request)) {
    delete dram_request;
    return false;
  }
  return true;
}

bool DramSim3Model::complete(bool is_write, uint64_t* address) {
  CoDRAMResponse* response =
      is_write ? model_->check_write_response() : model_->check_read_response();
  if (response == nullptr) {
    return false;
  }
  *address = response->req->address;
  delete response->req;
  delete response;
  return true;
}

}  // namespace

std::unique_ptr<DramModel> create_dram(const std::string& ini,
                                       const std::string& outdir) {
  return std::make_unique<DramSim3Model>(ini, outdir);
}
