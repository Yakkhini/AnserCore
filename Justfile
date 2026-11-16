# SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
#
# SPDX-License-Identifier: MulanPSL-2.0

set dotenv-load

export PRJ_ROOT := justfile_directory()
export NIX_BUILD_CORES := num_cpus()
BUILD_DIR := "$PRJ_ROOT/out"
INC_DIR := "$PRJ_ROOT/rtl-sim/include"
CONFIG_DIR := "$PRJ_ROOT/rtl-sim/config"
NPC_NAME := "AnserCore"
# XiangShan's co-simulation fork. `just dramsim3` clones and builds it.
DRAMSIM3_HOME := env_var_or_default("DRAMSIM3_HOME", justfile_directory() + "/third_party/DRAMsim3")
DRAMSIM3_LIB := DRAMSIM3_HOME + "/build/libdramsim3.a"

# GCC is the default. The Clang set is:
# SIM_COMPILER := "clang"
# SIM_CXX := "clang++"
# SIM_LINK := "clang++"
# SIM_AR := "llvm-ar"
SIM_COMPILER := "gcc"
SIM_CXX := "g++"
SIM_LINK := "g++"
SIM_AR := "ar"
SIM_ARGS := ""

# Clone OpenXiangShan/DRAMsim3 and build the static library.
dramsim3:
    #!/usr/bin/env zsh
    set -euo pipefail
    home={{DRAMSIM3_HOME}}
    if [[ ! -f $home/src/cosimulation.h ]]; then
        mkdir -p "$(dirname $home)"
        git clone --depth 1 --branch co-simulation \
            https://github.com/OpenXiangShan/DRAMsim3.git "$home"
    fi
    cmake -S "$home" -B "$home/build" -DCOSIM=ON -DCMAKE_BUILD_TYPE=Release
    cmake --build "$home/build" --target dramsim3 -j "$NIX_BUILD_CORES"

# Build the in-tree SoftFloat subset into out/lib/libsoftfloat.a.
softfloat:
    #!/usr/bin/env zsh
    set -euo pipefail
    src=$PRJ_ROOT/rtl-sim/ext/softfloat
    obj={{BUILD_DIR}}/softfloat
    lib={{BUILD_DIR}}/lib/libsoftfloat.a
    mkdir -p $obj {{BUILD_DIR}}/lib
    find $obj -name '*.o' -delete
    for file in $src/*.c; do
        {{SIM_COMPILER}} -O3 -I$src -c $file -o $obj/${file:t:r}.o
    done
    {{SIM_AR}} rcs $lib $obj/*.o

sv:
    #!/usr/bin/env zsh
    mill -i ansercore.run
    echo "SystemVerilog files are generated."

_compile target: softfloat dramsim3 sv
    #!/usr/bin/env zsh
    mkdir -p {{BUILD_DIR}}/bin
    mkdir -p {{BUILD_DIR}}/verilator
    vsrc=(${(f)"$(find {{BUILD_DIR}}/verilog -name '*.sv')"})
    csrc_dirs=(
        $PRJ_ROOT/rtl-sim/platform
        $PRJ_ROOT/rtl-sim/service
        $PRJ_ROOT/rtl-sim/session
        $PRJ_ROOT/rtl-sim/dpi-c
    )
    csrc=($PRJ_ROOT/rtl-sim/main.cc)
    found=$(find $csrc_dirs \( -name '*.cc' -o -name '*.cpp' -o -name '*.c' \))
    if [[ -n $found ]]; then
        csrc+=(${(f)found})
    fi
    vltsrc=(${(f)"$(find $PRJ_ROOT -name '*.vlt')"})

    verilator_flags=(
        --cc
        -Mdir {{BUILD_DIR}}/verilator/{{target}}
        --top-module {{target}}
        --prefix VTop
        --timescale 1ns/1ns
        --no-timing
        --compiler {{SIM_COMPILER}}
        -O3
        --x-assign fast
        --x-initial fast
        --noassert
        --autoflush
        -Wno-UNUSEDSIGNAL
        -Wno-DECLFILENAME
        -Wno-UNOPTFLAT
        --trace-fst
        --exe
        -o {{BUILD_DIR}}/bin/ansercore
    )
    include_dirs=(
        $PRJ_ROOT/rtl-sim
        {{BUILD_DIR}}/verilator
        {{INC_DIR}}
        {{CONFIG_DIR}}
        {{DRAMSIM3_HOME}}/src
        $PRJ_ROOT/rtl-sim/ext/softfloat
    )
    if [[ -n ${C_INCLUDE_PATH:-} ]]; then
        include_dirs+=($C_INCLUDE_PATH)
    fi
    cxx_flags=(
        -g
        -O3
        -flto
        -DCONFIG_TARGET_{{target}}
        -DEMU_TRACE_FST
    )
    for dir in $include_dirs; do
        cxx_flags+=(-I$dir)
    done
    ld_flags=(
        -flto
        {{BUILD_DIR}}/lib/libsoftfloat.a
        {{DRAMSIM3_LIB}}
        -ldl
        -pthread
    )
    cflags=()
    for flag in $cxx_flags; do
        cflags+=(-CFLAGS $flag)
    done
    ldflags=()
    for flag in $ld_flags; do
        ldflags+=(-LDFLAGS $flag)
    done

    verilator $verilator_flags $cflags $ldflags $vltsrc $csrc $vsrc
    make -C {{BUILD_DIR}}/verilator/{{target}} -f VTop.mk -j $NIX_BUILD_CORES CXX={{SIM_CXX}} LINK={{SIM_LINK}} AR={{SIM_AR}}

# Build the simulator only. `core-sim` runs the binary produced here.
compile: (_compile "AnserCore")

core-sim: compile
    {{BUILD_DIR}}/bin/ansercore {{SIM_ARGS}}

clean:
    rm -rf out
