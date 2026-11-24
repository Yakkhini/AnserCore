package ansercore.backend.issue

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._
import ansercore.backend.decode.{AnsDecode, AnsFpDecode, AnsZfaDecode}

class AnsIssueParamsSpec extends AnyFlatSpec with Matchers {
  private val params = BundleParams()
  private val exu    = AnsIssueParams.intExus.head
  private val iq     = AnsIssueParams.intScheduler.iqs.head

  behavior of "Issue parameter validation"

  it should "accept the current topology and reject duplicate FU mappings before toMap" in {
    noException should be thrownBy AnsIssueParams.validate(params)
    val error = intercept[IllegalArgumentException] {
      exu.copy(fuConfigs = exu.fuConfigs :+ exu.fuConfigs.head.copy(latency = AnsCertainLatency(3)))
    }
    error.getMessage should include("FU types must be unique")
  }

  it should "reject source, port, latency and encoding shapes that cannot fit the datapath" in {
    intercept[IllegalArgumentException](exu.copy(intRdPorts = Seq(-2, 1, -1)))
    intercept[IllegalArgumentException](exu.copy(intRdPorts = Seq(AnsIssueParams.nIntRd, 1, -1)).validate(params))
    intercept[IllegalArgumentException](exu.copy(intWbPort = AnsIssueParams.nIntWb).validate(params))
    intercept[IllegalArgumentException](exu.copy(fpWbPort = AnsIssueParams.nFpWb).validate(params))
    intercept[IllegalArgumentException] {
      exu
        .copy(fuConfigs = Seq(exu.fuConfigs.head.copy(latency = AnsCertainLatency(params.maxLatency + 1))))
        .validate(params)
    }
    intercept[IllegalArgumentException](exu.validate(params.copy(numSrc = 4)))
    intercept[IllegalArgumentException](AnsIssueParams.validate(params.copy(maxExuCount = AnsIssueParams.nExu - 1)))
    intercept[IllegalArgumentException](AnsIssueParams.validate(params.copy(loadPipelineWidth = 1)))
  }

  it should "derive FEX writeback latency maps by PRF domain" in {
    val fex0 = AnsIssueParams.exuByIndex(AnsIssueParams.exuIdxOf("FEX0"))
    val fex2 = AnsIssueParams.exuByIndex(AnsIssueParams.exuIdxOf("FEX2"))
    val mul0 = AnsIssueParams.exuByIndex(AnsIssueParams.exuIdxOf("MUL0"))

    fex0.intWbLatencyMap shouldBe Map(FuType.falu -> 1, FuType.fcvt -> 2)
    fex2.intWbLatencyMap shouldBe Map(FuType.falu -> 1)
    fex0.fpWbLatencyMap shouldBe Map(
      FuType.falu -> 1,
      FuType.fmac -> 3,
      FuType.fcvt -> 2,
    )
    fex0.intWbPort shouldBe 1
    fex0.intWbPriority shouldBe 2
    mul0.intWbPort shouldBe 1
    mul0.uncertainIntWbPort shouldBe 3
    mul0.intWbPriority shouldBe 1
  }

  it should "reserve the decoded writeback domain of every fixed floating point instruction on every eligible EXU" in {
    val rows = (AnsFpDecode.decodeArray ++ AnsZfaDecode.decodeArray).map(_._2).collect {
      case row: AnsDecode if Set(FuType.falu, FuType.fmac, FuType.fcvt).contains(row.fuType) => row
    }
    rows.count(r => r.fuType == FuType.falu && r.intWen) should be >= 8
    for {
      row   <- rows
      owner <- AnsIssueParams.fpExus if owner.fuTypes.contains(row.fuType)
    } {
      withClue(s"${owner.name} fu=${row.fuType} op=${row.fuOpType}: ") {
        if (row.intWen) owner.intWbLatencyMap.get(row.fuType) shouldBe owner.fuLatencyMap.get(row.fuType)
        if (row.fpWen) owner.fpWbLatencyMap.get(row.fuType) shouldBe owner.fuLatencyMap.get(row.fuType)
      }
    }
  }

  it should "reject incomplete, extra, fixed or FP-writing members of the two uncertain groups" in {
    val owner   = AnsIssueParams.uncertainOwner
    val grouped = owner.uncertainGroups.flatten
    grouped.distinct.size shouldBe grouped.size
    owner.uncertainGroups.size shouldBe 2
    for (fu <- grouped) {
      intercept[IllegalArgumentException](owner.copy(fuConfigs = owner.fuConfigs.filterNot(_.fuType == fu)))
      intercept[IllegalArgumentException] {
        owner.copy(fuConfigs =
          owner.fuConfigs.map(c => if (c.fuType == fu) c.copy(latency = AnsCertainLatency(2)) else c)
        )
      }
      intercept[IllegalArgumentException] {
        owner.copy(fuConfigs = owner.fuConfigs.map(c => if (c.fuType == fu) c.copy(writesFpRf = true) else c))
      }
    }
    intercept[IllegalArgumentException] {
      owner.copy(fuConfigs = owner.fuConfigs :+ AnsFuConfig("extra", FuType.fdiv, AnsUncertainLatency))
    }
  }

  it should "reject uncertain completion ports shared with any EXU or reserved for loads" in {
    val exus  = AnsIssueParams.allExus
    val owner = AnsIssueParams.uncertainOwner
    noException should be thrownBy AnsIssueParams.validateCompletionTopology(exus)
    for (port <- Seq(0, 1, 2, 4, 5, AnsIssueParams.nIntWb)) {
      intercept[IllegalArgumentException] {
        AnsIssueParams.validateCompletionTopology(
          exus.map(e => if (e == owner) e.copy(uncertainIntWbPort = port) else e)
        )
      }
    }
    intercept[IllegalArgumentException] {
      AnsIssueParams.validateCompletionTopology(exus.updated(0, exus.head.copy(intWbPort = owner.uncertainIntWbPort)))
    }
    intercept[IllegalArgumentException](AnsIssueParams.validateCompletionTopology(exus.filterNot(_ == owner)))
    intercept[IllegalArgumentException](AnsIssueParams.validateCompletionTopology(exus :+ owner.copy(name = "second")))
  }

  it should "reject inconsistent scheduler membership and unavailable wakeup sources" in {
    intercept[IllegalArgumentException](iq.copy(schdType = AnsFpScheduler))
    intercept[IllegalArgumentException](AnsSchedulerParams(AnsFpScheduler, Seq(iq)))
    intercept[IllegalArgumentException](iq.copy(wakeupSourceExuIdx = Seq(0, 0)))
    intercept[IllegalArgumentException](iq.copy(wakeupSourceExuIdx = Seq(AnsIssueParams.nExu)).validate(params))
    intercept[IllegalArgumentException] {
      iq.copy(wakeupSourceExuIdx = Seq(AnsIssueParams.memExus.head.exuIdx)).validate(params)
    }
    intercept[IllegalArgumentException](iq.copy(numEntries = AnsIssueParams.maxEntries + 1).validate(params))
  }
}
