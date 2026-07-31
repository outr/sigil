package sigil.tool.core

import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Tool, ToolName, ToolProfile, ToolSpec}

/**
 * Marker trait for the respond-family tools (`respond`,
 * `respond_options`, `respond_card`, `respond_cards`, `no_response`)
 * — the atomic content tools whose output IS the agent speaking
 * rather than a tool result feeding back to the model. Completion of
 * any one of them closes the turn from the silent-turn detector's
 * perspective, and their ToolInvokes are stamped internal (the
 * emitted Message is the visible artifact, not the invoke).
 *
 * The companion is the ONE membership source every consumer uses —
 * the orchestrator's internal-stamping and terminal-tool checks, the
 * silent-turn detector, provider stream heuristics, and the frame
 * renderer's synthetic-output pairing all consult it.
 */
trait RespondFamilyTool extends Tool

object RespondFamilyTool {

  /** Family terminality framing — `ENDS YOUR TURN.` beats a generic
    * destructive consequence because turn-end is the load-bearing
    * semantic (small models over-call respond when they think it's
    * just a content tool). `respond` itself overrides with the
    * conditional truth (its terminality depends on `endsTurn`). */
  val TerminalConsequence: String = "ENDS YOUR TURN."

  /** Shared spec builder for family members: publishing a Message is
    * an irreversible user-visible effect with no nameable target, so
    * every member is `Destructive(none, consequence)`. */
  def spec(name: ToolName,
           description: String,
           keywords: Set[String],
           consequence: String = TerminalConsequence): ToolSpec =
    ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Destructive(MutationTargeting.none, consequence)),
      discovery = DiscoverySpec(keywords = keywords)
    )

  /** Names of the respond-family tools. */
  lazy val names: Set[ToolName] = Set(
    RespondTool.name,
    RespondOptionsTool.name,
    RespondCardTool.name,
    RespondCardsTool.name,
    NoResponseTool.name
  )

  def contains(name: ToolName): Boolean = names.contains(name)

  /** Raw-string membership for call sites holding a wire-level name. */
  def containsRaw(raw: String): Boolean = names.exists(_.value == raw)

  def isMember(tool: Tool): Boolean = tool.isInstanceOf[RespondFamilyTool] || names.contains(tool.name)
}
