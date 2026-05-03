package sigil.tool.context

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.{SpaceId, TurnContext}
import sigil.conversation.ContextMemory
import sigil.event.{Event, Message, MessageRole}
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Re-scope an existing memory to a different accessible
 * [[sigil.SpaceId]]. Caller must be able to access both the source
 * and the target spaces — accessing only one fails with a diagnostic
 * message.
 *
 * Resolution order when looking up the target:
 *   1. Within the caller's accessible spaces, find memories matching
 *      `key`. If exactly one match, use it.
 *   2. If multiple matches and `fromSpace` is supplied, filter to
 *      that space.
 *   3. If no key match, try `_id` lookup as a fallback.
 *
 * The record's `_id` and `key` stay the same; only `spaceId` and
 * `modified` change. Versioning is preserved; pinned status is
 * preserved.
 */
case object MoveMemoryTool extends TypedTool[MoveMemoryInput](
  name = ToolName("move_memory"),
  description =
    """Re-scope a memory to a different accessible space — useful when a memory was classified
      |into the wrong space ("oh, this isn't a project rule, it's a personal preference") or
      |when scope changes ("this used to be project-A only; it now applies to me across projects").
      |
      |- `key`       — the memory's stable key (preferred) or `_id` value if no key.
      |- `newSpace`  — the target space (must be in your accessible spaces).
      |- `fromSpace` — optional disambiguator when the same key exists in multiple spaces.
      |
      |The record's id, key, and pinned status are preserved.""".stripMargin,
  keywords = Set("move", "rescope", "memory", "space", "transfer")
) {
  override def resultTtl: Option[Int] = Some(0)
  override val requiresAccessibleSpaces: Boolean = true

  override protected def executeTyped(input: MoveMemoryInput, context: TurnContext): Stream[Event] =
    Stream.force(move(input, context).map { messageText =>
      Stream.emits(List[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(messageText)),
        role = MessageRole.Tool
      )))
    })

  private def move(input: MoveMemoryInput, context: TurnContext): Task[String] =
    context.sigil.accessibleSpaces(context.chain).flatMap { accessible =>
      if (!accessible.contains(input.newSpace))
        Task.pure(s"[move_memory] target space '${input.newSpace.value}' is not in this caller's accessible spaces; cannot move.")
      else {
        val sourceSpaces = input.fromSpace.map(s => Set(s).intersect(accessible)).getOrElse(accessible)
        if (sourceSpaces.isEmpty)
          Task.pure(s"[move_memory] no accessible source spaces; cannot find '${input.key}'.")
        else findTarget(input.key, sourceSpaces, context).flatMap {
          case Some(memory) if memory.spaceId == input.newSpace =>
            Task.pure(s"[move_memory] memory '${displayKey(memory)}' is already in space '${input.newSpace.value}'; nothing to do.")
          case Some(memory) =>
            val moved = memory.copy(spaceId = input.newSpace, modified = lightdb.time.Timestamp())
            context.sigil.withDB(_.memories.transaction(_.upsert(moved))).map { _ =>
              s"[move_memory] moved memory '${displayKey(memory)}' from space '${memory.spaceId.value}' to '${input.newSpace.value}'."
            }
          case None =>
            Task.pure(s"[move_memory] no memory found matching key '${input.key}' in accessible spaces.")
        }
      }
    }

  private def findTarget(key: String,
                         spaces: Set[SpaceId],
                         context: TurnContext): Task[Option[ContextMemory]] =
    context.sigil.findMemories(spaces).flatMap { memories =>
      memories.find(m => m.key == key) match {
        case some @ Some(_) => Task.pure(some)
        case None =>
          context.sigil.withDB(_.memories.transaction(_.get(Id[ContextMemory](key)))).map {
            case some @ Some(m) if spaces.contains(m.spaceId) => some
            case _                                            => None
          }
      }
    }

  private def displayKey(m: ContextMemory): String =
    if (m.key.nonEmpty) m.key else if (m.label.nonEmpty) m.label else m._id.value
}
