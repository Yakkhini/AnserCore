#include "session/session.h"

Session::Session(int argc, char** argv)
    : state_(SessionState::kNormal), platform_(create_platform(argc, argv)) {}

void Session::run() {
  state_ = SessionState::kRunning;
  while (!platform_->get_finsh() && state_ == SessionState::kRunning) {
    platform_->tick();
  }
  if (platform_->get_error()) {
    state_ = SessionState::kError;
  } else if (state_ == SessionState::kRunning) {
    state_ = SessionState::kNormal;
  }
}

void Session::finish() {
  platform_->finish();
  switch (state_) {
    case SessionState::kError:
      break;
    case SessionState::kNormal:
      break;
    case SessionState::kRunning:
      break;
  }
}
