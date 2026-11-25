# AnserCore

## Simulator dependencies

`DRAM_BACKEND=dramsim3 just compile` downloads the pinned DRAMsim3 revision with West and builds the timing model. To build an existing local co-simulation checkout, set `DRAMSIM3_HOME` to its path. The simple memory backend skips DRAMsim3.

The development shell includes West. Outside it, install the `west` Python package. Dependency revisions and checkout paths are recorded in `deps/west.yml`. The recipes initialize West in the current checkout.

## LICENSE

All source code in this repository is licensed under Mulan PSL v2.

```
Copyright (c) 2026 ECOS Team <ecos-all@ict.ac.cn>
AnserCore is licensed under Mulan PSL v2.
You can use this software according to the terms and conditions of the Mulan PSL v2.
You may obtain a copy of Mulan PSL v2 at:
         http://license.coscl.org.cn/MulanPSL2
THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
See the Mulan PSL v2 for more details.
```
