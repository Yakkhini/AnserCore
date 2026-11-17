package ansercore.backend.issue

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ansercore.backend.Bundles._

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
