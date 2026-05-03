package sigil.tool.memory

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ContextMemory, MemorySource, MemoryStatus, UpsertMemoryResult}
import sigil.event.{Event, Message}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Opt-in tool: agent-driven persistence of a durable fact. Versions on
 * `(spaceId, key)` via `Sigil.upsertMemoryByKey`.
 */
case object RememberTool extends TypedTool[RememberInput](
  name = ToolName("remember"),
  description =
    """Persist a durable fact so future turns and future conversations can recall it.
      |
      |`key`     — a stable identifier for this fact, e.g. "user.preferred_language" or
      |             "project.deploy_target". Re-using a key with different `content` supersedes
      |             the prior version (the old one is archived, the new one becomes current).
      |             Re-using a key with identical `content` only refreshes metadata.
      |`label`   — human-readable short label (e.g. "Preferred language").
      |`summary` — one-sentence description of the fact.
      |`content` — the full fact text. This is what recall / retrieval searches over.
      |`tags`    — optional categorization tokens.
      |`memoryType` — Fact | Decision | Preference | ActionItem | Other.
      |`spaceId` — optional; omit to use the caller's default scope.""".stripMargin,
  examples = List(
    ToolExample(
      "Remember that the user prefers dark mode",
      RememberInput(
        key = "user.ui.theme",
        label = "UI theme preference",
        summary = "User prefers dark mode.",
        content = "The user has stated a preference for the dark UI theme across all sessions."
      )
    )
  ),
  keywords = Set("remember", "save", "store", "memory")
) {
  override protected def executeTyped(input: RememberInput, context: TurnContext): Stream[Event] =
    Stream.force {
      resolveSpace(input, context).flatMap {
        case None =>
          Task.pure(errorMessage(context,
            "remember: no memory space available. Either set Sigil.defaultMemorySpace or pass an explicit spaceId."))
        case Some(space) =>
          val memory = ContextMemory(
            fact = input.content,
            label = input.label,
            summary = input.summary,
            source = MemorySource.Explicit,
            spaceId = space,
            key = Some(input.key),
            keywords = input.keywords,
            memoryType = input.memoryType,
            status = MemoryStatus.Approved,
            conversationId = Some(context.conversation.id)
          )
          context.sigil.upsertMemoryByKey(memory).map { result =>
            val status = result match {
              case _: UpsertMemoryResult.Stored    => "stored"
              case _: UpsertMemoryResult.Refreshed => "refreshed"
              case _: UpsertMemoryResult.Versioned => "versioned"
            }
            resultMessage(context, input.key, status, result.memory)
          }
      }.map(msg => Stream.emits(List[Event](msg)))
    }

  private def resolveSpace(input: RememberInput, context: TurnContext) =
    input.spaceId match {
      case Some(s) => Task.pure(Some(s))
      case None    => context.sigil.defaultMemorySpace(context.conversation.id)
    }

  private def resultMessage(context: TurnContext, key: String, status: String, stored: ContextMemory): Message = {
    val body = status match {
      case "stored"     => s"[remember] stored $key"
      case "refreshed"  => s"[remember] refreshed $key (content unchanged; metadata updated)"
      case "versioned"  => s"[remember] updated $key (previous version archived)"
      case other        => s"[remember] $other $key"
    }
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(body))
    )
  }

  private def errorMessage(context: TurnContext, msg: String): Message =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(msg))
    )
}
