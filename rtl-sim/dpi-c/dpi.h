#ifndef SIM_DPI_DPI_H
#define SIM_DPI_DPI_H

#include <memory>
#include <vector>

class OptionSet;

// RTL calls these during eval(). Session only constructs them before the
// platform and keeps them alive until the simulation ends.
class DpiModule {
 public:
  virtual ~DpiModule() = default;
};

void register_dpi_options(OptionSet& options);
std::vector<std::unique_ptr<DpiModule>> create_dpi();

#endif
