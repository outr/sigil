package sigil.tooling

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Shared plumbing for the agent-facing BSP tools. Mirrors
 * [[LspToolSupport]] — a `withSession` block that handles
 * config-not-found / spawn-failure / RPC-error and a `reply` helper
 * that emits a Message event back into the agent's signal stream.
 *
 * `targetsFromInput` resolves the agent's input — which carries
 * either explicit URIs or empty (meaning "every workspace target") —
 * to a list of [[BuildTargetIdentifier]]s, fetching from the server
 * if necessary. Tools fold this into their pipeline so the
 * "compile / test all targets" shorthand works.
 *
 * Sigil bug #85 — sets `toolchain = Some("bsp")` on every mixed-in
 * tool so apps that register `"bsp"` in [[sigil.Sigil.activeToolchains]]
 * (Metals running for a Scala project, etc.) get the build-server
 * tools ranked above generic verbs for inspection-shaped queries.
 */
trait BspToolSupport extends sigil.tool.Tool {
  protected def manager: BspManager

  override def toolchain: Option[String] = Some("bsp")

  protected def withSession(projectRoot: String, context: TurnContext)(body: BspSession => Task[String]): Stream[Event] = {
    val task = manager.session(projectRoot).flatMap { session =>
      installProgressCallback(session, context)
      body(session)
        .map(text => reply(context, text, isError = false))
        .guarantee(Task(session.client.setStatusCallback(None)))
    }.handleError { e =>
      Task.pure(reply(context, s"BSP error: ${e.getMessage}", isError = true))
    }
    Stream.force(task.map(Stream.emit))
  }

  /**
   * Route BSP-server notifications (log lines, taskStart /
   * taskProgress / taskFinish, showMessage) through the active
   * tool's [[sigil.signal.ToolProgress]] channel. The callback is
   * installed for the duration of `body` and cleared on exit so
   * concurrent tool calls in the same session don't see stale
   * text.
   */
  private def installProgressCallback(session: BspSession, context: TurnContext): Unit =
    session.client.setStatusCallback(Some(text =>
      context.reportProgress(text).handleError(_ => Task.unit).startUnit()))

  /**
   * Resolve a user-supplied target list — empty means "everything
   * in the workspace". The server roundtrip is only paid when the
   * input list is empty; explicit URIs short-circuit.
   */
  protected def targetsFromInput(session: BspSession, requested: List[String]): Task[List[BuildTargetIdentifier]] =
    if (requested.nonEmpty) Task.pure(requested.map(uri => new BuildTargetIdentifier(uri)))
    else session.workspaceBuildTargets.map(_.map(_.getId))

  protected def reply(context: TurnContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )

  /**
   * Typed variant for tools extending `TypedOutputTool[I, O]`. Runs
   * `body` against a session and returns its typed `Output`. Errors
   * (config / spawn / RPC failures) get routed to the caller's
   * `onError` mapping — typically a sentinel variant on the tool's
   * Output type.
   */
  protected def withSessionTyped[Output](projectRoot: String,
                                         context: TurnContext,
                                         onError: String => Output)(body: BspSession => Task[Output]): Task[Output] =
    manager.session(projectRoot).flatMap { session =>
      installProgressCallback(session, context)
      body(session).guarantee(Task(session.client.setStatusCallback(None)))
    }.handleError(e => Task.pure(onError(s"BSP error: ${e.getMessage}")))
}
