#include "session/session.h"

namespace {

Session* current_session = nullptr;

}  // namespace

Session::Session(int argc, char** argv)
    : state_(SessionState::kNormal),
      services_(create_services()),
      dpi_(create_dpi()),
      platform_(create_platform(argc, argv)) {
  current_session = this;
}

Session::~Session() { current_session = nullptr; }

void session_fail() {
  if (current_session != nullptr) {
    current_session->state_ = SessionState::kError;
  }
}

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
