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
 * Reply surface is split by intent:
 *   - `respond` — for telling the user something. `content` is markdown.
 *     Inside markdown the parser recognises two extensions:
 *     `> [!Field icon="…"]\n> Label: Value` (typed Field block) and
 *     `## Heading` (opens a typed Card section). `disposition`
 *     (`success` / `failure`) stamps the resulting Message — the
 *     orchestrator's refusal-challenge intercept, downstream "show
 *     me failed turns" queries, and UI chrome all key off it
 *     directly.
 *   - `respond_options` — for asking the user to pick from a list.
 *     Typed schema (prompt, options, allowMultiple, exclusive). The
 *     reply correlates with the next user message.
 *
 * Plus essentials: `find_capability`, `cancel`, `record_consent`.
 *
 * **NOT in `all` by default** (each is shipped in core but apps opt
 * in by adding to their own `staticTools` / `toolNames`):
 *
 *   - `change_mode` — only useful when an app registers more than
 *     one [[sigil.provider.Mode]]. Single-mode apps would surface it
 *     as a no-op tool the model could waste tokens trying to call.
 *
 *   - `respond_failure` / `respond_field` — typed-emission siblings of
 *     the markdown extensions on `respond`. `respond` with
 *     `disposition = "failure"` (or a `> [!Field …]` callout in
 *     content) covers the common case; these standalone tools stay
 *     in core for apps that prefer the named-tool dispatch path.
 *
 *   - `respond_card` / `respond_cards` — composite Card-block reply
 *     surfaces with explicit `kind` and nested sections. `## H2`
 *     headings inside `respond` content auto-wrap into Cards for the
 *     common case; these are for typed multi-card dashboards.
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

  /**
   * The tool instances — pass to `ProviderRequest.tools`.
   */
  val all: Vector[Tool] =
    Vector(
      RespondTool,
      RespondOptionsTool,
      FindCapabilityTool,
      CancelTool,
      RecordConsentTool
    )

  /**
   * The ToolInput RWs for polymorphic registration. Sigil registers these
   * automatically during `instance.sync()`; apps don't need to touch this.
   *
   * Includes `ChangeModeInput` so apps that opt into [[ChangeModeTool]]
   * via their own `staticTools` round-trip without further wiring —
   * the input RW is cheap to register and harmless when the tool
   * itself isn't in the roster.
   */
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

  /**
   * Names of the atomic content tools — those whose output IS the
   * agent's user-facing content rather than a tool result feeding
   * back to the model. Their `executeTyped` emits a `Standard`-role
   * `Message` (not a `Tool`-role `ToolResults`), so no
   * `function_call_output` follows the model's invoking
   * `function_call` in wire history. The framework's frame renderer
   * pairs each such call with a synthetic empty output to satisfy
   * providers (notably OpenAI Responses) that strictly require every
   * `function_call` to have a matching `function_call_output`
   * (sigil bug #19).
   */
  val atomicContentToolNames: Set[sigil.tool.ToolName] = Set(
    RespondTool.schema.name,
    RespondOptionsTool.schema.name,
    RespondFieldTool.schema.name,
    RespondFailureTool.schema.name,
    RespondCardTool.schema.name,
    RespondCardsTool.schema.name,
    NoResponseTool.schema.name
  )
}
