package sigil.metals

import rapid.Task
import sigil.{Sigil, TurnContext}
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

import java.nio.file.Path

/**
 * Shared utilities for the Metals lifecycle tools — host-instance
 * resolution, workspace lookup, and the standard `Role.Tool` reply
 * shape. Centralized so each tool's `execute` body stays focused on
 * the actual lifecycle action rather than the boilerplate.
 */
private[metals] object MetalsToolSupport {

  /** Cast the tool's host to a [[MetalsSigil]] if possible. Returns
    * `None` for installations that registered the lifecycle tools
    * without mixing the trait in (defensive — shouldn't happen in
    * practice, but the cast crash would be opaque). */
  def manager(sigil: Sigil): Option[MetalsManager] = sigil match {
    case ms: MetalsSigil => Some(ms.metalsManager)
    case _               => None
  }

  /** Resolve the workspace for the conversation via
    * [[MetalsSigil.metalsWorkspace]]. Returns `Left(message)` when
    * the host doesn't include MetalsSigil, when the conversation
    * has no workspace mapping (`None` from the hook), or when the
    * mapping resolves to a non-existent path. The Right branch
    * carries the canonical absolute path. */
  def workspaceFor(sigil: Sigil, ctx: TurnContext): Task[Either[String, Path]] = sigil match {
    case ms: MetalsSigil =>
      ms.metalsWorkspace(ctx.conversation.id).map {
        case None =>
          Left(s"No Metals workspace configured for conversation ${ctx.conversation.id.value} — " +
               "the app's MetalsSigil.metalsWorkspace returned None.")
        case Some(path) =>
          val canonical = path.toAbsolutePath.normalize
          if (!java.nio.file.Files.isDirectory(canonical))
            Left(s"Metals workspace $canonical isn't a directory.")
          else
            Right(canonical)
      }
    case _ =>
      Task.pure(Left(
        "This Sigil instance doesn't include sigil-metals — mix in MetalsSigil to enable Metals lifecycle tools."
      ))
  }

  /** Build a `Role.Tool` Message reply with the supplied text. */
  def reply(context: TurnContext, text: String, isError: Boolean = false): Event =
    Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      visibility     = MessageVisibility.All
    )
}
