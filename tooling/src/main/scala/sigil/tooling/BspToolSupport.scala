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
 */
trait BspToolSupport {
  protected def manager: BspManager

  protected def withSession(projectRoot: String, context: TurnContext)
                           (body: BspSession => Task[String]): Stream[Event] = {
    val task = manager.session(projectRoot).flatMap { session =>
      body(session).map(text => reply(context, text, isError = false))
    }.handleError { e =>
      Task.pure(reply(context, s"BSP error: ${e.getMessage}", isError = true))
    }
    Stream.force(task.map(Stream.emit))
  }

  /** Resolve a user-supplied target list — empty means "everything
    * in the workspace". The server roundtrip is only paid when the
    * input list is empty; explicit URIs short-circuit. */
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
}
