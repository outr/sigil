package sigil.tool.util

import fabric.io.JsonFormatter
import fabric.{bool, num, obj, str}
import rapid.Stream
import sigil.{SpaceId, TurnContext}
import sigil.conversation.{ContextMemory, MemorySource}
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
        ctx.sigil.upsertMemoryByKey(mem).map { r =>
          val outcome = r match {
            case _: sigil.conversation.UpsertMemoryResult.Stored     => "stored"
            case _: sigil.conversation.UpsertMemoryResult.Refreshed  => "refreshed"
            case _: sigil.conversation.UpsertMemoryResult.Versioned  => "versioned"
          }
          obj("outcome" -> str(outcome), "memoryId" -> str(r.memory._id.value))
        }
      case None =>
        ctx.sigil.persistMemory(mem).map(saved => obj("outcome" -> str("stored"), "memoryId" -> str(saved._id.value)))
    }
    Stream.force(saved.map { payload =>
      Stream.emit[Event](Message(
        participantId  = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId        = ctx.conversation.currentTopicId,
        content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
        state          = EventState.Complete,
        role           = MessageRole.Tool
      ))
    })
  }
}
