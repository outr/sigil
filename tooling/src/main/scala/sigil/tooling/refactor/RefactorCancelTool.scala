package sigil.tooling.refactor

import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolName, TypedOutputTool}

/**
 * Drop step of the three-tool refactor session. Removes the
 * staged session from the store. Idempotent — every terminal
 * state (already-applied, already-cancelled, expired, never-existed)
 * collapses to `NotFound`, so a caller can safely cancel "just in
 * case" without checking whether a prior call already consumed the
 * session.
 */
final class RefactorCancelTool(sessionStore: RefactorSessionStore)
  extends TypedOutputTool[RefactorCancelInput, RefactorCancelOutput](
    name = ToolName("refactor_cancel"),
    description =
      """Drop a previously prepared refactor session by id. Removes the staged workspace edits
        |from the in-memory session store without writing anything to disk.
        |
        |Returns one of:
        |  - `cancelled` — the session was present and has been dropped.
        |  - `not-found` — the session id wasn't in the store (expired, already applied,
        |                  already cancelled, or never existed). Idempotent — repeated cancels
        |                  on the same id all return `not-found` after the first.""".stripMargin,
    keywords = Set("refactor", "cancel", "drop", "session", "discard", "abort")
  ) {

  override def paginate: Boolean = false

  override protected def executeTyped(input: RefactorCancelInput,
                                      ctx: TurnContext): Task[RefactorCancelOutput] = Task {
    val status =
      if (sessionStore.remove(input.sessionId)) RefactorCancelStatus.Cancelled
      else RefactorCancelStatus.NotFound
    RefactorCancelOutput(sessionId = input.sessionId, status = status)
  }
}
