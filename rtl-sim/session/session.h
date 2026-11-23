#ifndef SIM_SESSION_SESSION_H
#define SIM_SESSION_SESSION_H

#include <memory>
#include <vector>

#include "platform/platform.h"
#include "service/service.h"

enum class SessionState {
  kRunning,
  kNormal,
  kError,
};

class Session {
public:
  explicit Session(int argc, char **argv);

  void run();
  void finish();
  SessionState state() const { return state_; }

private:
  SessionState state_;
  std::vector<std::unique_ptr<SimService>> services_;
  std::unique_ptr<Platform> platform_;
};

#endif
