package sigil.tool.util

import fabric.io.JsonFormatter
import fabric.{Arr, Json, num, obj, str}
import rapid.Stream
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, SemanticSearchInput}
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Surface [[sigil.Sigil.searchMemories]] as an LLM-callable tool.
 * The agent supplies a query and gets back a ranked list of memory
 * facts. The space scope comes from
 * [[sigil.Sigil.accessibleSpaces]] applied to the chain — apps
 * that want stricter scoping override that hook. When no vector
 * index is wired (`vectorIndex = NoOpVectorIndex`), the framework
 * falls back to a space-scoped listing.
 */
case object SemanticSearchTool extends TypedTool[SemanticSearchInput](
  name = ToolName("semantic_search"),
  description =
    """Search persisted memories by semantic similarity. Returns the top-k matching memories ranked by
      |embedding similarity. Useful when you need to recall a fact you saved earlier or find related
      |context that may not match a literal keyword.""".stripMargin,
  examples = List(
    ToolExample("Recall preferences", SemanticSearchInput(query = "user's preferred coding style")),
    ToolExample("Top 3 matches only", SemanticSearchInput(query = "deadline next week", topK = Some(3)))
  ),
  keywords = Set("semantic", "search", "memory", "recall", "vector", "similarity", "rag")
) {
  override protected def executeTyped(input: SemanticSearchInput, ctx: TurnContext): Stream[Event] = Stream.force(
    ctx.sigil.accessibleSpaces(ctx.chain).flatMap { spaces =>
      ctx.sigil.searchMemories(input.query, spaces, input.topK.getOrElse(5)).map { memories =>
        val items: Vector[Json] = memories.toVector.map { m =>
          obj(
            "memoryId" -> str(m._id.value),
            "fact"     -> str(m.fact),
            "label"    -> str(m.label),
            "summary"  -> str(m.summary)
          )
        }
        val payload = obj("memories" -> Arr(items), "count" -> num(memories.size))
        Stream.emit[Event](Message(
          participantId  = ctx.caller,
          conversationId = ctx.conversation.id,
          topicId        = ctx.conversation.currentTopicId,
          content        = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
          state          = EventState.Complete,
          role           = MessageRole.Tool
        ))
      }
    }
  )
}
