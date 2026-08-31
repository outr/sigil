package sigil.tool.context

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.SpaceId
import sigil.conversation.{ContextMemory, MemoryStatus}
import sigil.tool.ToolContext

/**
 * Outcome of resolving a memory-mutation tool's `key` argument to a
 * record. The context tools (`pin_memory`, `unpin_memory`,
 * `move_memory`) all accept a stable key with an `_id` fallback, and
 * all three need the same three-way answer:
 *
 *   - `Found`         — a live record the tool may mutate;
 *   - `NotRecallable` — the id resolved, in an accessible space, to a
 *     record the recall gate excludes (superseded, rejected, expired).
 *     Distinct from `Missing` because mutating it would write a row
 *     nothing ever reads: the agent's pin appears to succeed and the
 *     directive never renders. The tool reports the state instead;
 *   - `Missing`       — no such key or id in the caller's scope.
 */
enum MemoryTarget {
  case Found(memory: ContextMemory)
  case NotRecallable(memory: ContextMemory)
  case Missing
}

object MemoryTarget {

  /**
   * Resolve `key` against `candidates` (an already-scoped, already-
   * gated listing), falling back to an `_id` lookup for agents that
   * pass a raw id from `list_memories`. The fallback re-checks space
   * membership and the recall gate — the listing legs apply both, the
   * direct `get` applies neither.
   */
  def resolve(key: String,
              spaces: Set[SpaceId],
              candidates: List[ContextMemory],
              context: ToolContext): Task[MemoryTarget] =
    candidates.find(_.key.contains(key)) match {
      case Some(memory) => Task.pure(Found(memory))
      case None =>
        context.sigil.withDB(_.memories.transaction(_.get(Id[ContextMemory](key)))).map {
          case Some(m) if !spaces.contains(m.spaceId) => Missing
          case Some(m) if m.isRecallable(Timestamp()) => Found(m)
          case Some(m) => NotRecallable(m)
          case None => Missing
        }
    }

  /**
   * Human-readable reason a resolved record isn't recallable, for the
   * tool's failure message.
   */
  def reason(memory: ContextMemory): String =
    if (memory.validUntil.isDefined) "it is a superseded version — a newer version of this memory replaced it"
    else if (memory.status != MemoryStatus.Approved) s"its status is ${memory.status}"
    else "it has expired"
}
