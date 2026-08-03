package sigil.tool.context

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.conversation.ContextMemory
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolGates, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Pin a previously-saved memory so it starts rendering every turn.
 * Symmetric counterpart to [[UnpinMemoryTool]].
 *
 * Resolution order when looking up the target:
 *   1. Within the caller's accessible spaces, find unpinned memories
 *      matching `key`. If exactly one match, use it.
 *   2. If multiple matches and `space` is supplied, filter to that
 *      space.
 *   3. If no key match, try `_id` lookup as a fallback.
 *
 * The promotion is durable but reversible — call `unpin_memory(key)`
 * to flip back. No write-time cap rejection: the framework's
 * [[sigil.signal.PinnedMemoryBudgetWarning]] surfaces budget pressure
 * as a warning, not an error. Apps that want hard rejection override
 * [[sigil.Sigil.validateCoreContextCap]].
 */
case object PinMemoryTool extends Tool {
  type Input  = PinMemoryInput
  type Output = TextToolOutput
  val io: ToolIO[PinMemoryInput, TextToolOutput] = ToolIO.derived[PinMemoryInput, TextToolOutput]

  override val name: ToolName = ToolName("pin_memory")
  override val description: String =
    """Pin a previously-saved memory so it renders every turn — useful when an existing fact
      |turns out to be a hard rule the agent should always follow ("from now on, always do X
      |whenever Y").
      |
      |- `key`   — the memory's stable key (preferred) or `_id` value if no key.
      |- `space` — optional disambiguator when the same key exists in multiple accessible spaces.
      |
      |Reversible via `unpin_memory(key)`.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.Mutating(MutationTargeting.none),
      gates = ToolGates(requiresAccessibleSpaces = true)
    ),
    discovery = DiscoverySpec(keywords = Set("pin", "promote", "memory", "directive", "always", "permanent"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: PinMemoryInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { accessible =>
      val effective = input.space.map(s => Set(s).intersect(accessible)).getOrElse(accessible)
      if (effective.isEmpty)
        Task.pure(ToolResult.failure(
          s"[pin_memory] no accessible memory spaces; cannot pin '${input.key}'.",
          hint = Some("The caller has no accessible memory spaces — authorize a space before pinning.")
        ))
      else
        findTarget(input.key, effective, context).flatMap {
          case MemoryTarget.Found(memory) if !memory.pinned =>
            context.sigil.updateMemory(memory.copy(pinned = true)).map { _ =>
              ToolResult.Success(TextToolOutput(
                s"[pin_memory] pinned memory '${displayKey(memory)}'. It will now render every turn until unpinned."))
            }
          case MemoryTarget.Found(memory) =>
            Task.pure(ToolResult.Success(TextToolOutput(
              s"[pin_memory] memory '${displayKey(memory)}' is already pinned; nothing to do.")))
          case MemoryTarget.NotRecallable(memory) =>
            Task.pure(ToolResult.failure(
              s"[pin_memory] memory '${displayKey(memory)}' cannot be pinned because ${MemoryTarget.reason(memory)}.",
              hint = Some("Pinning it would write a record no retrieval surfaces. Pin the current version instead, " +
                "or save the fact again with save_memory.")
            ))
          case MemoryTarget.Missing =>
            Task.pure(ToolResult.failure(
              s"[pin_memory] no memory found matching key '${input.key}' in accessible spaces.",
              hint = Some("Check the key via list_memories, or save the memory first with save_memory.")
            ))
        }
    }

  /** Look for the target by `key` first, then by `_id` fallback. */
  private def findTarget(key: String,
                         spaces: Set[sigil.SpaceId],
                         context: ToolContext): Task[MemoryTarget] =
    context.sigil.findMemories(spaces).flatMap(MemoryTarget.resolve(key, spaces, _, context))

  private def displayKey(m: ContextMemory): String =
    m.key.getOrElse(m.label)
}
