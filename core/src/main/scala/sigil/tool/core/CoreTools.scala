package sigil.tool.core

import fabric.rw.*
import sigil.tool.{Tool, ToolInput}
import sigil.tool.model.{
  ChangeModeInput, NoResponseInput, RespondFailureInput, RespondFieldInput,
  RespondInput, RespondOptionsInput, StopInput
}

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
 * Plus essentials: `no_response`, `find_capability`, `stop`.
 *
 * **NOT in `all` by default** (each is shipped in core but apps opt
 * in by adding to their own `staticTools` / `toolNames`):
 *
 *   - `change_mode` — only useful when an app registers more than
 *     one [[sigil.provider.Mode]]. Single-mode apps would surface it
 *     as a no-op tool the model could waste tokens trying to call.
 *
 * Tools that exist in the framework but are NOT in the default roster
 * (`sleep`, `lookup_information`, etc.) live in [[sigil.tool.util]].
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
      NoResponseTool,
      FindCapabilityTool,
      StopTool
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
      summon[RW[RespondOptionsInput]],
      summon[RW[RespondFieldInput]],
      summon[RW[RespondFailureInput]],
      summon[RW[ChangeModeInput]],
      summon[RW[NoResponseInput]],
      summon[RW[FindCapabilityInput]],
      summon[RW[StopInput]]
    )

  val coreToolNames: List[sigil.tool.ToolName] = all.map(_.schema.name).toList
}
