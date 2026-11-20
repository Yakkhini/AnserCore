#ifndef SIM_SERVICE_SERVICE_H
#define SIM_SERVICE_SERVICE_H

#include <memory>
#include <vector>

class OptionSet;

class SimService {
public:
  virtual ~SimService() = default;
  virtual const char *name() const = 0;
  virtual bool on_posedge() = 0;
  virtual void finish(bool) {}
};

void register_service_options(OptionSet &options);
std::vector<std::unique_ptr<SimService>> create_services();

#endif
