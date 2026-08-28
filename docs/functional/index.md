<!--
SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
SPDX-License-Identifier: CC-BY-SA-4.0
-->

# RVA23 / RVB23 配置规范调研

Reference：[RISC-V Ratified Specifications Library - Profiles](https://docs.riscv.org/reference/home/index.html)

RISC-V 指令集以其高度模块化与可定制化为显著特色之一。但是面临软件生态整合与市场推广时，过于碎片的规范约束给软件适配带来困难，降低了软件生态的泛用型。因此，在一般通用场景下约束更清晰的配置规范（Profiles）成为 RISC-V 的推荐解决方案。目前比较广泛使用的是 RVA23 配置规范和 RVB23 配置规范。其中 RVA23 配置规范针对高性能场景如服务器或者桌面平台的处理器规格需求，RVB23 配置规范针对嵌入式系统或边缘场景下的处理器规格需求。每一种配置文档在本质上都是对目前 RISC-V 指令集规范中认为有必要实现的指令集扩展的囊括。

在 AnserCore 处理器核项目的前期调研与规格约束工作中，我们需要研判 RVA23 / RVB23 两种配置规范，检查其中的每一种扩展集在硬件微架构上进行功能或性能支持的设计成本。此文档会依据相关定义规范和手册简要介绍 RVA23 和 RVB23 的相关内容，作为后续调研工作的参考。

## RVA23 配置规范规定列表

RVA23 配置规范分为用户态模式配置规范（RVA23U64）和特权级模式配置规范（RVA23S64）两类。它包括了一系列必要指令集扩展以及部分可被探测的可选指令集扩展。

### RVA23U64 Profile

基础指令集：RV64I 小端序 + ECALL 指令引起对执行环境的陷入请求

建议功能：执行未实现操作类型时引起非法指令异常

#### 强制扩展（继承自 RVA22U64）

- M：整数乘除法
- A：原子指令
- F：单精度浮点指令
- D：双精度浮点指令
- C：压缩指令
- B：位操作指令
- Zicsr：CSR 指令，同时也是 F 扩展集的隐含依赖
- Zicntr：基础的计数器和计时器
- ZIhpm：硬件性能计数器
- Ziccif：规范定义扩展（Profile-Defined Extensions）。在具有可缓存和一致性属性的主存区域内，进行对齐取指时需要保证原子性。对运行时指令修改有帮助。
- Ziccrse
- Ziccamoa：规范定义扩展，在具有可缓存和一致性属性的主存区域内，支持全部的原子操作。
- Zicclsm：规范定义扩展，在具有可缓存和一致性属性的主存区域内支持非对齐仿存。
- Za64rs：
- Zihintpause：提示等待，在自旋场景下有用。可以实现为 NOP。
- Zic644b：体系结构缓存块为 64Byte，作为缓存管理操作（CMO）指令的基础。
- Zicbom：显式缓存一致性处理，保证 Non-Coherence DMA Device 场景的正确性。
- Zicbop：预取指令，可以实现为 NOP
- Zicboz：缓存块清零
- Zfhmin：半精度浮点操作
- Zkt：规范定义扩展，数据无关执行延迟，在密码学场景下阻止时序侧信道攻击

#### 强制扩展（RVA23U64 新增）

- V：向量扩展
- Zvfhmin：向量半精度浮点最小支持
- Zvbb：向量位操作
- Zvkt：向量数据无关执行延迟
- Zihintntl：非时间局部性提示
- Zicond：谓词化分支指令
- Zimop：潜在操作指令，即目前视为 NOP 但未来可能有实际含义的指令，用于将来的前向兼容
- Zcmop：潜在操作指令，定义在压缩指令空间
- Zcb：额外压缩指令
- Zfa：额外浮点指令
- Zawrs
- Supm：指针掩码（Pointer Masking）支持

#### 可选扩展（地区政策原因）

- Zvkng：向量加密支持带 GCM 检验的 NIST 算法族
- Zvksg：向量加密支持带 GCM 检验的中国商密算法族

#### 可选扩展（未来可能强制）

- Zabha：字节和半字原子内存操作
- Zacas：比较并交换指令
- Ziccamoc
- Zvbc：向量无进位乘法
- Zama16b

#### 可选扩展（易被探查）

- Zfh：标量半精度浮点计算
- Zbc：标量无进位乘法
- Zicfilp：前向控制流保护，在 Linux 7.0 启用，可 NOP
- Zicfiss：后向控制流保护
- Zvfh：向量半精度浮点计算
- Zfbfmin：标量 BF16 类型转换
- Zvfbfmin：向量 BF16 类型转换
- Zvfbfwma：向量 BF16 拓宽乘加

#### 可选扩展（定位模糊的临时选项）

无

### RVA23S64 Profile

基础指令集：RV64I 小端序 + ECALL 指令

用户态的 ECALL 指令引起到特权级的陷入，特权级的 ECALL 指令引起对执行环境的陷入。建议执行未实现操作类型时引起非法指令异常。

#### 强制扩展（非特权级扩展）

- 所有 RVA23U64 的强制扩展
- Zifencei：取指屏障，可能在未来被 Zjid 指令缓存一致性机制扩展替代。

#### 强制扩展（继承自 RVA22S64）

- Ss1p13：特权级架构版本 1.13，作为 Ss1p12 的替代
- Svbare：satp 裸机模式支持
- Sv39：基于页表的 Sv39 虚拟内存系统
- Svade：页表在 A 标志位被清空时的访问或者 D 标志位被清空时被写入产生页表错误异常
- Ssccptr：在具有可缓存和一致性属性的主存区域内支持硬件页表读取
- Sstvecd：stvec 支持 Direct 模式，并且在 Direct 模式内 BASE 部分可以保存任意四字节对齐的地址
- Sstvala：
  - stval 必须在以下情况写入产生错误的虚拟地址：
    - 访存或者取指产生页表错误（Page Fault），Access Fault（访存错误），未对齐异常
    - 由于执行 EBREAK 或者 C.EBREAK 指令以外的原因引发的断点异常
  - 而对于虚拟指令（Virtual Instruction，虚拟化拦截）和非法指令异常，stval 必须写入错误指令
- Sscounterenw：必须通过 scounteren 来控制性能计数器读写权限
- Svpbmt：基于页表的内存类型（PMA，NC，I/O）
- Svinval：细粒度地址翻译缓存失效
- Svnapot：连续页聚合（和大页有点像，但是不一样）
- Sstc：特权级时钟中断（消除去机器级中断的开销）
- Sscofpmf：计数器溢出以及基于模式的过滤
- Ssnpm：指针掩码支持（特权级支持用户级）
- Ssu64xl：必须可以支持用户态 64 位架构
- Sha：参数化虚拟化扩展
  - H：虚拟化扩展
  - Ssstateen
  - Shcounterenw
  - Shvstvala
  - Shtvala
  - Shvstvecd
  - Shvsatpa
  - Shgatpa

#### 可选扩展（地区政策原因）

无

#### 可选扩展（未来可能被强制）

无

#### 可选扩展（易被探查）

继承自 RVA22S64：

- Sv48：基于页表的 48 位虚拟内存系统
- Sv57：基于页表的 57 位虚拟内存系统
- Zkr：熵源特权级寄存器

RVA23S64 新增：

- Svadu：硬件 A/D 标志位更新
- Sdtrig：硬件调试触发器
- Ssstrict：对标准/保留编码空间里没有实现的指令或 CSR，必须明确抛出 `Illegal Instruction`
- Svvptc：分配页表项可以被硬件观察到，省去 SFENCE.VMA/SINVAL.VMA
- Sspm：特权级自身指针掩码支持

#### 可选扩展（定位模糊的临时选项）

无

## RVB23 配置规范规格差异

基础指令集以及建议实现与 RVA 一致。

### RVB23U64 Profile

#### 强制扩展

没有 Zfhmin V Zvfhmin Zvbb Zvkt，没有 Supm

#### 可选扩展（地区政策原因）

- RVA23U64 的地区可选扩展 Zvkng Zvksg

新增的可选扩展：

- Zvkg
- Zvknc
- Zvksc
- Zkn
- Zks

#### 可选扩展（未来可能强制）

没有 Zvbc

#### 可选扩展（易被探查）

- 在 RVA23U64 中是必要扩展：
  - Zfhmin Half-precision floating-point.
  - V Vector extension.
  - Zvfhmin Vector minimal half-precision floating-point.
  - Zvbb Vector basic bit-manipulation instructions.
  - Zvkt Vector data-independent execution latency.
  - Supm Pointer masking, with the execution environment providing a means to select PMLEN=0 and PMLEN=7 at minimum.
- 其他所有 RVA23U64 中易被探查的可选扩展
- RVA23U64 中未来可能强制，但是在 RVB23U64 中未来继续保持可被探查的扩展：Zvbc

#### 可选扩展（定位模糊的临时选项）

无（和 RVA23U64 一致）

### RVB23S64

#### 强制扩展

- 所有 RVB23U64 的强制扩展，与 RVA23U64 的差异依然是没有 V Zvfhmin Zvbb Zvkt，没有 Supm
- 特权级扩展中没有 Ssnpm 和 Sha

#### 可选扩展（地区政策原因）

无（和 RVA23U64 一致）

#### 可选扩展（未来可能被强制）

无（和 RVA23U64 一致）

#### 可选扩展（易被探查）

- 在 RVA23S64 中为强制扩展：Ssnpm，Sha
- If the hypervisor extension is implemented and pointer masking (Ssnpm) is supported then henvcfg. PME must support at minimum, settings PMLEN=0 and PMLEN=7.

## 结论

我们的目标可以是 RVB23（对标 SiFive P550 GEN3），同 RVA23 相比有四个区别：

- 相比于 RVA23，RVB23 的 V 和 H 都不是强制性的
- 相比于 RVA23，RVB23 的半精度浮点转换不是强制性的
- 相比于 RVA23，RVB23 的指针掩码（Pointer Masking）机制不是必须的
- 相比于 RVA23，RVB23 的地区性可选加密扩展多了一些标量和低成本扩展
