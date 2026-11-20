#include "dpi-c/difftest/ref/ref.h"

#include <dlfcn.h>

#include <cstdio>
#include <stdexcept>
#include <string>

#include "service/memory/memory.h"

namespace {

void *load_symbol(void *handle, const char *name) {
  dlerror();
  void *symbol = dlsym(handle, name);
  const char *error = dlerror();
  if (symbol == nullptr || error != nullptr) {
    std::fprintf(stderr, "ref is missing %s: %s\n", name,
                 error != nullptr ? error : "null symbol");
    return nullptr;
  }
  return symbol;
}

} // namespace

RefLibrary::RefLibrary(const std::string &path)
    : handle_(nullptr), memcpy_(nullptr), regcpy_(nullptr), exec_(nullptr),
      raise_trap_(nullptr) {
  if (path.empty()) {
    throw std::runtime_error("missing --difftest-ref");
  }
  handle_ = dlopen(path.c_str(), RTLD_LAZY);
  if (handle_ == nullptr) {
    std::fprintf(stderr, "cannot open ref %s: %s\n", path.c_str(), dlerror());
    throw std::runtime_error("failed to load difftest ref " + path);
  }

  auto init =
      reinterpret_cast<void (*)()>(load_symbol(handle_, "difftest_init"));
  memcpy_ = reinterpret_cast<void (*)(uint64_t, void *, size_t, bool)>(
      load_symbol(handle_, "difftest_memcpy"));
  regcpy_ = reinterpret_cast<void (*)(void *, bool)>(
      load_symbol(handle_, "difftest_regcpy"));
  exec_ = reinterpret_cast<void (*)(uint64_t)>(
      load_symbol(handle_, "difftest_exec"));
  raise_trap_ = reinterpret_cast<void (*)(uint64_t, uint64_t)>(
      load_symbol(handle_, "difftest_raise_trap"));
  if (init == nullptr || memcpy_ == nullptr || regcpy_ == nullptr ||
      exec_ == nullptr || raise_trap_ == nullptr) {
    dlclose(handle_);
    handle_ = nullptr;
    throw std::runtime_error("failed to load difftest ref " + path);
  }
  init();
  std::printf("difftest reference: %s\n", path.c_str());
  MemoryService *memory = current_memory();
  memcpy_(0x80000000ull, const_cast<uint8_t *>(memory->image()),
          static_cast<size_t>(memory->image_bytes()), DIFFTEST_TO_REF);
}

RefLibrary::~RefLibrary() { dlclose(handle_); }

void RefLibrary::memcpy(uint64_t addr, void *buf, size_t n, bool direction) {
  memcpy_(addr, buf, n, direction);
}

void RefLibrary::regcpy(IsaState *state, bool direction) {
  regcpy_(state, direction);
}

void RefLibrary::exec(uint64_t n) { exec_(n); }

void RefLibrary::raise_trap(uint64_t cause, uint64_t tval) {
  raise_trap_(cause, tval);
}
