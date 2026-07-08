% SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
% SPDX-License-Identifier: CC-BY-SA-4.0

# MSHR 控制模块

## 功能描述

当 L2 发生缓存 miss 或者 hit 但需要更改 meta 权限时，会为请求分配一项 MSHR，用于记录请求所处的状态，并接收通道控制器与目录的响应，根据当前状态发出对应的控制请求。当所有控制请求完成后，释放 MSHR。

## 接口描述

## 事务描述

## 流水级描述

- Stage 1: xxxx
- Stage 2: xxxx

## 状态机描述

- sWork
