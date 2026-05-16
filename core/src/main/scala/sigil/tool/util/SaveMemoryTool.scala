package sigil.tool.util

import lightdb.id.Id
import rapid.Task
import sigil.{SpaceId, TurnContext}
import sigil.conversation.{ContextMemory, MemorySource, UpsertMemoryResult}
import sigil.provider.Mode
import sigil.tool.model.{SaveMemoryInput, SaveMemoryOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * Surface [[sigil.Sigil.upsertMemoryByKey]] (or `persistMemory` when
 * no key is supplied) as an LLM-callable tool. Apps that don't want
 * agents writing to memory directly should not register this tool;
 * memory is also writable through `Sigil`'s programmatic API.
 *
 * `space` is fixed at construction — apps that want per-call space
 * scoping construct multiple instances of the tool with different
 * `space` arguments and route via mode / behavior.
 *
 * Emits a typed [[SaveMemoryOutput]] (`outcome`, `memoryId`).
 */
final class SaveMemoryTool(space: SpaceId,
                           source: MemorySource = MemorySource.Explicit)
  extends TypedOutputTool[SaveMemoryInput, SaveMemoryOutput](
    name = ToolName("save_memory"),
    description =
      """Persist a fact for later retrieval. Required: `fact` + `label` (short title) + `summary`
        |(one-line gist). Pass `key` to overwrite a previously-saved memory under that key (versioned
        |upsert); omit `key` to append a new memory. Returns `{outcome, memoryId}`.""".stripMargin,
    examples = List(
      ToolExample(
        "Save a user preference",
        SaveMemoryInput(
          fact = "User prefers metric units across all generated documents.",
          label = "Unit preference",
          summary = "User prefers metric units.",
          key = Some("user.units")
        )
      ),
      ToolExample(
        "Append a new fact",
        SaveMemoryInput(
          fact = "Project deadline is 2026-05-15.",
          label = "Project deadline",
          summary = "Deadline 2026-05-15."
        )
      )
    ),
    keywords = Set("memory", "save", "remember", "store", "persist", "fact")
  ) with sigil.tool.DestructiveInternalTool {

  override val requiresAccessibleSpaces: Boolean = true

  override protected def executeTyped(input: SaveMemoryInput, ctx: TurnContext): Task[SaveMemoryOutput] =
    resolveSpace(input.space, ctx).flatMap { resolvedSpace =>
      val mem = ContextMemory(
        fact         = input.fact,
        label        = input.label,
        summary      = input.summary,
        source       = source,
        spaceId      = resolvedSpace,
        key          = input.key,
        pinned       = input.permanence.contains(sigil.conversation.Permanence.Always),
        keywords     = input.keywords,
        memoryType   = input.memoryType,
        modeAffinity = resolveModeAffinity(input.modeAffinity, ctx)
      )
      input.key match {
        case Some(_) =>
          ctx.sigil.upsertMemoryByKeyFor(mem, ctx.chain, ctx.conversation.id).map { r =>
            val outcome = r match {
              case _: UpsertMemoryResult.Stored    => "Stored"
              case _: UpsertMemoryResult.Refreshed => "Refreshed"
              case _: UpsertMemoryResult.Versioned => "Versioned"
            }
            SaveMemoryOutput(outcome = outcome, memoryId = r.memory._id.value)
          }
        case None =>
          ctx.sigil.persistMemoryFor(mem, ctx.chain, ctx.conversation.id)
            .map(stored => SaveMemoryOutput(outcome = "Stored", memoryId = stored._id.value))
      }
    }

  /** Resolve the agent's space hint to a concrete [[SpaceId]]. When
    * the hint is omitted or doesn't match an accessible space, fall
    * back to the tool's default `space` and let the framework's
    * classifier decide if the caller left it at GlobalSpace. */
  private def resolveSpace(hint: Option[String], ctx: TurnContext): Task[SpaceId] = hint match {
    case None => Task.pure(space)
    case Some(value) =>
      ctx.sigil.accessibleSpaces(ctx.chain, ctx.conversation.id).map { accessible =>
        accessible.find(_.value == value.trim).getOrElse(space)
      }
  }

  /** Resolve mode `name`s to `Id[Mode]`. Unknown names are dropped
    * with a WARN — better to persist the memory as universal (empty
    * set) than to lose it entirely on a typo. Sigil bug #195. */
  private def resolveModeAffinity(names: Set[String], ctx: TurnContext): Set[Id[Mode]] = {
    if (names.isEmpty) Set.empty
    else {
      val known = ctx.sigil.availableModes.map(_.name).toSet
      names.iterator.map(_.trim).filter(_.nonEmpty).flatMap { name =>
        if (known.contains(name)) Some(Id[Mode](name))
        else {
          scribe.warn(s"save_memory: dropping unknown modeAffinity '$name' " +
            s"— not in availableModes [${known.mkString(", ")}]")
          None
        }
      }.toSet
    }
  }

}
