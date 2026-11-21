package ansercore.backend.rob

import chisel3.util.log2Ceil

import ansercore.backend.Bundles.BundleParams
import ansercore.backend.rename.AnsRenameParams

/** ROB-local dimensions; the active backend keeps its existing 4/4/4 widths. */
final case class AnsRobParams(
  robSize:        Int = 128,
  bankCount:      Int = 8,
  renameWidth:    Int = 4,
  commitWidth:    Int = 4,
  walkWidth:      Int = 8,
  writebackPorts: Int = 1,
) {
  private def isPowerOfTwo(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0

  require(isPowerOfTwo(robSize), "ROB size must be a power of two")
  require(isPowerOfTwo(bankCount) && bankCount > 1, "ROB needs a power-of-two bank count greater than one")
  require(robSize > bankCount, "each ROB bank needs at least two rows")
  require(renameWidth > 0 && renameWidth <= bankCount, "enqueue width must fit one bank line")
  require(isPowerOfTwo(commitWidth) && commitWidth <= bankCount, "commit width must fit one bank line")
  require(walkWidth == bankCount, "Walk scans one complete bank line")
  require(writebackPorts > 0, "ROB needs at least one writeback port")
  require(robSize > 3 * renameWidth, "ROB must leave room for dispatch reservations")

  val rabCommitWidth: Int    = AnsRenameParams.rabCommitWidth
  val valueWidth: Int        = log2Ceil(robSize)
  val ptrWidth: Int          = valueWidth + 1
  val bankEntries: Int       = robSize / bankCount
  val bankAddrWidth: Int     = log2Ceil(bankCount)
  val rowAddrWidth: Int      = log2Ceil(bankEntries)
  val occupancyWidth: Int    = log2Ceil(robSize + 1)
  val enqueueCountWidth: Int = log2Ceil(renameWidth + 1)
  val commitCountWidth: Int  = log2Ceil(commitWidth + 1)
}

object AnsRobParams {
  def from(params: BundleParams, writebackPorts: Int = 1): AnsRobParams = {
    require(params.renameWidth == AnsRenameParams.rabCommitWidth, "Rename/RAB currently require matching widths")
    val config = AnsRobParams(
      renameWidth    = params.renameWidth,
      commitWidth    = params.renameWidth,
      writebackPorts = writebackPorts,
    )
    require(params.robIndexWidth == config.ptrWidth, "BundleParams.robIndexWidth must match AnsRobPtr")
    config
  }
}
