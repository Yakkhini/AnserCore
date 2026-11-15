#include "session/session.h"

Session::Session(int argc, char** argv)
    : state_(SessionState::kNormal),
      platform_(create_platform(argc, argv)),
      services_(create_services()) {}

void Session::run() {
  state_ = SessionState::kRunning;
  while (!platform_->get_finsh() && state_ == SessionState::kRunning) {
    platform_->tick();
    for (const std::unique_ptr<SimService>& service : services_) {
      if (!service->on_posedge()) {
        state_ = SessionState::kError;
        return;
      }
    }
  }
  if (platform_->get_error()) {
    state_ = SessionState::kError;
  } else if (state_ == SessionState::kRunning) {
    state_ = SessionState::kNormal;
  }
}

void Session::finish() {
  platform_->finish();
  bool error = state_ == SessionState::kError;
  for (const std::unique_ptr<SimService>& service : services_) {
    service->finish(error);
  }
  switch (state_) {
    case SessionState::kError:
      break;
    case SessionState::kNormal:
      break;
    case SessionState::kRunning:
      break;
  }
}
