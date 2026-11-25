/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

#include "session/options.h"

#include <getopt.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <string>
#include <vector>

bool OptionSet::takes_value(ValueKind kind) const {
  return kind != ValueKind::Flag;
}

bool OptionSet::assign(const RegisteredOption &option,
                       const char *value) const {
  switch (option.kind) {
  case ValueKind::Flag:
    *static_cast<bool *>(option.context) = true;
    return true;
  case ValueKind::Text:
    *static_cast<std::string *>(option.context) = value;
    return true;
  case ValueKind::Number:
    if (*value == '\0' || *value == '-') {
      return false;
    }
    errno = 0;
    char *end = nullptr;
    unsigned long long parsed = std::strtoull(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' ||
        parsed > std::numeric_limits<uint32_t>::max()) {
      return false;
    }
    *static_cast<uint32_t *>(option.context) = static_cast<uint32_t>(parsed);
    return true;
  }

  return false;
}

void OptionSet::add(const char *name, bool *value, const char *help,
                    char short_name) {
  RegisteredOption option;
  option.name = name;
  option.help = help == nullptr ? "" : help;
  option.short_name = short_name;
  option.kind = ValueKind::Flag;
  option.context = value;
  items_.push_back(option);
}

void OptionSet::add(const char *name, uint32_t *value, const char *help,
                    char short_name) {
  RegisteredOption option;
  option.name = name;
  option.help = help == nullptr ? "" : help;
  option.short_name = short_name;
  option.kind = ValueKind::Number;
  option.context = value;
  items_.push_back(option);
}

void OptionSet::add(const char *name, std::string *value, const char *help,
                    char short_name) {
  RegisteredOption option;
  option.name = name;
  option.help = help == nullptr ? "" : help;
  option.short_name = short_name;
  option.kind = ValueKind::Text;
  option.context = value;
  items_.push_back(option);
}

void OptionSet::print(const char *argv0) const {
  std::printf("Usage: %s [OPTION...]\n", argv0);
  std::printf("  -h, --help                 print all parameters\n");
  for (const RegisteredOption &option : items_) {
    if (option.short_name != 0) {
      std::printf("  -%c, --%s%s\n", option.short_name, option.name.c_str(),
                  takes_value(option.kind) ? "=VAL" : "");
    } else if (takes_value(option.kind)) {
      std::printf("      --%s=VAL\n", option.name.c_str());
    } else {
      std::printf("      --%s\n", option.name.c_str());
    }
    if (!option.help.empty()) {
      std::printf("          %s\n", option.help.c_str());
    }
  }
}

ParseStatus OptionSet::parse(int argc, char **argv) {
  // getopt_long permutes argv, so keep the caller's array unchanged.
  std::vector<std::string> owned;
  std::vector<char *> pointers;
  owned.reserve(static_cast<size_t>(argc));
  pointers.reserve(static_cast<size_t>(argc));
  for (int index = 0; index < argc; ++index) {
    owned.emplace_back(argv[index]);
  }
  for (std::string &arg : owned) {
    pointers.push_back(&arg[0]);
  }

  std::string short_opts = ":h";
  std::vector<option> longs;
  longs.push_back(option{"help", no_argument, nullptr, 'h'});
  for (size_t index = 0; index < items_.size(); ++index) {
    const RegisteredOption &item = items_[index];
    // Long options without a short name use 1000 + row, above any character
    // code.
    int value =
        item.short_name != 0 ? item.short_name : static_cast<int>(1000 + index);
    longs.push_back(
        option{item.name.c_str(),
               takes_value(item.kind) ? required_argument : no_argument,
               nullptr, value});
    if (item.short_name != 0) {
      short_opts.push_back(item.short_name);
      if (takes_value(item.kind)) {
        short_opts.push_back(':');
      }
    }
  }
  longs.push_back(option{nullptr, 0, nullptr, 0});

  int value = -1;
  while ((value = getopt_long(argc, pointers.data(), short_opts.c_str(),
                              longs.data(), nullptr)) != -1) {
    switch (value) {
    case 'h':
      return ParseStatus::Help;
    case '?':
      if (optopt != 0) {
        std::fprintf(stderr, "unknown option: -%c\n", optopt);
      } else {
        std::fprintf(stderr, "unknown option: %s\n", pointers[optind - 1]);
      }
      return ParseStatus::Error;
    case ':':
      if (optopt != 0) {
        std::fprintf(stderr, "option requires a value: -%c\n", optopt);
      } else {
        std::fprintf(stderr, "option requires a value: %s\n",
                     pointers[optind - 1]);
      }
      return ParseStatus::Error;
    default: {
      const RegisteredOption *matched;
      if (value >= 1000) {
        matched = &items_[static_cast<size_t>(value - 1000)];
      } else {
        for (const RegisteredOption &item : items_) {
          if (item.short_name == value) {
            matched = &item;
            break;
          }
        }
      }

      if (!assign(*matched, optarg)) {
        std::fprintf(stderr, "invalid value for --%s\n", matched->name.c_str());
        return ParseStatus::Error;
      }
      break;
    }
    }
  }
  return ParseStatus::Ok;
}
