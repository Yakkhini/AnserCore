#ifndef SIM_DPI_DIFFTEST_H
#define SIM_DPI_DIFFTEST_H

#include <memory>
#include <string>

#include "dpi-c/difftest/ref/ref.h"
#include "dpi-c/dpi.h"

class OptionSet;

struct DifftestConfig {
  bool enable = false;
  std::string ref_path;
};

// Constructing an enabled difftest loads the reference and calls difftest_init.
class Difftest final : public DpiModule {
 public:
  explicit Difftest(DifftestConfig config);
  ~Difftest() override;

  void isa_compare(const IsaState& dut);
  void copy_to_ref(const IsaState& dut);
  void exec(uint64_t n);

 private:
  DifftestConfig config_;
  std::unique_ptr<RefLibrary> ref_;
};

void register_difftest_options(OptionSet& options);
std::unique_ptr<DpiModule> create_difftest();

#endif
