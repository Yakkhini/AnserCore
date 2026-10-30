# Compact CHI 协议环境

## 功能描述

Compact CHI 协议环境为 ICache、DCache、PTW 和 UnCache 请求方提供统一的接口类型、参数和编码常量。它定义 REQ、EVT、RSP、DAT、SNP 五类 Flit，以及包含七个 ready/valid 通道的 `CompactCHIRequesterIO`。

协议环境只负责描述消息格式和基础编码辅助函数。缓存命中、MSHR、一致性状态、DBID 生命周期、CompAck 时机、Snoop 数据生成和请求重试由请求方控制器负责。

当前单核集成约定上游节点号如下：

| 请求方 | NodeID |
| --- | --- |
| ICache | `0b00` |
| PTW | `0b01` |
| DCache | `0b10` |
| UnCache | `0b11` |

NodeID 字段宽度仍由 `CompactCHIParams` 参数决定。上述数值是系统集成约定，不改变协议字段宽度。

## 参数描述

协议参数由 `CompactCHIParams` 定义。

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `addrBits` | 48 | REQ/EVT 字节地址宽度 |
| `upstreamNodeIdBits` | 7 | 上游请求方 NodeID 宽度 |
| `downstreamNodeIdBits` | 7 | 下游 Completer NodeID 宽度 |
| `txnIdBits` | 8 | REQ/EVT 和下游返回消息 TxnID 宽度 |
| `dbidBits` | 8 | 上游完成消息、写数据和 SNP TxnID 宽度 |
| `tagAliasBits` | 无默认值 | TagAlias 宽度，由上游 VIPT Cache 的参数规格确定，或由其索引配置自动导出；REQ 与 SNP 必须使用相同宽度 |
| `dataBits` | 256 | 单拍数据宽度，允许 64/128/256/512 |
| `dataIdBits` | 1 | 必须等于 `log2Ceil(512 / dataBits)` |
| `reqOpcodeBits` | 6 | Compact CHI REQ Opcode 宽度 |
| `evtOpcodeBits` | 1 | EVT Opcode 宽度 |
| `snpOpcodeBits` | 2 | SNP Opcode 宽度 |
| `upRspOpcodeBits` | 1 | 上游 RSP Opcode 宽度 |
| `downRspOpcodeBits` | 3 | 下游 RSP Opcode 宽度 |
| `upDatOpcodeBits` | 2 | 上游 DAT Opcode 宽度 |
| `downDatOpcodeBits` | 1 | 下游 DAT Opcode 宽度 |

TagAlias 由具体 VIPT 请求方根据自身索引结构生成，协议环境不规定它对应哪些虚拟地址位。`tagAliasBits` 必须由 DCache 或其他上游 Cache 的参数显式传入，不能在协议包中假定固定值。REQ 和 SNP 使用同一 `tagAliasBits` 配置；配置为 0 表示系统不启用硬件别名维护。

`Resp` 编码由 `CompactCHIResp` 提供：

| 常量 | 编码 |
| --- | --- |
| `I` | `0b000` |
| `SC` | `0b001` |
| `UC` | `0b010` |
| `I_PD` | `0b100` |
| `SC_PD` | `0b101` |
| `UC_PD` | `0b110` |

`0b011` 和 `0b111` 为保留编码。协议环境不实现缓存状态机，只提供状态编码。

## 接口描述

请求方使用：

```scala
val port = IO(new CompactCHIRequesterIO(c, wayBits))
```

| 端口 | 请求方方向 | Flit | TxnID 宽度 |
| --- | --- | --- | --- |
| `req` | 发出 | `CompactCHIReqFlit` | `txnIdBits` |
| `evt` | 发出 | `CompactCHIEvtFlit` | `txnIdBits` |
| `txRsp` | 发出 | `CompactCHIUpRspFlit` | `dbidBits` |
| `txDat` | 发出 | `CompactCHIUpDatFlit` | `dbidBits` |
| `rxRsp` | 接收 | `CompactCHIDownRspFlit` | `txnIdBits`，另有 `dbID` |
| `rxDat` | 接收 | `CompactCHIDownDatFlit` | `txnIdBits`，另有 `dbID` |
| `rxSnp` | 接收 | `CompactCHISnpFlit` | `dbidBits` |

