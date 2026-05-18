package sigil.tooling.types

import fabric.rw.*

/**
 * Tool-emission shape for `bsp_compile`. The agent inspects
 * `status` (OK / ERROR / CANCELLED) and iterates `diagnostics` per
 * target / file rather than regex-parsing rendered text.
 */
case class BspCompileResult(projectRoot: String,
                            status: String,
                            targetCount: Int,
                            diagnostics: List[BspDiagnostic])
  derives RW
