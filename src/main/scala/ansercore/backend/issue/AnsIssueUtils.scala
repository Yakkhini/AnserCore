/* Issue 域公用小工具。 */
package ansercore.backend.issue

import chisel3._
import chisel3.util._

import ansercore.backend.Bundles._
import ansercore.backend.rename.AnsRenameRedirect

object AnsIssueUtils {

  /** ROB 是 128 项、robIdx 8 bit，模距离 < 128 即为更年轻，无需额外 flag。 */
  def isYounger(robIdx: UInt, redirectRobIdx: UInt): Bool = {
    val diff = robIdx -% redirectRobIdx
    diff =/= 0.U && !diff(diff.getWidth - 1)
  }

  def needFlush(robIdx: UInt, redirect: Valid[AnsRenameRedirect]): Bool = {
    val younger = isYounger(robIdx, redirect.bits.robIdx)
    val itself  = redirect.bits.flushItself && robIdx === redirect.bits.robIdx
    redirect.valid && (younger || itself)
  }

  /** Advance exactly once at a cycle boundary; issue metadata is already aged for data0. */
  def shiftLoadDependency(dep: Vec[UInt]): Vec[UInt] =
    VecInit(dep.map(x => (x << 1)(x.getWidth - 1, 0)))

  def mergeLoadDependency(deps: Seq[Vec[UInt]]): Vec[UInt] = {
    val width = deps.head.length
    VecInit((0 until width).map { i =>
      deps.map(_(i)).reduce(_ | _)
    })
  }

  def isLoadCancelled(deps: Vec[UInt], ldCancel: Vec[LoadCancel]): Bool =
    LoadShouldCancel(deps, ldCancel)
}
