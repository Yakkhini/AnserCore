/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

#ifndef SIM_SESSION_OPTIONS_H
#define SIM_SESSION_OPTIONS_H

#include <cstdint>
#include <string>
#include <vector>

enum class ParseStatus {
  Ok,
  Help,
  Error,
};

enum class ValueKind {
  Flag,
  Number,
  Text,
};

struct RegisteredOption {
  std::string name;
  std::string help;
  char short_name;
  ValueKind kind;
  void *context;
};

class OptionSet {
public:
  void add(const char *name, bool *value, const char *help = nullptr,
           char short_name = 0);
  void add(const char *name, uint32_t *value, const char *help = nullptr,
           char short_name = 0);
  void add(const char *name, std::string *value, const char *help = nullptr,
           char short_name = 0);

  ParseStatus parse(int argc, char **argv);
  void print(const char *argv0) const;

private:
  bool takes_value(ValueKind kind) const;
  bool assign(const RegisteredOption &option, const char *value) const;

  std::vector<RegisteredOption> items_;
};

#endif
