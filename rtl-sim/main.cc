/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

#include "session/options.h"
#include "session/session.h"

int main(int argc, char** argv) {
  OptionSet options;
  register_platform_options(options);

  ParseStatus parsed = options.parse(argc, argv);
  if (parsed == ParseStatus::Help) {
    options.print(argc > 0 ? argv[0] : "sim");
    return 0;
  }
  if (parsed == ParseStatus::Error) {
    return 1;
  }

  Session session(argc, argv);
  session.run();
  session.finish();
  return session.state() != SessionState::kNormal;
}
