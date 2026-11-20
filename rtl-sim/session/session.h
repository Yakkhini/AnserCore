#ifndef SIM_SESSION_SESSION_H
#define SIM_SESSION_SESSION_H

#include <memory>
#include <vector>

#include "dpi-c/dpi.h"
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
  ~Session();

  void run();
  void finish();
  SessionState state() const { return state_; }

  friend void session_fail();

private:
  SessionState state_;
  // Memory must exist before difftest copies the loaded image into the ref.
  std::vector<std::unique_ptr<SimService>> services_;
  std::vector<std::unique_ptr<DpiModule>> dpi_;
  std::unique_ptr<Platform> platform_;
};

void session_fail();

#endif
