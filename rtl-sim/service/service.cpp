#include "service/service.h"

#include <utility>

#include "service/memory/memory.h"
#include "session/options.h"

namespace {

struct ServiceModule {
  void (*register_options)(OptionSet &);
  std::unique_ptr<SimService> (*create)();
};

const ServiceModule kModules[] = {
    {register_memory_options, create_memory},
};

} // namespace

void register_service_options(OptionSet &options) {
  for (const ServiceModule &module : kModules) {
    module.register_options(options);
  }
}

std::vector<std::unique_ptr<SimService>> create_services() {
  std::vector<std::unique_ptr<SimService>> services;
  for (const ServiceModule &module : kModules) {
    std::unique_ptr<SimService> service = module.create();
    if (service) {
      services.push_back(std::move(service));
    }
  }
  return services;
}
