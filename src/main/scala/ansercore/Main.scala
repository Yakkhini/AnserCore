// SPDX-FileCopyrightText: 2026 ECOS Team <ecos-all@ict.ac.cn>
// SPDX-License-Identifier: MulanPSL-2.0

package ansercore

import ansercore.backend.AnsBackend
import circt.stage.ChiselStage

object Main extends App {
  val targetDir = args.headOption.getOrElse("build")
  ChiselStage.emitSystemVerilogFile(new AnsBackend(), Array("--target-dir", targetDir))
}
