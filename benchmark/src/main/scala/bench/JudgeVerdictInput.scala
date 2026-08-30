package bench

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Typed verdict shape for [[BenchJudge]]. A structured tool input
 * rather than free text so a mid-size local model produces a parseable
 * judgment every time — the same reason every framework-internal
 * consult in Sigil is tool-shaped.
 *
 *   - `correct` — does the response convey the gold answer?
 *   - `reasoning` — one line; kept in the per-question report so a
 *     disputed verdict can be read rather than re-run.
 */
case class JudgeVerdictInput(correct: Boolean, reasoning: String) extends ToolInput derives RW
