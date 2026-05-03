package sigil.tool.context

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.ContextMemory
import sigil.event.{Event, Message, MessageRole}
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Unpin a memory so it stops rendering every turn. The record stays
 * on disk; only its rendering policy changes — `recall_memory` /
 * `lookup` can still surface it on demand and topical retrieval will
 * pick it up when keywords match.
 *
 * Resolution order when looking up the target:
 *   1. Within the caller's accessible spaces, find pinned memories
 *      matching `key`. If exactly one match, use it.
 *   2. If multiple matches and `space` is supplied, filter to that
 *      space.
 *   3. If no key match, try `_id` lookup as a fallback (for cases
 *      where the agent received a UUID-style id from
 *      `list_pinned_memories`).
 */
case object UnpinMemoryTool extends TypedTool[UnpinMemoryInput](
  name = ToolName("unpin_memory"),
  description =
    """Unpin a memory so it stops rendering every turn. The record stays on disk —
      |the agent / user can re-pin later. Use this when the user reviews `list_pinned_memories`
      |and decides a directive is no longer applicable.
      |
      |- `key`   — the memory's stable key (preferred) or `_id` value if no key.
      |- `space` — optional disambiguator when the same key is pinned in multiple spaces.""".stripMargin,
  keywords = Set("unpin", "remove", "demote", "memory", "directive", "trim")
) {
  override def resultTtl: Option[Int] = Some(0)

  override protected def executeTyped(input: UnpinMemoryInput, context: TurnContext): Stream[Event] =
    Stream.force(unpin(input, context).map { messageText =>
      Stream.emits(List[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(messageText)),
        role = MessageRole.Tool
      )))
    })

  private def unpin(input: UnpinMemoryInput, context: TurnContext): Task[String] =
    context.sigil.accessibleSpaces(context.chain).flatMap { accessible =>
      val effective = input.space.map(s => Set(s).intersect(accessible)).getOrElse(accessible)
      if (effective.isEmpty)
        Task.pure(s"[unpin_memory] no accessible memory spaces; cannot unpin '${input.key}'.")
      else
        findTarget(input.key, effective, context).flatMap {
          case Some(memory) if memory.pinned =>
            val unpinned = memory.copy(pinned = false)
            context.sigil.withDB(_.memories.transaction(_.upsert(unpinned))).map { _ =>
              s"[unpin_memory] unpinned memory '${displayKey(memory)}'. The record remains accessible via topical retrieval, lookup, and recall_memory."
            }
          case Some(memory) =>
            Task.pure(s"[unpin_memory] memory '${displayKey(memory)}' is not pinned; nothing to do.")
          case None =>
            Task.pure(s"[unpin_memory] no pinned memory found matching key '${input.key}' in accessible spaces.")
        }
    }

  private def findTarget(key: String,
                         spaces: Set[sigil.SpaceId],
                         context: TurnContext): Task[Option[ContextMemory]] =
    context.sigil.findCriticalMemories(spaces).flatMap { pinned =>
      pinned.find(m => m.key == key) match {
        case some @ Some(_) => Task.pure(some)
        case None =>
          // Fallback: maybe the agent passed an _id (UUID-style) from list_pinned_memories
          context.sigil.withDB(_.memories.transaction(_.get(Id[ContextMemory](key)))).map {
            case some @ Some(m) if spaces.contains(m.spaceId) => some
            case _                                            => None
          }
      }
    }

  private def displayKey(m: ContextMemory): String =
    if (m.key.nonEmpty) m.key else if (m.label.nonEmpty) m.label else m._id.value
}
