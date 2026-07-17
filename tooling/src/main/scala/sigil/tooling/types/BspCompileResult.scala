package sigil.tooling.types

import fabric.rw.*

/**
 * Tool-emission shape for `bsp_compile`. The agent inspects
 * `status` (OK / ERROR / CANCELLED) and iterates `diagnostics` per
 * target / file rather than regex-parsing rendered text.
 *
 * `cause` makes an ERROR actionable when `diagnostics` is empty: a
 * request-level failure (target resolution, build import, BSP
 * connection) carries the failing stage + underlying error text, and
 * a compile that failed without publishing structured diagnostics
 * carries a bounded tail of the build server's log output. A bare
 * `{status: ERROR, diagnostics: []}` leaves the agent blind — nothing
 * in its loop can surface WHAT failed, so it thrashes (re-compile,
 * re-list, restart the server) without converging.
 */
case class BspCompileResult(projectRoot: String,
                            status: String,
                            targetCount: Int,
                            diagnostics: List[BspDiagnostic],
                            cause: Option[String] = None)
  extends sigil.tool.ToolOutput derives RW
