package sigil.tool.util

import rapid.Stream
import sigil.{SpaceId, TurnContext}
import sigil.conversation.{ContextMemory, MemorySource, UpsertMemoryResult}
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, SaveMemoryInput}
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Surface [[sigil.Sigil.upsertMemoryByKey]] (or `persistMemory` when
 * no key is supplied) as an LLM-callable tool. Apps that don't want
 * agents writing to memory directly should not register this tool;
 * memory is also writable through `Sigil`'s programmatic API.
 *
 * `space` is fixed at construction — apps that want per-call space
 * scoping construct multiple instances of the tool with different
 * `space` arguments and route via mode / behavior.
 */
final class SaveMemoryTool(space: SpaceId,
                           source: MemorySource = MemorySource.Explicit)
  extends TypedTool[SaveMemoryInput](
    name = ToolName("save_memory"),
    description =
      """Persist a fact for later retrieval. Required: `fact` + `label` (short title) + `summary`
        |(one-line gist). Pass `key` to overwrite a previously-saved memory under that key (versioned
        |upsert); omit `key` to append a new memory.""".stripMargin,
    examples = List(
      ToolExample(
        "Save a user preference",
        SaveMemoryInput(
          fact = "User prefers metric units across all generated documents.",
          label = "Unit preference",
          summary = "User prefers metric units.",
          key = Some("user.units")
        )
      ),
      ToolExample(
        "Append a new fact",
        SaveMemoryInput(
          fact = "Project deadline is 2026-05-15.",
          label = "Project deadline",
          summary = "Deadline 2026-05-15."
        )
      )
    ),
    keywords = Set("memory", "save", "remember", "store", "persist", "fact")
  ) {

  override val requiresAccessibleSpaces: Boolean = true

  override protected def executeTyped(input: SaveMemoryInput, ctx: TurnContext): Stream[Event] = Stream.force {
    resolveSpace(input.space, ctx).flatMap { resolvedSpace =>
      val mem = ContextMemory(
        fact       = input.fact,
        label      = input.label,
        summary    = input.summary,
        source     = source,
        spaceId    = resolvedSpace,
        key        = input.key,
        pinned     = input.permanence.contains(sigil.conversation.Permanence.Always),
        keywords   = input.keywords,
        memoryType = input.memoryType
      )
      val saved = input.key match {
        case Some(_) =>
          ctx.sigil.upsertMemoryByKeyFor(mem, ctx.chain, ctx.conversation.id).map { r =>
            val outcome = r match {
              case _: UpsertMemoryResult.Stored    => "Stored"
              case _: UpsertMemoryResult.Refreshed => "Refreshed"
              case _: UpsertMemoryResult.Versioned => "Versioned (prior archived)"
            }
            s"$outcome memory ${r.memory._id.value}."
          }
        case None =>
          ctx.sigil.persistMemoryFor(mem, ctx.chain, ctx.conversation.id)
            .map(stored => s"Stored memory ${stored._id.value}.")
      }
      saved.map { text =>
        Stream.emit[Event](Message(
          participantId  = ctx.caller,
          conversationId = ctx.conversation.id,
          topicId        = ctx.conversation.currentTopicId,
          content        = Vector(ResponseContent.Text(text)),
          state          = EventState.Complete,
          role           = MessageRole.Tool
        ))
      }
    }
  }

  /** Resolve the agent's space hint to a concrete [[SpaceId]]. When
    * the hint is omitted or doesn't match an accessible space, fall
    * back to the tool's default `space` and let the framework's
    * classifier decide if the caller left it at GlobalSpace. */
  private def resolveSpace(hint: Option[String], ctx: TurnContext): rapid.Task[SpaceId] = hint match {
    case None => rapid.Task.pure(space)
    case Some(value) =>
      ctx.sigil.accessibleSpaces(ctx.chain).map { accessible =>
        accessible.find(_.value == value.trim).getOrElse(space)
      }
  }

}
