#include "service/lightsss/lightsss.h"

#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <new>
#include <stdexcept>
#include <string>
#include <utility>

#include "platform/platform.h"
#include "session/options.h"

struct LightsssShare {
  std::atomic<uint32_t> flag;
  std::atomic<int> oldest;
};

namespace {

LightsssConfig config;

uint64_t now_ms() {
  timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  return static_cast<uint64_t>(now.tv_sec) * 1000ull +
         static_cast<uint64_t>(now.tv_nsec) / 1000000ull;
}

void flush_stdio() {
  std::fflush(stdout);
  std::fflush(stderr);
}

void wait_pid(int pid) {
  while (waitpid(pid, nullptr, 0) < 0) {
    if (errno != EINTR) {
      break;
    }
  }
}

} // namespace

LightsssService::LightsssService(LightsssConfig parsed)
    : config_(std::move(parsed)), is_child_(false), have_forked_(false),
      interval_ms_(0), last_fork_ms_(0), share_(nullptr) {
  check_config();
  interval_ms_ = static_cast<uint64_t>(config_.interval) * 1000ull;
  std::setvbuf(stdout, nullptr, _IOLBF, 0);
  flush_stdio();
  void *memory = mmap(nullptr, sizeof(LightsssShare), PROT_READ | PROT_WRITE,
                      MAP_SHARED | MAP_ANONYMOUS, -1, 0);
  if (memory == MAP_FAILED) {
    throw std::runtime_error("LightSSS mmap failed");
  }
  share_ = new (memory) LightsssShare();
  share_->flag.store(0);
  share_->oldest.store(0);
}

LightsssService::~LightsssService() {
  if (share_ != nullptr) {
    share_->~LightsssShare();
    munmap(share_, sizeof(LightsssShare));
    share_ = nullptr;
  }
}

void LightsssService::check_config() {
  if (config_.slots == 0) {
    throw std::runtime_error("invalid --lightsss-slots " +
                             std::to_string(config_.slots));
  }
  if (config_.interval == 0 || config_.interval > 24u * 60u * 60u) {
    throw std::runtime_error("invalid --lightsss-interval " +
                             std::to_string(config_.interval));
  }
}

bool LightsssService::on_posedge() {
  if (is_child_) {
    return true;
  }
  uint64_t now = now_ms();
  if (have_forked_ && now - last_fork_ms_ < interval_ms_) {
    return true;
  }
  have_forked_ = true;
  last_fork_ms_ = now;
  return spawn();
}

bool LightsssService::spawn() {
  while (slots_.size() >= config_.slots) {
    int oldest = slots_.back();
    slots_.pop_back();
    kill(oldest, SIGKILL);
    wait_pid(oldest);
  }

  flush_stdio();
  pid_t pid = fork();
  if (pid < 0) {
    std::perror("LightSSS fork");
    return false;
  }
  // 父进程，
  if (pid > 0) {
    slots_.push_front(pid);
    flush_stdio();
    return true;
  }
  // 子进程
  is_child_ = true;
  slots_.clear();
  // 检查是否苏醒
  while (share_->flag.load(std::memory_order_acquire) == 0) {
    usleep(1000);
  }
  if (share_->oldest.load(std::memory_order_relaxed) != getpid()) {
    std::fprintf(stderr, "LightSSS non-oldest child %d woke\n", getpid());
    _exit(1);
  }
  std::printf("LightSSS child %d resumed\n", getpid());
  flush_stdio();
  // Verilator keeps only the forking thread.
  at_clone();
  open_waveform();
  return true;
}

void LightsssService::wakeup() {
  if (slots_.empty()) {
    return;
  }
  int oldest = slots_.back();
  share_->oldest.store(oldest, std::memory_order_relaxed);
  for (int pid : slots_) {
    if (pid == oldest) {
      continue;
    }
    kill(pid, SIGKILL);
    wait_pid(pid);
  }
  std::printf("LightSSS wakeup oldest %d\n", oldest);
  flush_stdio();
  share_->flag.store(1, std::memory_order_release);
  wait_pid(oldest);
  slots_.clear();
}

void LightsssService::clear_children() {
  while (!slots_.empty()) {
    int pid = slots_.back();
    slots_.pop_back();
    kill(pid, SIGKILL);
    wait_pid(pid);
  }
}

void LightsssService::finish(bool error) {
  if (is_child_) {
    return;
  }
  if (error) {
    wakeup();
    return;
  }
  clear_children();
}

void register_lightsss_options(OptionSet &options) {
  options.add("lightsss", &config.enable, "enable LightSSS checkpoints");
  options.add("lightsss-slots", &config.slots,
              "number of checkpoint processes (default 2)");
  options.add("lightsss-interval", &config.interval,
              "seconds between checkpoints (default 10)");
}

std::unique_ptr<SimService> create_lightsss() {
  if (!config.enable) {
    return nullptr;
  }
  return std::make_unique<LightsssService>(config);
}

bool lightsss_enabled() { return config.enable; }
