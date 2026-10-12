/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

#include "VAnserCore.h"
#include "verilated.h"

int main(int argc, char **argv) {
  VerilatedContext *contextp = new VerilatedContext;
  contextp->commandArgs(argc, argv);

  VAnserCore *top = new VAnserCore{contextp};

  while (!contextp->gotFinish()) {
    top->eval();
  }

  delete top;
  delete contextp;
  return 0;
}
