<!--
SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
SPDX-License-Identifier: CC-BY-SA-4.0
-->

# 微架构概述

当前 AnserCore 的微架构可以分为四个主要组件：

- **前端（Frontend）**: 前端负责指令获取、指令解码和指令执行。
- **后端（Backend）**: 后端负责数据获取、数据缓存和数据写入。
- **仿存（Memory System）**: 仿存负责管理内存层级，包括 LSU、MMU 和 DCache。
- **缓存子系统（Cache Subsystem）**: 缓存子系统负责管理缓存层级，包括 L2 缓存。
