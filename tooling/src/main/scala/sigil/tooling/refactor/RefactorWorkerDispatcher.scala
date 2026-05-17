package sigil.tooling.refactor

import rapid.Task
import sigil.TurnContext
import sigil.tool.fs.GrepMatch

/**
 * Pluggable per-file worker dispatch for the prepare step of
 * [[RefactorWithInstructionTool]]. Production uses
 * [[WorkflowRefactorWorkerDispatcher]] (one Strider worker run per
 * file driving a real LLM); test fixtures inject a deterministic
 * function so the session-API tests don't need a live model.
 */
trait RefactorWorkerDispatcher {

  /** Decide for each grep match in `filePath` how the refactor
    * should apply. The framework verifies each `Edited` decision's
    * `oldText` against the current file contents before writing —
    * a stub dispatcher in tests can therefore return precomputed
    * decisions without re-reading the file. */
  def dispatch(ctx: TurnContext,
               modelId: String,
               filePath: String,
               matches: List[GrepMatch],
               instruction: String): Task[Either[String, List[MatchDecision]]]
}
