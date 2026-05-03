package sigil.tool.context

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.ContextMemory
import sigil.event.{Event, Message, MessageRole}
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Pin a previously-saved memory so it starts rendering every turn.
 * Symmetric counterpart to [[UnpinMemoryTool]].
 *
 * Resolution order when looking up the target:
 *   1. Within the caller's accessible spaces, find unpinned memories
 *      matching `key`. If exactly one match, use it.
 *   2. If multiple matches and `space` is supplied, filter to that
 *      space.
 *   3. If no key match, try `_id` lookup as a fallback.
 *
 * The promotion is durable but reversible — call `unpin_memory(key)`
 * to flip back. No write-time cap rejection: the framework's
 * [[sigil.signal.PinnedMemoryBudgetWarning]] surfaces budget pressure
 * as a warning, not an error. Apps that want hard rejection override
 * [[sigil.Sigil.validateCoreContextCap]].
 */
case object PinMemoryTool extends TypedTool[PinMemoryInput](
  name = ToolName("pin_memory"),
  description =
    """Pin a previously-saved memory so it renders every turn — useful when an existing fact
      |turns out to be a hard rule the agent should always follow ("from now on, always do X
      |whenever Y").
      |
      |- `key`   — the memory's stable key (preferred) or `_id` value if no key.
      |- `space` — optional disambiguator when the same key exists in multiple accessible spaces.
      |
      |Reversible via `unpin_memory(key)`.""".stripMargin,
  keywords = Set("pin", "promote", "memory", "directive", "always", "permanent")
) {
  override def resultTtl: Option[Int] = Some(0)
  override val requiresAccessibleSpaces: Boolean = true

  override protected def executeTyped(input: PinMemoryInput, context: TurnContext): Stream[Event] =
    Stream.force(pin(input, context).map { messageText =>
      Stream.emits(List[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(messageText)),
        role = MessageRole.Tool
      )))
    })

  private def pin(input: PinMemoryInput, context: TurnContext): Task[String] =
    context.sigil.accessibleSpaces(context.chain).flatMap { accessible =>
      val effective = input.space.map(s => Set(s).intersect(accessible)).getOrElse(accessible)
      if (effective.isEmpty)
        Task.pure(s"[pin_memory] no accessible memory spaces; cannot pin '${input.key}'.")
      else
        findTarget(input.key, effective, context).flatMap {
          case Some(memory) if !memory.pinned =>
            val pinned = memory.copy(pinned = true)
            context.sigil.withDB(_.memories.transaction(_.upsert(pinned))).map { _ =>
              s"[pin_memory] pinned memory '${displayKey(memory)}'. It will now render every turn until unpinned."
            }
          case Some(memory) =>
            Task.pure(s"[pin_memory] memory '${displayKey(memory)}' is already pinned; nothing to do.")
          case None =>
            Task.pure(s"[pin_memory] no memory found matching key '${input.key}' in accessible spaces.")
        }
    }

  /** Look for the target by `key` first, then by `_id` fallback. */
  private def findTarget(key: String,
                         spaces: Set[sigil.SpaceId],
                         context: TurnContext): Task[Option[ContextMemory]] =
    context.sigil.findMemories(spaces).flatMap { memories =>
      memories.find(m => m.key.contains(key)) match {
        case some @ Some(_) => Task.pure(some)
        case None =>
          context.sigil.withDB(_.memories.transaction(_.get(Id[ContextMemory](key)))).map {
            case some @ Some(m) if spaces.contains(m.spaceId) => some
            case _                                            => None
          }
      }
    }

  private def displayKey(m: ContextMemory): String =
    m.key.getOrElse(m.label)
}
