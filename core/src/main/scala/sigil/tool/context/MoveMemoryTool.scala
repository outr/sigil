package sigil.tool.context

import fabric.rw.*
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
      |- `newSpace`  — the target space's value string (must be one of your accessible spaces).
      |- `fromSpace` — optional space value string to disambiguate when the same key exists in multiple spaces.
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
      def accessibleValues: String = accessible.map(_.value).toList.sorted.take(20).mkString(", ")
      accessible.find(_.value == input.newSpace) match {
        case None =>
          Task.pure(ToolResult.failure(
            s"[move_memory] target space '${input.newSpace}' is not in this caller's accessible spaces; cannot move.",
            hint = Some(s"Pass one of the accessible space values: $accessibleValues.")
          ))
        case Some(targetSpace) =>
          val sourceSpaces = input.fromSpace match {
            case None    => accessible
            case Some(v) => accessible.filter(_.value == v)
          }
          if (sourceSpaces.isEmpty)
            Task.pure(ToolResult.failure(
              s"[move_memory] fromSpace '${input.fromSpace.getOrElse("")}' is not an accessible space; cannot find '${input.key}'.",
              hint = Some(s"Omit fromSpace or pass one of: $accessibleValues.")
            ))
          else findTarget(input.key, sourceSpaces, context).flatMap {
          case MemoryTarget.Found(memory) if memory.spaceId == targetSpace =>
            Task.pure(ToolResult.Success(TextToolOutput(
              s"[move_memory] memory '${displayKey(memory)}' is already in space '${targetSpace.value}'; nothing to do.")))
          case MemoryTarget.Found(memory) =>
            context.sigil.updateMemory(memory.copy(spaceId = targetSpace)).map { _ =>
              ToolResult.Success(TextToolOutput(
                s"[move_memory] moved memory '${displayKey(memory)}' from space '${memory.spaceId.value}' to '${targetSpace.value}'."))
            }
          case MemoryTarget.NotRecallable(memory) =>
            Task.pure(ToolResult.failure(
              s"[move_memory] memory '${displayKey(memory)}' cannot be moved because ${MemoryTarget.reason(memory)}.",
              hint = Some("Move the current version instead — re-scoping an archived record changes nothing any " +
                "retrieval reads.")
            ))
          case MemoryTarget.Missing =>
            Task.pure(ToolResult.failure(
              s"[move_memory] no memory found matching key '${input.key}' in accessible spaces.",
              hint = Some("Confirm the key with list_memories, or pass fromSpace to disambiguate.")
            ))
          }
      }
    }

  private def findTarget(key: String,
                         spaces: Set[SpaceId],
                         context: ToolContext): Task[MemoryTarget] =
    context.sigil.findMemories(spaces).flatMap(MemoryTarget.resolve(key, spaces, _, context))

  private def displayKey(m: ContextMemory): String =
    m.key.getOrElse(m.label)
}
