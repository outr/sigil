package sigil.tool.context

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.SpaceId
import sigil.tool.ToolContext
import sigil.conversation.ContextMemory
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolGates, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

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
case object MoveMemoryTool extends Tool {
  type Input  = MoveMemoryInput
  type Output = TextToolOutput
  val io: ToolIO[MoveMemoryInput, TextToolOutput] = ToolIO.derived[MoveMemoryInput, TextToolOutput]

  override val name: ToolName = ToolName("move_memory")
  override val description: String =
    """Re-scope a memory to a different accessible space — useful when a memory was classified
      |into the wrong space ("oh, this isn't a project rule, it's a personal preference") or
      |when scope changes ("this used to be project-A only; it now applies to me across projects").
      |
      |- `key`       — the memory's stable key (preferred) or `_id` value if no key.
      |- `newSpace`  — the target space (must be in your accessible spaces).
      |- `fromSpace` — optional disambiguator when the same key exists in multiple spaces.
      |
      |The record's id, key, and pinned status are preserved.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.Mutating(MutationTargeting.none),
      gates = ToolGates(requiresAccessibleSpaces = true)
    ),
    discovery = DiscoverySpec(keywords = Set("move", "rescope", "memory", "space", "transfer"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: MoveMemoryInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { accessible =>
      if (!accessible.contains(input.newSpace))
        Task.pure(ToolResult.failure(
          s"[move_memory] target space '${input.newSpace.value}' is not in this caller's accessible spaces; cannot move.",
          hint = Some("Choose a newSpace the caller can access; check accessible spaces before moving.")
        ))
      else {
        val sourceSpaces = input.fromSpace.map(s => Set(s).intersect(accessible)).getOrElse(accessible)
        if (sourceSpaces.isEmpty)
          Task.pure(ToolResult.failure(
            s"[move_memory] no accessible source spaces; cannot find '${input.key}'.",
            hint = Some("The supplied fromSpace is not accessible — omit it or pass an accessible space.")
          ))
        else findTarget(input.key, sourceSpaces, context).flatMap {
          case Some(memory) if memory.spaceId == input.newSpace =>
            Task.pure(ToolResult.Success(TextToolOutput(
              s"[move_memory] memory '${displayKey(memory)}' is already in space '${input.newSpace.value}'; nothing to do.")))
          case Some(memory) =>
            context.sigil.updateMemory(memory.copy(spaceId = input.newSpace)).map { _ =>
              ToolResult.Success(TextToolOutput(
                s"[move_memory] moved memory '${displayKey(memory)}' from space '${memory.spaceId.value}' to '${input.newSpace.value}'."))
            }
          case None =>
            Task.pure(ToolResult.failure(
              s"[move_memory] no memory found matching key '${input.key}' in accessible spaces.",
              hint = Some("Confirm the key with list_memories, or pass fromSpace to disambiguate.")
            ))
        }
      }
    }

  private def findTarget(key: String,
                         spaces: Set[SpaceId],
                         context: ToolContext): Task[Option[ContextMemory]] =
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
