package sigil.tool.memory

import fabric.rw.*
import rapid.Task
import sigil.SpaceId
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolExample, ToolName, ToolProfile, ToolResult, ToolSpec}

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
 * [[sigil.tool.util.SemanticSearchTool]] (search) for the full memory
 * CRUD surface.
 */
case object ForgetMemoryTool extends Tool {
  type Input  = ForgetMemoryInput
  type Output = TextToolOutput
  val inputRW: RW[ForgetMemoryInput] = summon[RW[ForgetMemoryInput]]
  val outputRW: RW[TextToolOutput]   = summon[RW[TextToolOutput]]

  override val name: ToolName = ToolName("forget_memory")
  override val description: String =
    """Mark a memory as forgotten. Pass `memoryId` to soft-delete a single record (kept on disk
      |for lineage but hidden from recall). Pass `key` to hard-delete every version of a keyed
      |memory in the caller's default space. Use sparingly — most "I changed my mind" updates
      |are better expressed by saving a new memory under the same key (versioned upsert).""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("memory", "forget", "delete", "remove"))
  )

  override val examples: List[ToolExample] = List(
    ToolExample("Reject a single auto-extracted memory",
      ForgetMemoryInput(memoryId = Some(lightdb.id.Id("mem-12345")))),
    ToolExample("Hard-delete every version of a keyed memory",
      ForgetMemoryInput(key = Some("user.units")))
  )

  override def executeResult(input: ForgetMemoryInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    (input.memoryId, input.key) match {
      case (Some(_), Some(_)) =>
        Task.pure(ToolResult.failure(
          "[forget_memory] supply either memoryId OR key, not both.",
          hint = Some("Call again with exactly one of memoryId or key.")
        ))

      case (Some(id), None) =>
        // Idempotent: a delete whose target is already gone has ACHIEVED the
        // desired state, so report success rather than a failure. Returning a
        // failure here made agents retry the same forget over and over (a
        // production wire audit caught the same memoryId/key forgotten 13–17×
        // in one conversation); success with an "already forgotten" note ends
        // the loop after the first call.
        context.sigil.rejectMemory(id).map {
          case None =>
            ToolResult.Success(TextToolOutput(
              s"[forget_memory] nothing to do — no active memory with id ${id.value} (already forgotten)."))
          case Some(_) =>
            ToolResult.Success(TextToolOutput(s"[forget_memory] rejected memory ${id.value}."))
        }

      case (None, Some(key)) =>
        resolveSpace(context).flatMap {
          case None =>
            // No default space means there is nothing keyed to forget — also an
            // already-satisfied state, not an error to retry against.
            Task.pure(ToolResult.Success(TextToolOutput(
              s"[forget_memory] nothing to do — no memory space for key $key (nothing stored to forget).")))
          case Some(space) =>
            context.sigil.forgetMemory(key, space).map { count =>
              if (count == 0)
                ToolResult.Success(TextToolOutput(
                  s"[forget_memory] nothing to do — no records for key $key (already forgotten)."))
              else
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
