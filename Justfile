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
# simple or dramsim3. dramsim3 defines EMU_DRAMSIM3.
DRAM_BACKEND := env_var_or_default("DRAM_BACKEND", "simple")
DRAMSIM3_HOME := env_var_or_default("DRAMSIM3_HOME", "")

# Initialize a West workspace in this checkout and sync only the requested project.
_west-update project:
    #!/usr/bin/env zsh
    set -euo pipefail
    cd "$PRJ_ROOT"
    mkdir -p out
    touch out/west.lock
    zmodload zsh/system
    zsystem flock -f lock_fd out/west.lock
    if [[ ! -f .west/config ]]; then
        west init -l deps
    fi
    west update --narrow --fetch-opt=--depth=1 {{ quote(project) }}

sv:
    #!/usr/bin/env zsh
    set -e
    mill -i ansercore.run
    echo "SystemVerilog files are generated."

_compile target: sv
    #!/usr/bin/env zsh
    set -euo pipefail
    backend={{ quote(DRAM_BACKEND) }}
    case "$backend" in
        simple) ;;
        dramsim3)
            dram_dir={{ quote(DRAMSIM3_HOME) }}
            if [[ -z "$dram_dir" ]]; then
                just --justfile "$PRJ_ROOT/Justfile" _west-update dramsim3
                dram_dir=$(west list dramsim3 -f '{abspath}')
            elif [[ ! -f "$dram_dir/src/cosimulation.h" ]]; then
                echo "DRAMSIM3_HOME must point to an existing co-simulation checkout: $dram_dir" >&2
                exit 1
            fi
            dram_dir=${dram_dir:A}
            cmake -S "$dram_dir" -B "$dram_dir/build" -DCOSIM=ON -DCMAKE_BUILD_TYPE=Release
            cmake --build "$dram_dir/build" --target dramsim3 -j "$NIX_BUILD_CORES"
            ;;
        *) echo "unknown DRAM_BACKEND: $backend (expected simple or dramsim3)" >&2; exit 1 ;;
    esac
    obj_dir={{ BUILD_DIR }}/verilator/{{ target }}/$backend
    mkdir -p "$obj_dir"
    mkdir -p {{ BUILD_DIR }}/bin
    vsrc=(${(f)"$(find {{ BUILD_DIR }}/verilog -name '*.sv')"})
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
    if [[ "$backend" == "dramsim3" ]]; then
        include_dirs+=("$dram_dir/src")
        cxx_flags+=(-DEMU_DRAMSIM3)
        ld_flags+=("$dram_dir/build/libdramsim3.a")
    fi
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
    ln -sfn "../verilator/{{ target }}/$backend/ansercore" {{ BUILD_DIR }}/bin/ansercore

# Build the simulator only. `core-sim` runs the binary produced here.
compile: (_compile "AnserCore")

core-sim: compile
    {{ BUILD_DIR }}/bin/ansercore {{ SIM_ARGS }}

clean:
    rm -rf out
