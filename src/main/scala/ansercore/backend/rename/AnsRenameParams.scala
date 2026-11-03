/* 雁栖湖 (AnserCore) 后端重命名模块：自包含参数。
 *
 * 这里只列出「重命名」需要的硬件常量。流水线契约(DecodedInst/DynInst)仍复用
 * ansercore.backend.Bundles，避免与前端/译码各自定义一套宽度而漂移。
 */
package ansercore.backend.rename

import chisel3.util.log2Ceil

import ansercore.backend.Bundles.BundleParams

/** 雁栖湖 rename 的硬件形状。
  *
  * 与昆明湖(kunminghu-v2)相比，这里只保留标量整数/浮点两类寄存器：
  *   - 去掉向量 v0/vl/vp 及其 FreeList / RAT 实例；
  *   - 去掉 SSIT / WaitTable(存储集预测)相关端口；
  *   - 只支持 RVB23 标量，不含虚拟化(不做 VS 影子状态)。
  */
object AnsRenameParams {

  /** 每周期提交/回滚的指令数，与 ROB 回传的 RabCommitWidth 一致。 */
  val rabCommitWidth = 4

  /** 每周期最多重命名 / 分配的指令数，等于 Bundles.renameWidth 默认值。 */
  val renameWidth = BundleParams().renameWidth

  /** 整数/浮点物理寄存器总数(含与逻辑寄存器重合的部分)。 */
  val intPhyRegs = 180
  val fpPhyRegs  = 160

  /** 整数/浮点逻辑寄存器数。 */
  val intLogicRegs = 32
  val fpLogicRegs  = 32

  /** FreeList 管理的空闲槽数量：整数 180 个物理寄存器，浮点 160-32。 */
  val intFreeListSize = intPhyRegs
  val fpFreeListSize  = fpPhyRegs - fpLogicRegs

  /** RAT / FreeList 快照槽数。 */
  val renameSnapshotNum = 4

  /** 每条指令读 RAT 的源操作数端口数：整数 2(rs1/rs2)，浮点 3(rs1/rs2/rs3)。 */
  val intReadPorts = 2
  val fpReadPorts  = 3

  /** 快照选择索引位宽。 */
  val snapshotSelectWidth = log2Ceil(renameSnapshotNum)

  /** 压缩后的指令尺寸编码位宽。 */
  val instrSizeWidth = log2Ceil(renameWidth + 1)
}
