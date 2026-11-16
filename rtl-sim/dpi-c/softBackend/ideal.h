#ifndef SIM_SERVICE_IDEAL_H
#define SIM_SERVICE_IDEAL_H

#include <cstdint>

#include "dpi-c/difftest/ref/ref.h"
#include "dpi-c/dpi.h"
#include "exec.h"

class OptionSet;

// What one step reports. taken/target come from the instruction.
// mmio means a device was touched. The hart itself is read separately.
struct StepResult {
  bool taken = false;
  uint64_t target = 0;
  bool mmio = false;
};

// Fetch request from the frontend. taken/target describe the branch.
// A pc or instruction mismatch calls session_fail and leaves the hart unchanged.
extern "C" void ideal_backend_inst(uint64_t pc, unsigned int inst,
                                   unsigned char page_fault,
                                   unsigned char access_fault,
                                   unsigned char* taken, uint64_t* target,
                                   IsaState* state);

// Constructed once when --ideal is set. The constructor clears the hart.
class SoftBackend final : public DpiModule {
 public:
  SoftBackend();
  ~SoftBackend() override;

  StepResult step(uint64_t pc, uint32_t inst, unsigned char page_fault,
                  unsigned char access_fault);
  IsaState isa_state() const;

 private:
  void reset();
  bool decode(ExecContext* ctx, uint32_t raw, IdealDecoded* decoded);

  IdealHart hart_{};
};

void register_ideal_options(OptionSet& options);
std::unique_ptr<DpiModule> create_ideal();

#endif
