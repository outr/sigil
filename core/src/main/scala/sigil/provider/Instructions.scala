package sigil.provider

import fabric.rw.*

/**
 * Structured instructions composed into the system prompt sent to the LLM.
 *
 * Four fields capture concerns the LLM processes differently:
 *   - `safety`      : Safety posture — what the agent should and shouldn't do without confirmation.
 *   - `behavior`    : Response style — how the agent should sound (tone, terseness, opinions).
 *   - `personality` : Identity and voice — who the agent is.
 *   - `guidelines`  : Task- or application-specific customization layered on top.
 *
 * All four render into a single system-prompt string via [[render]]. The
 * order is deliberate: personality first (establishes context), then the
 * operational `core` (safety + behavior — operational discipline), then
 * guidelines (specifics that may reference earlier context).
 *
 * The `safety` and `behavior` fields are independent so callers can swap
 * just one slot. The framework default is the conservative
 * [[Instructions.ConfirmingSafety]] posture (confirm before
 * external/destructive actions). Benchmarks (AgentDojo, etc.) and
 * deployments where the agent has been granted full authority pick
 * [[Instructions.AutonomousSafety]] via the [[Instructions.autonomous]]
 * factory or `Instructions(safety = AutonomousSafety, ...)`.
 */
case class Instructions private (safety: String = Instructions.ConfirmingSafety,
                                 behavior: String = Instructions.DefaultBehavior,
                                 personality: String = Instructions.DefaultPersonality,
                                 guidelines: List[String] = Nil) derives RW {
  assert(personality.nonEmpty, "Personality must not be empty!")

  def withPersonality(personality: String): Instructions = copy(personality = personality)

  def withSafety(safety: String): Instructions = copy(safety = safety)

  def withBehavior(behavior: String): Instructions = copy(behavior = behavior)

  def withGuidelines(guidelines: String*): Instructions = copy(guidelines = this.guidelines ::: guidelines.toList)

  /**
   * Operational core — concatenation of safety + behavior. Empty slots
   * drop out so callers can disable one by passing `""`.
   */
  lazy val core: String =
    List(safety, behavior).filter(_.nonEmpty).mkString("\n\n")

  /**
   * Render into a single system-prompt string.
   */
  lazy val render: String =
    (List(personality, core) ::: guidelines).filter(_.nonEmpty).mkString("\n\n")
}

object Instructions {
  val DefaultPersonality: String = "You are a helpful assistant."

  /**
   * Default safety posture — confirm before external-facing or destructive
   * actions. Right for production deployments where the agent's actions
   * have user-visible consequences.
   */
  val ConfirmingSafety: String =
    """SAFETY
      |- Read, search, inspect, and organize freely.
      |- External-facing actions (sending messages, posting publicly, publishing content) require user confirmation first.
      |- Destructive operations (delete, overwrite, drop data) always require explicit confirmation.""".stripMargin

  /**
   * Autonomous safety posture — act on user instructions directly without
   * mid-task confirmation. Right for benchmarks (AgentDojo, etc.) and
   * deployments where the agent has been pre-authorized to carry tasks
   * to completion. The user has already authorized the agent at the task
   * level; asking for permission to perform actions they explicitly
   * requested produces the wrong UX and tanks utility metrics.
   */
  val AutonomousSafety: String =
    """SAFETY
      |- The user has authorized you to act on their behalf. Carry their requested task to completion.
      |- Read, search, inspect, organize, and execute actions directly — do not ask for permission to perform actions the user has explicitly requested.
      |- If a request is genuinely ambiguous, ask for clarification; otherwise act.""".stripMargin

  /**
   * Default response behavior — direct, concise, opinionated.
   */
  val DefaultBehavior: String =
    """BEHAVIOR
      |- Be direct and specific. Skip filler like "Great question!" — just help.
      |- Have opinions; push back when something seems wrong.
      |- Keep responses concise unless the user asks for detail.
      |- Ask for clarification only when a request is genuinely ambiguous, not to confirm obvious intent.""".stripMargin

  // -- back-compat aliases --
  // Older code referenced `SafetyGuidance` / `BehaviorGuidance` / `DefaultCore` directly.
  // These names continue to resolve to the conservative defaults.

  val SafetyGuidance: String = ConfirmingSafety
  val BehaviorGuidance: String = DefaultBehavior
  val DefaultCore: String = List(ConfirmingSafety, DefaultBehavior).mkString("\n\n")

  /**
   * Build an [[Instructions]] with the framework defaults pre-applied.
   * Callers can override any slot with named arguments:
   *
   * {{{
   *   Instructions(safety = Instructions.AutonomousSafety)
   *   Instructions(personality = "You are a banking assistant.")
   *   Instructions(behavior = "")  // disable the behavior block
   * }}}
   */
  def apply(safety: String = ConfirmingSafety,
            behavior: String = DefaultBehavior,
            personality: String = DefaultPersonality,
            guidelines: List[String] = Nil): Instructions =
    new Instructions(safety = safety, behavior = behavior, personality = personality, guidelines = guidelines)

  /**
   * Convenience factory for autonomous-action posture — the agent acts
   * directly on user instructions without asking for confirmation. Use
   * this for benchmarks (AgentDojo, etc.) and "agent has been granted
   * full authority" deployments.
   *
   * Equivalent to `Instructions(safety = Instructions.AutonomousSafety, ...)`.
   */
  def autonomous(personality: String = DefaultPersonality,
                 guidelines: List[String] = Nil): Instructions =
    apply(safety = AutonomousSafety, personality = personality, guidelines = guidelines)
}
