/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

#ifndef SIM_PLATFORM_PLATFORM_H
#define SIM_PLATFORM_PLATFORM_H

#include <memory>

class OptionSet;

class Platform {
public:
  virtual ~Platform() = default;
  virtual void tick() = 0;
  virtual bool get_finsh() = 0;
  virtual bool get_error() = 0;
  virtual void finish() = 0;
};

void register_platform_options(OptionSet &options);
std::unique_ptr<Platform> create_platform(int argc, char **argv);

#endif
