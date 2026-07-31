package sigil.tooling

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Shared plumbing for the agent-facing BSP tools. Mirrors
 * [[LspToolSupport]] — a `withSessionTyped` block that handles
 * config-not-found / spawn-failure / RPC-error and a `reply` helper
 * that emits a Message event back into the agent's signal stream.
 *
 * `targetsFromInput` resolves the agent's input — which carries
 * either explicit URIs or empty (meaning "every workspace target") —
 * to a list of [[BuildTargetIdentifier]]s, fetching from the server
 * if necessary. Tools fold this into their pipeline so the
 * "compile / test all targets" shorthand works.
 *
 */
trait BspToolSupport extends sigil.tool.Tool {
  protected def manager: BspManager

  /** Guard the BSP tool's input against schema-leaked placeholder
    * values ("projectRoot": "string", etc.) — see
    * [[sigil.tool.PlaceholderInputDetector]]. Returns a placeholder-
    * rejection failure when any of `fields` is a recognised
    * placeholder; returns `None` to let the tool run. Tools call this
    * in their `executeResult` prelude before `withSessionTyped`. */
  protected def validatePlaceholders(fields: (String, String)*): Option[String] =
    sigil.tool.PlaceholderInputDetector.validateNoPlaceholders(fields*)

  /** Route BSP-server notifications (log lines, taskStart /
    * taskProgress / taskFinish, showMessage) through the active
    * tool's [[sigil.signal.ToolProgress]] channel. The callback is
    * installed for the duration of `body` and cleared on exit so
    * concurrent tool calls in the same session don't see stale
    * text. */
  private def installProgressCallback(session: BspSession, context: ToolContext): Unit =
    session.client.setStatusCallback(Some(text =>
      context.reportProgress(text).handleError(_ => Task.unit).startUnit()
    ))

  /** Resolve a user-supplied target list — empty means "everything
    * in the workspace". The server roundtrip is only paid when the
    * input list is empty; explicit URIs short-circuit. */
  protected def targetsFromInput(session: BspSession, requested: List[String]): Task[List[BuildTargetIdentifier]] =
    if (requested.nonEmpty) Task.pure(requested.map(uri => new BuildTargetIdentifier(uri)))
    else session.workspaceBuildTargets.map(_.map(_.getId))

  /** Emit a `Role.Tool` Message event carrying `text` back into the
    * agent's signal stream — the reply primitive for raw-`Tool` BSP
    * subclasses that don't return a typed `Output`. */
  protected def reply(context: ToolContext, text: String, isError: Boolean): Event =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      role = MessageRole.Tool,
      visibility = MessageVisibility.All
    )

  /** Typed variant for tools whose `executeResult` returns a typed
    * `Output`. Runs `body` against a session and returns its typed
    * `Output`. Errors
    * (config / spawn / RPC failures) get routed to the caller's
    * `onError` mapping — typically a sentinel variant on the tool's
    * Output type. */
  protected def withSessionTyped[Output](projectRoot: String,
                                         context: ToolContext,
                                         onError: String => Output)
                                        (body: BspSession => Task[Output]): Task[Output] =
    validatePlaceholders("projectRoot" -> projectRoot) match {
      case Some(reason) => Task.pure(onError(reason))
      case None =>
        manager.session(projectRoot).flatMap { session =>
          installProgressCallback(session, context)
          body(session).guarantee(Task(session.client.setStatusCallback(None)))
        }.handleError(e => Task.pure(onError(s"BSP error: ${e.getMessage}")))
    }

  /** Absorbs the session-acquire + `targetsFromInput` resolve +
    * empty-target short-circuit shared by every "operate on a list of
    * targets" BSP tool. `requested` is the agent's `targets` input
    * (empty means "every workspace target"); `emptyResult` is the
    * value returned when the resolved set is empty; `call` runs the
    * actual RPC against the non-empty target list. Both the error
    * path and the empty path resolve to a single `emptyResult` /
    * `onError` value so the per-tool body is just the RPC + its
    * result mapping. */
  protected def withTargets[Output](projectRoot: String,
                                    context: ToolContext,
                                    requested: List[String],
                                    onError: String => Output,
                                    emptyResult: => Output)
                                   (call: (BspSession, List[BuildTargetIdentifier]) => Task[Output]): Task[Output] =
    withSessionTyped[Output](projectRoot, context, onError) { session =>
      // Explicit URIs are validated against the workspace's real target
      // set BEFORE the RPC ships. The agent constructs target URIs from
      // `bsp_list_targets` output; a typo'd or stale URI used to sail
      // into the server and come back as an opaque request failure —
      // the onError message here names the unmatched URI(s) AND the
      // valid set so the agent can self-correct. Costs one
      // `workspaceBuildTargets` roundtrip on explicit-target calls
      // (cheap — the server caches it).
      session.workspaceBuildTargets.map(_.map(_.getId)).flatMap { workspace =>
        BspToolSupport.validateRequestedTargets(requested, workspace) match {
          case Left(reason)   => Task.pure(onError(reason))
          case Right(Nil)     => Task.pure(emptyResult)
          case Right(targets) => call(session, targets)
        }
      }
    }
}

object BspToolSupport {

  /** Resolve the agent's requested target URIs against the workspace's
    * real target set. Empty `requested` means "every workspace target".
    * Returns `Left(reason)` naming the unmatched URI(s) and the valid
    * set when any explicit URI doesn't resolve — the actionable message
    * the tools' `onError` mapping carries to the agent. */
  def validateRequestedTargets(requested: List[String],
                               workspace: List[BuildTargetIdentifier]): Either[String, List[BuildTargetIdentifier]] =
    if (requested.isEmpty) Right(workspace)
    else {
      val validUris = workspace.iterator.map(_.getUri).toSet
      val unmatched = requested.filterNot(validUris.contains)
      if (unmatched.isEmpty) Right(requested.map(uri => new BuildTargetIdentifier(uri)))
      else Left(
        s"no build target matched ${unmatched.map(u => s"`$u`").mkString(", ")} — " +
          s"valid targets: ${workspace.map(_.getUri).mkString(", ")}")
    }
}
