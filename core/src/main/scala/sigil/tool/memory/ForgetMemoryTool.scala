package sigil.tool.memory

import fabric.rw.*
import rapid.Task
import sigil.SpaceId
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

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
case object ForgetMemoryTool extends Tool {
  type Input  = ForgetMemoryInput
  type Output = TextToolOutput
  val inputRW: RW[ForgetMemoryInput] = summon[RW[ForgetMemoryInput]]
  val outputRW: RW[TextToolOutput]   = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("forget_memory")
  val description: String =
    """Mark a memory as forgotten. Pass `memoryId` to soft-delete a single record (kept on disk
      |for lineage but hidden from recall). Pass `key` to hard-delete every version of a keyed
      |memory in the caller's default space. Use sparingly — most "I changed my mind" updates
      |are better expressed by saving a new memory under the same key (versioned upsert).""".stripMargin
  override val examples: List[ToolExample] = List(
    ToolExample("Reject a single auto-extracted memory",
      ForgetMemoryInput(memoryId = Some(lightdb.id.Id("mem-12345")))),
    ToolExample("Hard-delete every version of a keyed memory",
      ForgetMemoryInput(key = Some("user.units")))
  )
  override val keywords: Set[String] = Set("memory", "forget", "delete", "remove")

  override def executeResult(input: ForgetMemoryInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    (input.memoryId, input.key) match {
      case (Some(_), Some(_)) =>
        Task.pure(ToolResult.failure(
          "[forget_memory] supply either memoryId OR key, not both.",
          hint = Some("Call again with exactly one of memoryId or key.")
        ))

      case (Some(id), None) =>
        context.sigil.rejectMemory(id).map {
          case None =>
            ToolResult.failure(
              s"[forget_memory] no memory with id ${id.value}.",
              hint = Some("Confirm the id via list_memories, or pass `key` to hard-delete by stable key.")
            )
          case Some(_) =>
            ToolResult.Success(TextToolOutput(s"[forget_memory] rejected memory ${id.value}."))
        }

      case (None, Some(key)) =>
        resolveSpace(context).flatMap {
          case None =>
            Task.pure(ToolResult.failure(
              s"[forget_memory] no memory space available for key $key.",
              hint = Some("This conversation has no default memory space; nothing could be forgotten.")
            ))
          case Some(space) =>
            context.sigil.forgetMemory(key, space).map { count =>
              ToolResult.Success(TextToolOutput(s"[forget_memory] removed $count record(s) for key $key."))
            }
        }

      case (None, None) =>
        Task.pure(ToolResult.failure(
          "[forget_memory] supply memoryId or key.",
          hint = Some("Pass `memoryId` to soft-delete one record, or `key` to hard-delete every version.")
        ))
    }

  private def resolveSpace(context: ToolContext): Task[Option[SpaceId]] =
    context.sigil.defaultMemorySpace(context.conversation.id)
}
