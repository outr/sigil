package sigil.tool.context

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.conversation.ContextMemory
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}

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
 *      `list_memories(pinned=true)`).
 */
case object UnpinMemoryTool extends Tool {
  type Input = UnpinMemoryInput
  type Output = TextToolOutput
  val inputRW: RW[UnpinMemoryInput] = summon[RW[UnpinMemoryInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("unpin_memory")
  val description: String =
    """Unpin a memory so it stops rendering every turn. The record stays on disk —
      |the agent / user can re-pin later. Use this when the user reviews `list_memories(pinned=true)`
      |and decides a directive is no longer applicable.
      |
      |- `key`   — the memory's stable key (preferred) or `_id` value if no key.
      |- `space` — optional disambiguator when the same key is pinned in multiple spaces.""".stripMargin
  override val keywords: Set[String] = Set("unpin", "remove", "demote", "memory", "directive", "trim")

  override def resultTtl: Option[Int] = Some(0)
  override val requiresAccessibleSpaces: Boolean = true

  override def executeResult(input: UnpinMemoryInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    unpin(input, context).map(text => ToolResult.Success(TextToolOutput(text)))

  private def unpin(input: UnpinMemoryInput, context: ToolContext): Task[String] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { accessible =>
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
                         context: ToolContext): Task[Option[ContextMemory]] =
    context.sigil.findCriticalMemories(spaces).flatMap { pinned =>
      pinned.find(m => m.key.contains(key)) match {
        case some @ Some(_) => Task.pure(some)
        case None =>
          // Fallback: maybe the agent passed an _id (UUID-style) from list_memories(pinned=true)
          context.sigil.withDB(_.memories.transaction(_.get(Id[ContextMemory](key)))).map {
            case some @ Some(m) if spaces.contains(m.spaceId) => some
            case _ => None
          }
      }
    }

  private def displayKey(m: ContextMemory): String =
    m.key.getOrElse(m.label)
}
