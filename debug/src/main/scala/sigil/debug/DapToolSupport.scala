package sigil.debug

import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.ToolResult

/**
 * Shared plumbing for the agent-facing DAP tools. Provides a
 * `withSession` block that looks up the active session by id and
 * surfaces a clear [[ToolResult.Failure]] when none exists, mapping a
 * thrown error from the body to a recoverable failure as well.
 */
trait DapToolSupport {
  protected def manager: DapManager

  /**
   * Run `body` against the named session, resolving its typed
   * result. If no session with that id is active, resolve a
   * [[ToolResult.Failure]] pointing the agent at `dap_launch`. A
   * thrown error from `body` becomes a recoverable failure.
   */
  protected def withSession[O](sessionId: String, context: ToolContext)(body: DapSession => Task[ToolResult[O]]): Task[ToolResult[O]] =
    manager.get(sessionId) match {
      case None =>
        Task.pure(ToolResult.failure(
          message = s"No active debug session '$sessionId'.",
          hint = Some("Launch one with `dap_launch` first.")
        ))
      case Some(session) =>
        body(session).handleError(e => Task.pure(ToolResult.failure(s"DAP error: ${e.getMessage}")))
    }
}
