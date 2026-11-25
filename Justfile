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
sv:
    #!/usr/bin/env zsh
    set -e
    mill -i ansercore.run
    echo "SystemVerilog files are generated."

_compile target: sv
    #!/usr/bin/env zsh
    set -euo pipefail
    obj_dir={{ BUILD_DIR }}/verilator/{{ target }}
    mkdir -p "$obj_dir"
    mkdir -p {{ BUILD_DIR }}/bin
    vsrc=(${(f)"$(find {{ BUILD_DIR }}/verilog -name '*.sv')"})
    csrc_dirs=(
        $PRJ_ROOT/rtl-sim/platform
        $PRJ_ROOT/rtl-sim/session
    )
    csrc=($PRJ_ROOT/rtl-sim/main.cc)
    found=$(find $csrc_dirs \( -name '*.cc' -o -name '*.cpp' -o -name '*.c' \))
    if [[ -n $found ]]; then
        csrc+=(${(f)found})
    fi
    vltsrc=(${(f)"$(find $PRJ_ROOT -name '*.vlt')"})

    verilator_flags=(
        --cc
        -Mdir "$obj_dir"
        --top-module {{ target }}
        --prefix VTop
        --timescale 1ns/1ns
        --no-timing
        --compiler {{ SIM_COMPILER }}
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
        -o ansercore
    )
    include_dirs=(
        $PRJ_ROOT/rtl-sim
        {{ BUILD_DIR }}/verilator
        {{ INC_DIR }}
        {{ CONFIG_DIR }}
    )
    if [[ -n ${C_INCLUDE_PATH:-} ]]; then
        include_dirs+=($C_INCLUDE_PATH)
    fi
    cxx_flags=(
        -g
        -O3
        -flto
        -DCONFIG_TARGET_{{ target }}
        -DEMU_TRACE_FST
    )
    ld_flags=(
        -flto
        -pthread
    )
    for dir in $include_dirs; do
        cxx_flags+=(-I$dir)
    done
    cflags=()
    for flag in $cxx_flags; do
        cflags+=(-CFLAGS $flag)
    done
    ldflags=()
    for flag in $ld_flags; do
        ldflags+=(-LDFLAGS $flag)
    done

    verilator $verilator_flags $cflags $ldflags $vltsrc $csrc $vsrc
    make -C "$obj_dir" -f VTop.mk -j $NIX_BUILD_CORES CXX={{ SIM_CXX }} LINK={{ SIM_LINK }} AR={{ SIM_AR }}
    ln -sfn "../verilator/{{ target }}/ansercore" {{ BUILD_DIR }}/bin/ansercore

# Build the simulator only. `core-sim` runs the binary produced here.
compile: (_compile "AnserCore")

core-sim: compile
    {{ BUILD_DIR }}/bin/ansercore {{ SIM_ARGS }}

clean:
    rm -rf out
