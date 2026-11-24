/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

#ifndef SIM_SESSION_SESSION_H
#define SIM_SESSION_SESSION_H

#include <memory>

#include "platform/platform.h"

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
  std::unique_ptr<Platform> platform_;
};

#endif
