/*
 * SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
 *
 * SPDX-License-Identifier: MulanPSL-2.0
 */

package utils

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR

abstract class ReplacementPolicy(val nWays: Int) {
  require(nWays >= 2, "requires at least two ways")

  def width:           Int
  def getReplaceWayOH: UInt

  protected def getNextState(prevState: UInt, touchWayOH: UInt): UInt
  protected def state:                                           UInt

  final def update(touchWayOH: UInt): Unit = {
    require(touchWayOH.getWidth == nWays)
    assert(
      PopCount(touchWayOH) === 1.U,
      "touchWayOH must be exactly one-hot",
    )

    state := getNextState(state, touchWayOH)
  }

  final def update(touchWayOH: Vec[Bool]): Unit = {
    assert(
      PopCount(touchWayOH) === 1.U,
      "touchWayOH must be exactly one-hot",
    )
    update(touchWayOH.asUInt)
  }

  final def update(touchWayOH: Seq[Bool]): Unit = {
    assert(
      PopCount(touchWayOH) === 1.U,
      "touchWayOH must be exactly one-hot",
    )
    update(VecInit(touchWayOH).asUInt)
  }

  final def updateMany(touchWayOHs: Seq[UInt]): Unit = {
    var tempState = state
    touchWayOHs.foreach { touch =>
      assert(
        PopCount(touch) === 1.U,
        "touchWayOH must be exactly one-hot",
      )
      require(touch.getWidth == nWays)

      tempState = getNextState(tempState, touch)
    }
    state := tempState
  }

}

class Random(nWays: Int) extends ReplacementPolicy(nWays) {
  override val width: Int            = log2Ceil(nWays)
  override def getReplaceWayOH: UInt = {
    if (isPow2(nWays)) {
      UIntToOH(state(width - 1, 0))
    } else {
      VecInit(PriorityEncoderOH(partition(state, nWays))).asUInt
    }
  }

  override protected val state: UInt                                           = LFSR(16)
  override protected def getNextState(prevState: UInt, touchWayOH: UInt): UInt = {
    throw new UnsupportedOperationException(
      "Random replacement does not support update"
    )
  }

  private def partition(value: UInt, slices: Int): Seq[Bool] =
    Seq.tabulate(slices)(i => value < (((i + 1) << value.getWidth) / slices).U)

}

class TrueLRU(nWays: Int) extends ReplacementPolicy(nWays) {
  /*
Example 4-way (6-bits):
aij 表示 i old j
   aij       way 0   way 1   way 2   way 3
  way 0       1      s0      s1      s2
  way 1      !s0      1      s3      s4
  way 2      !s1     !s3      1      s5
  way 3      !s2     !s4    !s55      1

state[5] - 2 more old than 3
state[4] - 1 more old than 3
state[3] - 1 more old than 2
state[2] - 0 more old than 3
state[1] - 0 more old than 2
state[0] - 0 more old than 1
   */
  override val width: Int                                                      = (nWays * (nWays - 1)) / 2
  override def getReplaceWayOH: UInt                                           = {
    val oldest = getOldestWayOH(VecInit.fill(nWays)(true.B).asUInt)
    oldest
  }
  override protected val state                                                 = Reg(UInt(width.W))
  override protected def getNextState(prevState: UInt, touchWayOH: UInt): UInt = {
    val nextStateVec = Wire(Vec(width, Bool()))
    for (low <- 0 until nWays - 1) {
      for (high <- low + 1 until nWays) {
        val idx = bitIndex(low, high)

        nextStateVec(idx) := Mux(
          touchWayOH(low) || touchWayOH(high),
          touchWayOH(high), // low high 不会同时访问，访问 low 的时候为 0, low 比 high 新，所以为 0, 访问 high 的时候，表示 low 比 high 旧
          prevState(idx),
        )
      }
    }

    nextStateVec.asUInt
  }

  val ageMatrix = (0 until nWays).map { i =>
    VecInit(
      (0 until nWays).map { j =>
        getAgeBit(state, i, j)
      }
    ).asUInt
  }

  private def bitIndex(low: Int, high: Int): Int = {
    require(0 <= low && low < high && high < nWays)

    val base = low * nWays - low * (low + 1) / 2

    base + high - low - 1
  }

  private def getAgeBit(state: UInt, i: Int, j: Int): Bool = {
    if (i == j) {
      true.B
    } else {
      val (low, high) =
        if (i < j) (i, j)
        else (j, i)

      val idx = bitIndex(low, high)

      if (i < j) state(idx)
      else !state(idx)
    }
  }

  def getOldestWayOH(candidate: UInt): UInt = {
    require(candidate.getWidth == nWays)
    VecInit(
      (0 until nWays).map { i =>
        candidate(i) && (ageMatrix(i) | ~candidate).andR
      }
    ).asUInt
  }
}

