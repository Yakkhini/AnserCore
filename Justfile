# SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
#
# SPDX-License-Identifier: MulanPSL-2.0

set dotenv-load

BUILD_DIR := "$PRJ_ROOT/out"
INC_DIR := "$PRJ_ROOT/rtl-sim/include"
CONFIG_DIR := "$PRJ_ROOT/rtl-sim/config"
NPC_NAME := "AnserCore"

sv:
    #!/usr/bin/env zsh
    mill -i ansercore.run
    echo "SystemVerilog files are generated."

_compile target:
    #!/usr/bin/env zsh
    mkdir -p {{BUILD_DIR}}/bin
    mkdir -p {{BUILD_DIR}}/verilator
    VSRC=`find {{BUILD_DIR}}/verilog -name '*.sv' | tr '\n' ' '` # use echo command latter cause dollar var will cause error
    CSRC=`find $PRJ_ROOT/rtl-sim -name '*.cc' | tr '\n' ' '` # use echo command latter cause dollar var will cause error
    VLTSRC=`find $PRJ_ROOT -name '*.vlt' | tr '\n' ' '` # use echo command latter cause dollar var will cause error
    verilator --cc -Mdir {{BUILD_DIR}}/verilator/{{target}} \
    --top-module {{target}} \
    --timescale 1ns/1ns --no-timing \
    --compiler clang \
    -O3 --x-assign fast --x-initial fast --noassert \
    --autoflush \
    -Wno-UNUSEDSIGNAL -Wno-DECLFILENAME -Wno-UNOPTFLAT \
    `echo $VLTSRC` `echo $CSRC` `echo $VSRC` \
    -CFLAGS -Wno-unknown-warning-option \
    -CFLAGS -I$C_INCLUDE_PATH \
    -CFLAGS -I{{BUILD_DIR}}/verilator -CFLAGS -I{{INC_DIR}} -CFLAGS -I{{CONFIG_DIR}} -CFLAGS -g \
    -CFLAGS -O3 -CFLAGS -flto -LDFLAGS -flto \
    -CFLAGS -DCONFIG_TARGET_{{target}} \
    --trace-fst --exe -o {{BUILD_DIR}}/bin/ansercore
    make -C {{BUILD_DIR}}/verilator/{{target}} -f V{{target}}.mk -j $NIX_BUILD_CORES CXX=clang++ LINK=clang++ AR=llvm-ar

core-sim: sv (_compile "AnserCore")

clean:
    rm -rf out
