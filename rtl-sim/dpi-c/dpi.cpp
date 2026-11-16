#include "dpi-c/dpi.h"

#include <utility>

#include "dpi-c/difftest/difftest.h"
#include "dpi-c/softBackend/ideal.h"
#include "dpi-c/softMMU/mmu.h"

namespace {

struct DpiEntry {
  void (*register_options)(OptionSet&);
  std::unique_ptr<DpiModule> (*create)();
};

const DpiEntry kModules[] = {
    {register_difftest_options, create_difftest},
    {register_mmu_options, create_mmu},
    {register_ideal_options, create_ideal},
};

}  // namespace

void register_dpi_options(OptionSet& options) {
  for (const DpiEntry& module : kModules) {
    module.register_options(options);
  }
}

std::vector<std::unique_ptr<DpiModule>> create_dpi() {
  std::vector<std::unique_ptr<DpiModule>> modules;
  for (const DpiEntry& module : kModules) {
    std::unique_ptr<DpiModule> created = module.create();
    if (created) {
      modules.push_back(std::move(created));
    }
  }
  return modules;
}
