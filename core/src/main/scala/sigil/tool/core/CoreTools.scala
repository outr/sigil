package sigil.tool.core

import fabric.rw.*
import sigil.tool.{Tool, ToolInput}
import sigil.tool.context.{
  ContextBreakdownInput, ContextBreakdownTool,
  ListMemoriesInput, ListMemoriesTool,
  MoveMemoryInput, MoveMemoryTool,
  PinMemoryInput, PinMemoryTool,
  UnpinMemoryInput, UnpinMemoryTool
}
import sigil.tool.model.{
  CancelInput, ChangeModeInput, NoResponseInput, RecordConsentInput, RespondCardInput, RespondCardsInput,
  RespondFailureInput, RespondFieldInput, RespondInput, RespondOptionsInput
}
import sigil.tool.skill.{ActivateSkillInput, ActivateSkillTool}

/**
 * The canonical set of framework-level tools every sigil agent
 * should have access to — the universal-essential subset, free of
 * any feature that isn't relevant to single-mode apps.
 *
 * The default reply path is `respond` — every user-facing message goes
 * through here. Its `content` field is plain markdown (code fences,
 * headings, images, lists, links, tables) which the framework parses
 * into typed [[sigil.tool.model.ResponseContent]] blocks at
 * turn-settle time. `topicLabel` + `topicSummary` arrive together so
 * topic-shift resolution stays deterministic.
 *
 * Markdown can't natively express interactive choices, labeled
 * key/value cards, or typed failure signals — those live as small
 * atomic content tools (`respond_options`, `respond_field`,
 * `respond_failure`) that emit a single `Message` carrying the
 * structured block.
 *
 * Plus essentials: `find_capability`, `cancel`.
 *
 * **NOT in `all` by default** (each is shipped in core but apps opt
 * in by adding to their own `staticTools` / `toolNames`):
 *
 *   - `change_mode` — only useful when an app registers more than
 *     one [[sigil.provider.Mode]]. Single-mode apps would surface it
 *     as a no-op tool the model could waste tokens trying to call.
 *
 *   - `respond_card` / `respond_cards` — composite Card-block reply
 *     surfaces. Useful when the app's UI renders dashboards / metric
 *     tiles / search-result lists as styled cards. Apps that don't
 *     need card composition keep `respond` (plus `respond_options` /
 *     `respond_field`) and skip these.
 *
 *   - `activate_skill` — only useful when an app's agents register
 *     skill catalogs. Apps without skills surface it as a no-op tool
 *     the model could waste tokens trying to call.
 *
 *   - `list_memories` / `unpin_memory` / `context_breakdown` —
 *     context-introspection surface (Phase 2 of context-limit
 *     enforcement). Apps that surface a "review pinned memories"
 *     UI / context-utilisation gauge opt in; apps that don't use
 *     pinned memories (or that present such reviews via app UI
 *     rather than agent dialog) skip these.
 *
 * Adding any of these to a small core roster shifts the model's
 * tool-selection decisions — concretely, OpenAI's GPT can pick
 * `respond` over `change_mode` if the surface is broad enough that
 * mode-switching reads as the wrong shape, so the default stays
 * minimal and apps opt in to the surface they actually use.
 *
 * Tools that exist in the framework but are NOT in the default roster
 * (`sleep`, `lookup`, etc.) live in [[sigil.tool.util]].
 * Apps opt those in explicitly by including their names in an agent's
 * `toolNames` list.
 */
object CoreTools {

  /** The tool instances — pass to `ProviderRequest.tools`. */
  val all: Vector[Tool] =
    Vector(
      RespondTool,
      RespondOptionsTool,
      RespondFieldTool,
      RespondFailureTool,
      FindCapabilityTool,
      CancelTool,
      RecordConsentTool
    )

  /** The ToolInput RWs for polymorphic registration. Sigil registers these
    * automatically during `instance.sync()`; apps don't need to touch this.
    *
    * Includes `ChangeModeInput` so apps that opt into [[ChangeModeTool]]
    * via their own `staticTools` round-trip without further wiring —
    * the input RW is cheap to register and harmless when the tool
    * itself isn't in the roster. */
  val inputRWs: List[RW[? <: ToolInput]] =
    List(
      summon[RW[RespondInput]],
      summon[RW[RespondCardInput]],
      summon[RW[RespondCardsInput]],
      summon[RW[RespondOptionsInput]],
      summon[RW[RespondFieldInput]],
      summon[RW[RespondFailureInput]],
      summon[RW[ChangeModeInput]],
      summon[RW[NoResponseInput]],
      summon[RW[RecordConsentInput]],
      summon[RW[FindCapabilityInput]],
      summon[RW[CancelInput]],
      summon[RW[ActivateSkillInput]],
      summon[RW[ListMemoriesInput]],
      summon[RW[PinMemoryInput]],
      summon[RW[UnpinMemoryInput]],
      summon[RW[MoveMemoryInput]],
      summon[RW[ContextBreakdownInput]],
      summon[RW[sigil.tool.model.CompleteTaskInput]],
      summon[RW[sigil.tool.output.NextPageInput]],
      summon[RW[sigil.tool.output.QueryToolOutputInput]],
      summon[RW[sigil.tool.core.CancelFrameworkWorkflowInput]],
      summon[RW[RequestEscalationInput]]
    )

  val coreToolNames: List[sigil.tool.ToolName] = all.map(_.schema.name).toList

  /** Names of the atomic content tools — those whose output IS the
    * agent's user-facing content rather than a tool result feeding
    * back to the model. Their `executeTyped` emits a `Standard`-role
    * `Message` (not a `Tool`-role `ToolResults`), so no
    * `function_call_output` follows the model's invoking
    * `function_call` in wire history. The framework's frame renderer
    * pairs each such call with a synthetic empty output to satisfy
    * providers (notably OpenAI Responses) that strictly require every
    * `function_call` to have a matching `function_call_output`
    * (sigil bug #19). */
  val atomicContentToolNames: Set[sigil.tool.ToolName] = Set(
    RespondTool.schema.name,
    RespondOptionsTool.schema.name,
    RespondFieldTool.schema.name,
    RespondFailureTool.schema.name,
    RespondCardTool.schema.name,
    RespondCardsTool.schema.name,
    NoResponseTool.schema.name  // still atomic-shape when apps opt back in
  )
}