所有端口使用 ready/valid 握手。

REQ 包含 `TagAlias`、`NS`、`Order`、`MemAttr`、`Excl`、`ExpCompData`、`ExpCompStash`、`WayValid` 和 `Way`。EVT 的 `MemAttr` 只有 Allocate 属性。UpRSP/UpDAT 和 DnRSP/DnDAT 保留 `RespErr`；UpDAT 保留 `byteEnable`，用于部分写；DnRSP/DnDAT 保留 `CBusy` 和 `DataSource`。

SNP 的 `Addr` 是物理地址右移 3 位。SNP 扩展携带 `TagAlias`，表示被探测的上游旧副本使用的 alias；它不替代物理地址，也不携带 Way。

## 事务描述

### ReadShared 与 CompAck

请求方发出 ReadShared，接收完整 CompData 后使用数据消息中的 `DBID` 发送 CompAck。CompAck 的 TxnID 是 DBID，不是原请求 TxnID。

```scala
port.txRsp.bits := CompactCHIEdge.compAck(c, completion)
```

ReadUnique 和 MakeUnique 也可能需要 CompAck。ReadUnique 可以通过 RSP 返回无数据 Comp；ReadOnce、ReadNoSnp、CMO、Stash 和写请求不发送 CompAck。

### ReadOnce 与 Snoop

ReadOnce 在收齐 CompData 后结束，不发送 CompAck。收到 SNP 后，使用 SNP 的 TxnID 生成 SnpResp，源和目标对调：

```scala
port.txRsp.bits := CompactCHIEdge.snoopResponse(
  c,
  port.rxSnp.bits,
  resp = CompactCHIResp.I,
  respErr = CompactCHIRespErr.NoError,
)
```

需要返回数据时，控制器另外发送 `CompactCHIUpDatFlit`，使用 `SnpRespData` 和 SNP 的 TxnID。

### 非缓存写

WriteNoSnpPtl 和 WriteNoSnpFull 的数据必须在收到 DBIDResp 或 CompDBIDResp 后发送。`byteEnable` 表示本拍每个字节是否有效；256 bit 数据拍包含 32 个字节，部分 MMIO 写不能因为总线宽度为 32B 而省略掩码。

## Opcode 范围

协议环境定义以下非保留 Opcode：

- EVT：`Evict`、`WriteBackFull`；
- REQ：`StashShared`、`StashUnique`、`ReadNoSnp`、`ReadOnce`、`ReadShared`、`WriteNoSnpPtl`、`WriteNoSnpFull`、`WriteUniquePtl`、`WriteUniqueFull`、`CleanShared`、`CleanInvalid`、`MakeInvalid`、`ReadUnique`、`MakeUnique`；
- SNP：`SnpMakeInvalid`、`SnpToInvalid`、`SnpToShared`、`SnpToClean`；
- DnRSP：`CompStash`、`Comp`、`DBIDResp`、`CompDBIDResp`、`CompCMO`；
- UpRSP：`CompAck`、`SnpResp`；
- DnDAT：`CompData`；
- UpDAT：`NonCopyBackWrData`、`CopyBackWrData`、`SnpRespData`。

REQ 的 `0x20..0x31` 保留为 Atomic 编码范围，当前协议环境只保留合法编码判断，不提供 Atomic 事务 helper 或执行逻辑。保留编码不定义为正常 Opcode。

## 状态机与控制边界

本协议环境没有事务状态机、MSHR、DBID 配对、CompAck 调度、Retry/P-Credit、缓存状态迁移或 Snoop 数据生成。请求方控制器必须自行保存跨周期事务上下文，并保证各通道在反压期间保持 Flit 稳定。
