#include <cstdint>
#include <deque>
#include <memory>

#include "service/memory/dram/dram.h"

namespace {

class SimpleDram final : public DramModel {
public:
  bool accept(uint64_t, bool) override { return true; }

  bool request(uint64_t, bool is_write, uint32_t id) override {
    queue(is_write).push_back(id);
    return true;
  }

  void tick() override {}

  bool complete(bool is_write, uint32_t *id) override {
    std::deque<uint32_t> &pending = queue(is_write);
    if (pending.empty()) {
      return false;
    }
    *id = pending.front();
    pending.pop_front();
    return true;
  }

private:
  std::deque<uint32_t> &queue(bool is_write) {
    return is_write ? writes_ : reads_;
  }

  std::deque<uint32_t> reads_;
  std::deque<uint32_t> writes_;
};

} // namespace

#ifndef EMU_DRAMSIM3

std::unique_ptr<DramModel> create_dram() {
  return std::make_unique<SimpleDram>();
}

#endif
