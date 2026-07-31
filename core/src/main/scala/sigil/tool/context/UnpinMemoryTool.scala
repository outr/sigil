package sigil.tool.context

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.conversation.ContextMemory
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolGates, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Unpin a memory so it stops rendering every turn. The record stays
 * on disk; only its rendering policy changes — `semantic_search` /
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
  type Input  = UnpinMemoryInput
  type Output = TextToolOutput
  val inputRW: RW[UnpinMemoryInput] = summon[RW[UnpinMemoryInput]]
  val outputRW: RW[TextToolOutput]  = summon[RW[TextToolOutput]]

  override val name: ToolName = ToolName("unpin_memory")
  override val description: String =
    """Unpin a memory so it stops rendering every turn. The record stays on disk —
      |the agent / user can re-pin later. Use this when the user reviews `list_memories(pinned=true)`
      |and decides a directive is no longer applicable.
      |
      |- `key`   — the memory's stable key (preferred) or `_id` value if no key.
      |- `space` — optional disambiguator when the same key is pinned in multiple spaces.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.Mutating(MutationTargeting.none),
      gates = ToolGates(requiresAccessibleSpaces = true)
    ),
    discovery = DiscoverySpec(keywords = Set("unpin", "remove", "demote", "memory", "directive", "trim"))
  )

  override def executeResult(input: UnpinMemoryInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { accessible =>
      val effective = input.space.map(s => Set(s).intersect(accessible)).getOrElse(accessible)
      if (effective.isEmpty)
        Task.pure(ToolResult.failure(
          s"[unpin_memory] no accessible memory spaces; cannot unpin '${input.key}'.",
          hint = Some("The caller has no accessible memory spaces — authorize a space before unpinning.")
        ))
      else
        findTarget(input.key, effective, context).flatMap {
          case Some(memory) if memory.pinned =>
            context.sigil.updateMemory(memory.copy(pinned = false)).map { _ =>
              ToolResult.Success(TextToolOutput(
                s"[unpin_memory] unpinned memory '${displayKey(memory)}'. The record remains accessible via topical retrieval, lookup, and semantic_search."))
            }
          case Some(memory) =>
            Task.pure(ToolResult.Success(TextToolOutput(
              s"[unpin_memory] memory '${displayKey(memory)}' is not pinned; nothing to do.")))
          case None =>
            Task.pure(ToolResult.failure(
              s"[unpin_memory] no pinned memory found matching key '${input.key}' in accessible spaces.",
              hint = Some("List currently pinned memories with list_memories(pinned=true) to confirm the key.")
            ))
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
            case _                                            => None
          }
      }
    }

  private def displayKey(m: ContextMemory): String =
    m.key.getOrElse(m.label)
}
