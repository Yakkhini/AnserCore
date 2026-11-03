/* 雁栖湖 rename 自包含的接口 bundle。
 *
 * 这些 bundle 对应设计文档 4.2 节的接口列表。相比昆明湖：
 *   - RabCommitInfo 去掉了 vecWen/v0Wen/vlWen，只保留 rfWen/fpWen/isMove；
 *   - SnapshotPort 的宽度由本目录参数给出，不依赖全局 RenameSnapshotNum；
 *   - 新增 AnsFusionInfo，用于标量宏融合后的源 2 选择(见 AnsRename 顶层)。
 */
package ansercore.backend.rename

import chisel3._

import ansercore.backend.Bundles.{BundleParams, FusionInfo}
import AnsRenameParams._

/** RAT 早读请求：与 decode 的 `AnsRatReadRequest` 同形，数据留在 rename 内部使用。 */
class AnsRatReadRequest(val addrWidth: Int) extends Bundle {
  val hold = Bool()
  val addr = UInt(addrWidth.W)
}

/** RAT 读端口：地址由译码级驱动，数据由 RAT 返回。 */
class AnsRatReadPort(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val hold = Input(Bool())
  val addr = Input(UInt(addrWidth.W))
  val data = Output(UInt(dataWidth.W))
}

/** RAT 写端口：推测写(重命名/walk)与架构写(提交)共用。 */
class AnsRatWritePort(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val wen  = Bool()
  val addr = UInt(addrWidth.W)
  val data = UInt(dataWidth.W)
}

/** 一条提交/回滚项中与重命名表、FreeList 有关的信息。 */
class AnsRabCommitInfo(val params: BundleParams = BundleParams()) extends Bundle {
  val ldest  = UInt(params.logicalRegWidth.W)
  val pdest  = UInt(params.physicalRegWidth.W)
  val rfWen  = Bool()
  val fpWen  = Bool()
  val isMove = Bool()
}

/** ROB 回传给 rename 的提交/回滚信息。 */
class AnsRabCommitIO(val params: BundleParams = BundleParams()) extends Bundle {
  val isCommit    = Bool()
  val commitValid = Vec(rabCommitWidth, Bool())
  val isWalk      = Bool()
  val walkValid   = Vec(rabCommitWidth, Bool())
  val info        = Vec(rabCommitWidth, new AnsRabCommitInfo(params))
}

/** RAT / FreeList 快照的入队、出队、恢复与冲刷控制。 */
class AnsSnapshotPort(val params: BundleParams = BundleParams()) extends Bundle {
  val snptEnq    = Bool()
  val snptDeq    = Bool()
  val useSnpt    = Bool()
  val snptSelect = UInt(snapshotSelectWidth.W)
  val flushVec   = Vec(renameSnapshotNum, Bool())
}

/** 抽象后的重定向信息：只需要目标 ROB 序号与「是否冲刷自身」。 */
class AnsRenameRedirect(val params: BundleParams = BundleParams()) extends Bundle {
  val robIdx      = UInt(params.robIndexWidth.W)
  val flushItself = Bool()
  val isInterrupt = Bool()
}

/** 标量宏融合后，源 2 需要从哪一路读取（与 decode 的 AnsFusionDecodeInfo 对应）。 */
class AnsFusionInfo extends FusionInfo

/** 阻塞原因，对应设计文档 4.2.1 的 stallReason 接口。 */
class AnsRenameStallReason extends Bundle {
  val integerFreeList  = Bool()
  val floatingFreeList = Bool()
  val walk             = Bool()
  val redirect         = Bool()
  val downstream       = Bool()
}
