package sigil.tool.context

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ContextMemory, MemorySource}
import sigil.event.{Event, Message, MessageRole}
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Demote a Critical memory to a sheddable source ([[MemorySource.Compression]]).
 * The record stays on disk; only its rendering policy changes — it's no
 * longer in the inviolable "render every turn" set, but
 * `recall_memory` / `lookup` can still surface it on demand.
 *
 * Resolution order when looking up the target:
 *   1. Within the caller's accessible spaces, find Critical memories
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
    """Demote a Critical memory so it stops rendering every turn. The record stays on disk —
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
          case Some(memory) if memory.source == MemorySource.Critical =>
            val demoted = memory.copy(source = MemorySource.Compression)
            context.sigil.withDB(_.memories.transaction(_.upsert(demoted))).map { _ =>
              s"[unpin_memory] demoted memory '${displayKey(memory)}' from Critical → Compression. The record remains accessible via lookup or recall_memory."
            }
          case Some(memory) =>
            Task.pure(s"[unpin_memory] memory '${displayKey(memory)}' is already source=${memory.source}; nothing to do.")
          case None =>
            Task.pure(s"[unpin_memory] no Critical memory found matching key '${input.key}' in accessible spaces.")
        }
    }

  private def findTarget(key: String,
                         spaces: Set[sigil.SpaceId],
                         context: TurnContext): Task[Option[ContextMemory]] =
    context.sigil.findCriticalMemories(spaces).flatMap { criticals =>
      criticals.find(m => m.key == key) match {
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
