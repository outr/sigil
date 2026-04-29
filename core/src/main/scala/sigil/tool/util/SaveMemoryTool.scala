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
      """Persist a fact for later retrieval. Pass `key` to overwrite a previously-saved memory under that
        |key (versioned upsert); omit `key` to append a new memory. `label` (short title) and `summary`
        |(one-line gist) populate surfaced metadata.""".stripMargin,
    examples = List(
      ToolExample("Save a user preference", SaveMemoryInput(fact = "User prefers metric units", key = Some("user.units"))),
      ToolExample("Append a new fact", SaveMemoryInput(fact = "Project deadline is 2026-05-15."))
    ),
    keywords = Set("memory", "save", "remember", "store", "persist", "fact")
  ) {
  override protected def executeTyped(input: SaveMemoryInput, ctx: TurnContext): Stream[Event] = {
    val mem = ContextMemory(
      fact    = input.fact,
      source  = source,
      spaceId = space,
      key     = input.key.getOrElse(""),
      label   = input.label.getOrElse(""),
      summary = input.summary.getOrElse("")
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
    Stream.force(saved.map { text =>
      Stream.emit[Event](Message(
        participantId  = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId        = ctx.conversation.currentTopicId,
        content        = Vector(ResponseContent.Text(text)),
        state          = EventState.Complete,
        role           = MessageRole.Tool
      ))
    })
  }
}
