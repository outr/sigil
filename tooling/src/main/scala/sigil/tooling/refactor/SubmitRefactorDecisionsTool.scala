package sigil.tooling.refactor

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolInput, ToolName, TypedTool}

/**
 * Worker-facing tool for [[RefactorWithInstructionTool]]. A per-file
 * worker (a `SigilAgentDecisionStep` run spawned by the main
 * refactor tool) invokes this with a typed
 * `List[MatchDecision]` describing how each grep hit should be
 * handled. The framework's strict-mode tool dispatch validates the
 * payload against the schema; hallucinations (out-of-range lines,
 * malformed enum values) get rejected at decode time per the
 * existing `ToolInputValidator` path.
 *
 * The tool's `execute` is intentionally a no-op — its sole purpose
 * is to surface the typed input via the framework's standard
 * ToolInvoke event. The refactor tool reads back the decisions by
 * walking the worker conversation's events for ToolInvoke entries
 * named `submit_refactor_decisions` and extracting the typed
 * `input`.
 *
 * Sigil bug #212.
 */
case object SubmitRefactorDecisionsTool
  extends TypedTool[SubmitRefactorDecisionsInput](
    name = ToolName("submit_refactor_decisions"),
    description =
      """Submit your per-match decisions for the file you were given. Call this exactly once with
        |the full list of decisions, then finish your turn with the standard worker-termination
        |tool call.
        |
        |For each match (one per line passed in the brief), choose one action:
        |  - `Edited` — the change should be applied. Set `newText` to the replacement,
        |    `startChar` and `endChar` to the 0-based character range on that line.
        |  - `Skipped` — leave this match unchanged. Set `reason` to explain (e.g. "match is
        |    inside a string literal", "instruction doesn't apply here").
        |  - `Failed` — you can't make a clean decision. Set `reason`.
        |
        |`oldText` MUST be the exact text currently at the match location — the framework uses it
        |to verify the edit hasn't drifted before writing. Mismatched `oldText` aborts the file's
        |write with a clear error.""".stripMargin,
    keywords = Set("submit", "decisions", "refactor", "worker", "matches")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: SubmitRefactorDecisionsInput,
                                      context: TurnContext): Stream[Event] = Stream.empty
}

/** Typed input for [[SubmitRefactorDecisionsTool]]. */
case class SubmitRefactorDecisionsInput(filePath: String,
                                        decisions: List[MatchDecision]) extends ToolInput derives RW
