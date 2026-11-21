package ansercore.backend.rob

import ansercore.backend.Bundles.BundleParams

/** Compatibility aliases for downstream source code; new integrations should use AnsRob. */
class AnsRobStubIO(override val params: BundleParams = BundleParams())
    extends AnsRobIO(params, AnsRobParams.from(params))

class AnsRobStub(override val params: BundleParams = BundleParams()) extends AnsRob(params)
