package sigil.tool.context

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.ContextMemory
import sigil.event.{Event, Message, MessageRole}
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Returns every [[MemorySource.Critical]] memory the caller's chain
 * can access — keyed by `key` + `summary` + per-record token cost
 * (using the active provider's tokenizer when available, char/4
 * heuristic otherwise). Output is JSON the agent reads on its next
 * turn and typically renders to the user via `respond_options` or a
 * Markdown table.
 *
 * Pairs with `unpin_memory(key)`: agent calls list → user picks → agent
 * calls unpin per selection. The conversation never leaves the user's
 * field of view; pinned items are visible and manageable.
 */
case object ListPinnedMemoriesTool extends TypedTool[ListPinnedMemoriesInput](
  name = ToolName("list_pinned_memories"),
  description =
    """List every Critical memory pinned to your context — the directives, persona invariants,
      |and persistent facts the framework renders every turn. Returns each memory's `key`,
      |`summary`, and token cost. Use this when the user asks "what's in your context?" /
      |"what memories are pinned?" / "why is my context full?".
      |
      |Pair with `unpin_memory(key)` to remove a directive that's no longer applicable.
      |Pair with `lookup(capabilityType="Memory", name=key)` to fetch the full fact text
      |when the summary alone isn't enough to decide.
      |
      |- `spaces` — optional filter; empty = every space your chain can access.""".stripMargin,
  keywords = Set("list", "pinned", "memories", "critical", "directives", "context", "review")
) {
  override def resultTtl: Option[Int] = Some(0)

  override protected def executeTyped(input: ListPinnedMemoriesInput, context: TurnContext): Stream[Event] =
    Stream.force(collect(input, context).map { body =>
      Stream.emits(List[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(body)),
        role = MessageRole.Tool
      )))
    })

  private def collect(input: ListPinnedMemoriesInput, context: TurnContext): Task[String] =
    context.sigil.accessibleSpaces(context.chain).flatMap { accessible =>
      val effective = if (input.spaces.nonEmpty) input.spaces.intersect(accessible) else accessible
      if (effective.isEmpty) Task.pure("""{"pinned":[],"note":"No accessible memory spaces for this chain."}""")
      else {
        val tokenizer = HeuristicTokenizer
        context.sigil.findCriticalMemories(effective).map { memories =>
          val items = memories.map { m =>
            val rendered = if (m.summary.trim.nonEmpty) m.summary else m.fact
            obj(
              "key" -> str(if (m.key.nonEmpty) m.key else m._id.value),
              "label" -> str(m.label),
              "summary" -> str(if (m.summary.nonEmpty) m.summary else m.fact.take(140)),
              "tokens" -> num(tokenizer.count(rendered)),
              "spaceId" -> str(m.spaceId.value)
            )
          }
          val payload = obj("pinned" -> arr(items*))
          JsonFormatter.Default(payload)
        }
      }
    }
}
