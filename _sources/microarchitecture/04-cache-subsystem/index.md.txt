<!--
SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
SPDX-License-Identifier: CC-BY-SA-4.0
-->

# 缓存子系统

AnserCore 缓存子系统微架构文档

## 功能描述

缓存子系统（Cache Subsystem）负责 L2 缓存。YQH-L2 模块通过通道控制器接收 TileLink 总线上的请求，并将其转换为 Cache 内部请求。之后通过请求仲裁模块进入主流水线，读取目录获取缓存块状态，根据缓存块状态和请求信息判断是否能够处理。若本层缓存可以直接处理该请求，则继续在主流水线中进行读数据、更新目录等操作，然后进入通道控制器模块，转化为 TileLink 总线请求回复。

若需要和其它缓存进行交互才能处理该请求，则为其配一个 MSHR。MSHR 根据需求向上下层 Cache 发送子请求，等待收到响应并满足释放条件后，再释放任务重新进入主流水线，进行读缓冲区、读写数据、更新目录等操作，然后进入通道控制器模块，转化为 TileLink 总线请求回复。

当一个请求所需的全部操作在 MSHR 中完成时，MSHR 被释放，等待接收新的请求。

### 子模块列表

{doc}`mshrctrl <mshrctrl>`
: MSHR（Miss Status Handling Register）控制模块，管理所有 miss / probe / refill / release 等跨周期事务，默认包含 16 项 MSHR。

### 特性 1：采用类 MESI 的缓存一致性协议

YQH-L2 遵循 TileLink 一致性树的规则，其缓存行状态包括 N（Nothing）、B（Branch）、T（Trunk）、TT（Tip）4 个状态：

- N：无效
- B：只有读权限
- T：当前核具有写权限，但是写权限位于上游 cache，当前 cache 层次不可读不可写（内部状态）
- TT：可读可写

一致性树按照内存、L3、L2、L1 的顺序自下而上生长，内存作为根节点拥有可读可写的权限，子节点的权限都不能超过父节点的权限。其中 TT 代表拥有 T 权限的最上层子节点（也是 T 权限树的叶子节点），说明该节点上层只有 N 或 B 权限，相反 T 权限而不是 TT 权限的节点代表上层一定还有 T/TT 权限节点。详细规则请参考 TileLink 手册 9.1 章。

## 参数描述

`enabldMSHR`
: 是否启用 MSHR，默认为 true。

## 接口描述

## 事务描述

### 接收并响应请求事务

### 发送 Refill 事务

## 流水级描述

- Stage 1：接收 L1 请求
- Stage 2：发回相应信号

## 状态机描述

- sIdle：
- sWork：
- sRefill：

```{toctree}
:maxdepth: 1
:hidden:

mshrctrl
```