/*
 * The tree is numbered breadth first.  Thus a 4-way tree has
 *
 *                 state[0]
 *              state[1] state[2]
 *
 * and an 8-way tree adds state[3]..state[6].  For a non-power-of-two
 * number of ways, the same numbering is used after constructing a
 * balanced tree with exactly nWays leaves (and nWays - 1 internal nodes).
 *
 * example 5-way：

                           state[0]
                         /          \
                    state[1]      state[2]
                    /    \        /    \
                  way0  way1    way2   state[3]
                                        /   \
                                     way3  way4
  state[0] ~ state[3]
  way0 = state[0] && state[1]
  way1 = state[0] && !state[1]
  way2 = !state[0] && state[2]
  way3 = !state[0] && !state[2] && state[3]
  way4 = !state[0] && !state[2] && !state[3]
 */
class PseudoLRU(nWays: Int) extends ReplacementPolicy(nWays) {
  override val width: Int            = nWays - 1
  override def getReplaceWayOH: UInt =
    VecInit((0 until nWays).map(way => choose(tree, way))).asUInt

  override protected def getNextState(prevState: UInt, touchWayOH: UInt): UInt = {
    val nextState = Wire(Vec(width, Bool()))

    def updateNode(node: TreeNode): Unit = {
      if (node.left.nonEmpty) {
        val touchedLeft  = node.left.get.ways.map(touchWayOH(_)).reduce(_ || _)
        val touchedRight = node.right.get.ways.map(touchWayOH(_)).reduce(_ || _)
        // A touched way makes the tree point to the opposite subtree.
        nextState(node.index) := Mux(touchedLeft || touchedRight, touchedRight, prevState(node.index))
        updateNode(node.left.get)
        updateNode(node.right.get)
      }
    }
    updateNode(tree)
    nextState.asUInt
  }
  override protected val state                                                 = Reg(UInt(width.W))

  final private class TreeNode(
    val ways:  Seq[Int],
    val left:  Option[TreeNode],
    val right: Option[TreeNode],
    var index: Int = -1,
  )

  private def makeTree(ways: Seq[Int]): TreeNode = {
    if (ways.size == 1) {
      new TreeNode(ways, None, None)
    } else {
      val leftSize = ways.size / 2
      new TreeNode(
        ways,
        Some(makeTree(ways.take(leftSize))),
        Some(makeTree(ways.drop(leftSize))),
      )
    }
  }

  private def numberTree(root: TreeNode): Unit = {
    val queue     = scala.collection.mutable.Queue[TreeNode](root)
    var nextIndex = 0
    while (queue.nonEmpty) {
      val node = queue.dequeue()
      if (node.left.nonEmpty) {
        node.index = nextIndex
        nextIndex += 1
      }
      node.left.foreach(queue.enqueue(_))
      node.right.foreach(queue.enqueue(_))
    }
    require(nextIndex == nWays - 1)
  }

  private def choose(node: TreeNode, way: Int): Bool = {
    if (node.left.isEmpty) {
      true.B
    } else if (node.left.get.ways.contains(way)) {
      state(node.index) && choose(node.left.get, way)
    } else {
      !state(node.index) && choose(node.right.get, way)
    }
  }

  private val tree: TreeNode = makeTree(0 until nWays)
  numberTree(tree)

}

class StaticRRIP(nWays: Int) extends ReplacementPolicy(nWays) {
  override val width: Int                                             = nWays - 1
  override def getReplaceWayOH: UInt                                  = ???
  override protected val state                                        = Reg(UInt(width.W))
  protected def getNextState(prevState: UInt, touchWayOH: UInt): UInt = ???
}

class BimodalRRIP(nWays: Int) extends ReplacementPolicy(nWays) {
  override val width: Int                                             = nWays - 1
  override def getReplaceWayOH: UInt                                  = ???
  override protected val state                                        = Reg(UInt(width.W))
  protected def getNextState(prevState: UInt, touchWayOH: UInt): UInt = ???

}

class DynamicRRIP(nWays: Int) extends ReplacementPolicy(nWays) {
  override val width: Int                                             = nWays - 1
  override def getReplaceWayOH: UInt                                  = ???
  override protected val state                                        = Reg(UInt(width.W))
  protected def getNextState(prevState: UInt, touchWayOH: UInt): UInt = ???

}

object ReplacementPolicy {
  def apply(name: String, nWays: Int): ReplacementPolicy = {
    name.toLowerCase match {
      case "random" => new Random(nWays)
      case "lru"    => new TrueLRU(nWays)
      case "plru"   => new PseudoLRU(nWays)
      case "srrip"  => new StaticRRIP(nWays)
      case "brrip"  => new BimodalRRIP(nWays)
      case "drrip"  => new DynamicRRIP(nWays)
      case _        => throw new IllegalArgumentException("Unknown ReplacementPolicy type")
    }
  }
}
