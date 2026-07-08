<!--
SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
SPDX-License-Identifier: CC-BY-SA-4.0
-->

# 微架构概述

当前 AnserCore 的微架构可以分为四个主要组件：

- **前端（Frontend）**: 前端负责分支预测、指令取回、指令解码等功能处理。
- **后端（Backend）**: 后端负责乱序调度、指令执行、数据写回等功能处理。
- **仿存（Memory System）**: 仿存负责管理内存层级，包括 LSU、MMU 和 DCache。
- **缓存子系统（Cache Subsystem）**: 缓存子系统负责管理缓存层级，包括 L2 缓存。

## 文档规范

此处的文档规范仅对当前微架构文档编写内容的结构进行约束，尽可能保障 AnserCore 初期文档体系的严谨性，以指导后续处理器核的正式开发。

一般性的，从模块拆分的角度看，可以在四个主要组件下编写各个下一级子模块的文档。不建议将模块拆分得太碎。

文档格式主要由 MyST 标记语言进行编写。对于任何模块的文档编写，建议包括以下内容：

功能描述
: 从模块动机和微架构特性的角度，用语言文字描述此模块的功能与性能方案，方便读者理解此模块的工作方式与性能特征。

参数描述
: 如果此模块支持参数化配置，需要描述此模块的参数类型、含义、默认值、合法值等。考虑使用 MyST 的 `fieldlist` 或 `deflist` 来编写参数列表。

接口描述
: 描述此模块的接口信号，包括信号名、信号分组、数据内容和含义、涉及事务、连接的对向模块等。考虑使用 MyST 的 `fieldlist` 或 `deflist` 来编写信号列表。

事务描述
: 从事务粒度描述模块对外作为主机或者从机的信号时序行为，需要在形式化程度上尽可能和波形时序图等价。

流水级描述
: 对于流水化模块，需要描述各个流水级在时钟周期内的行为。

状态机描述
: 描述此模块的状态机设计，以及每个状态在做的业务逻辑。

上述的第一个、第二个部分为通用性质的描述。第三个、第四个部分为模块对外形态的描述，用于其他模块的维护者理解此模块的行为，以支持相关模块的开发工作。第五个、第六个部分为模块内部形态的描述，用于此模块维护者后续的实现、迭代等开发工作。对于可参数化模块，在一般情况下各个内容部分基于默认值的假设编写，如果存在某些参数下功能的边界行为或者特例，有必要时也可以补充。

从提交规范上，贡献者每次提交 PR 时需要对涉及的新增、修改或删除的范围进行约束，不要将过于宽泛的改动以一次提交完成。文档的提交约定同样遵循整个仓库的风格，需要声明 `docs` 作用域和顶层组件名称的作用域，例如：

```text
# Note that the scope `frontend` is valid and the scope `icache` is invalid in documentation commits.
[docs, frontend] add ICache documentation
[docs, backend] fix typos in issue queue description
[docs, memory] merge load queue and store queue doc files
[docs, cache] remove music files since unuseful
```

从排版规范来看，以 [中文文案排版指北](https://github.com/sparanoid/chinese-copywriting-guidelines) 为准。目前一些格式检查的 CI / PR 检查工具已经部署到仓库内，如果贡献内容存在格式问题预期会暴露出来。
