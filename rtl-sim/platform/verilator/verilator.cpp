/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

#include <verilated.h>

#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

#include "VTop.h"
#include "platform/platform.h"
#include "session/options.h"

#if defined(EMU_TRACE_FST)
#include <verilated_fst_c.h>
using TraceFile = VerilatedFstC;
#define EMU_HAS_TRACE 1
#elif defined(EMU_TRACE_VCD)
#include <verilated_vcd_c.h>
using TraceFile = VerilatedVcdC;
#define EMU_HAS_TRACE 1
#endif

namespace {

struct Config {
  uint32_t reset_cycles = 50;
  bool dump_wave = false;
  bool dump_wave_full = false;
  std::string wave_path;
};

Config config;

class VerilatorPlatform final : public Platform {
public:
  VerilatorPlatform(Config parsed, int argc, char **argv);

  void tick() override;
  bool get_finsh() override { return context_->gotFinish(); }
  bool get_error() override { return context_->gotError(); }
  void finish() override {
    std::printf("simulation time: %" PRIu64 "\n", context_->time());
  }

private:
  Config config_;
  std::unique_ptr<VerilatedContext> context_;
  std::unique_ptr<VTop> dut_;
#if defined(EMU_HAS_TRACE)
  bool open_waveform();
  std::unique_ptr<TraceFile> waveform_;
#endif
};

VerilatorPlatform::VerilatorPlatform(Config parsed, int argc, char **argv)
    : config_(std::move(parsed)) {
  context_ = std::make_unique<VerilatedContext>();
  context_->commandArgs(argc, argv);
#if defined(EMU_HAS_TRACE)
  if (config_.dump_wave) {
    context_->traceEverOn(true);
  }
#endif
  dut_ = std::make_unique<VTop>(context_.get());
#if defined(EMU_HAS_TRACE)
  if (config_.dump_wave) {
    if (!open_waveform()) {
      throw std::runtime_error("cannot open waveform file");
    }
  }
#endif

  dut_->reset = 1;
  for (uint32_t cycle = 0; cycle < config_.reset_cycles; ++cycle) {
    tick();
  }
  dut_->reset = 0;
}

void VerilatorPlatform::tick() {
  dut_->clock = 0;
  dut_->eval();
#if defined(EMU_HAS_TRACE)
  if (waveform_) {
    if (config.dump_wave_full) {
      waveform_->dump(context_->time());
    }
  }
#endif
  context_->timeInc(1);

  dut_->clock = 1;
  dut_->eval();
#if defined(EMU_HAS_TRACE)
  if (waveform_) {
    waveform_->dump(context_->time());
  }
#endif
  context_->timeInc(1);
}

#if defined(EMU_HAS_TRACE)
bool VerilatorPlatform::open_waveform() {
  std::string path = config_.wave_path;
  if (path.empty()) {
#if defined(EMU_TRACE_FST)
    path = "wave.fst";
#else
    path = "wave.vcd";
#endif
  }
  waveform_ = std::make_unique<TraceFile>();
  dut_->trace(waveform_.get(), 99);
  waveform_->open(path.c_str());
  if (waveform_->isOpen()) {
    return true;
  }
  std::fprintf(stderr, "cannot open waveform file: %s\n", path.c_str());
  return false;
}
#endif

} // namespace

void register_platform_options(OptionSet &options) {
  config = Config{};
  options.add("reset-cycles", &config.reset_cycles,
              "reset for N cycles before the main loop (default 50)");
  options.add("dump-wave", &config.dump_wave,
              "enable waveform tracing; records the rising edge");
  options.add("dump-wave-full", &config.dump_wave_full,
              "record both half-cycles; requires --dump-wave");
  options.add("wave-path", &config.wave_path, "waveform output path");
}

std::unique_ptr<Platform> create_platform(int argc, char **argv) {
  return std::make_unique<VerilatorPlatform>(config, argc, argv);
}
