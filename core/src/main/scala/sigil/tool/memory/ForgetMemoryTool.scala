package sigil.tool.memory

import rapid.{Stream, Task}
import sigil.{SpaceId, TurnContext}
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Forget (mark rejected, or hard-delete by key) a previously stored
 * memory. Two modes:
 *
 *   - `memoryId` — soft-delete: transition that single record to
 *                  `MemoryStatus.Rejected`. The record is kept on disk
 *                  for lineage but hidden from `searchMemories` and
 *                  `findMemories`.
 *   - `key`     — hard-delete: every version of the keyed memory in
 *                  the caller's default space (or the supplied
 *                  `spaceId`) is removed via [[sigil.Sigil.forgetMemory]],
 *                  including any vector-index points.
 *
 * Pair with [[sigil.tool.util.SaveMemoryTool]] (write) and
 * [[RecallMemoryTool]] (search) for the full memory CRUD surface.
 */
case object ForgetMemoryTool extends TypedTool[ForgetMemoryInput](
  name = ToolName("forget_memory"),
  description =
    """Mark a memory as forgotten. Pass `memoryId` to soft-delete a single record (kept on disk
      |for lineage but hidden from recall). Pass `key` to hard-delete every version of a keyed
      |memory in the caller's default space. Use sparingly — most "I changed my mind" updates
      |are better expressed by saving a new memory under the same key (versioned upsert).""".stripMargin,
  examples = List(
    ToolExample("Reject a single auto-extracted memory",
      ForgetMemoryInput(memoryId = Some(lightdb.id.Id("mem-12345")))),
    ToolExample("Hard-delete every version of a keyed memory",
      ForgetMemoryInput(key = Some("user.units")))
  ),
  keywords = Set("memory", "forget", "delete", "remove")
) {
  override protected def executeTyped(input: ForgetMemoryInput, context: TurnContext): Stream[Event] = {
    val msgTask: Task[Message] = (input.memoryId, input.key) match {
      case (Some(_), Some(_)) =>
        Task.pure(toMsg(context,
          "[forget_memory] supply either memoryId OR key, not both."))

      case (Some(id), None) =>
        context.sigil.rejectMemory(id).map {
          case None    => toMsg(context, s"[forget_memory] no memory with id ${id.value}.")
          case Some(_) => toMsg(context, s"[forget_memory] rejected memory ${id.value}.")
        }

      case (None, Some(key)) =>
        resolveSpace(context).flatMap {
          case None =>
            Task.pure(toMsg(context, s"[forget_memory] no memory space available for key $key."))
          case Some(space) =>
            context.sigil.forgetMemory(key, space).map { count =>
              toMsg(context, s"[forget_memory] removed $count record(s) for key $key.")
            }
        }

      case (None, None) =>
        Task.pure(toMsg(context, "[forget_memory] supply memoryId or key."))
    }
    Stream.force(msgTask.map(msg => Stream.emits[Event](List(msg))))
  }

  private def resolveSpace(context: TurnContext): Task[Option[SpaceId]] =
    context.sigil.defaultMemorySpace(context.conversation.id)

  private def toMsg(context: TurnContext, body: String): Message =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(body)),
      state = EventState.Complete,
      role = MessageRole.Tool
    )
}
