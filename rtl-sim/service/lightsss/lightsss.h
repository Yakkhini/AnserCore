#ifndef SIM_SERVICE_LIGHTSSS_H
#define SIM_SERVICE_LIGHTSSS_H

#include <cstdint>
#include <deque>
#include <memory>

#include "service/service.h"

class OptionSet;
struct LightsssShare;

struct LightsssConfig {
  bool enable = false;
  uint32_t slots = 2;
  uint32_t interval = 10;
};

class LightsssService final : public SimService {
public:
  explicit LightsssService(LightsssConfig config);
  ~LightsssService() override;

  const char *name() const override { return "lightsss"; }
  bool on_posedge() override;
  void finish(bool error) override;

private:
  void check_config();
  bool spawn();
  void wakeup();
  void clear_children();

  LightsssConfig config_;
  bool is_child_;
  bool have_forked_;
  uint64_t interval_ms_;
  uint64_t last_fork_ms_;
  LightsssShare *share_;
  std::deque<int> slots_;
};

void register_lightsss_options(OptionSet &options);
std::unique_ptr<SimService> create_lightsss();
bool lightsss_enabled();

#endif
