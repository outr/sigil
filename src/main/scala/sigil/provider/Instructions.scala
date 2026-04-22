package sigil.provider

import fabric.rw.*

/**
 * Structured instructions composed into the system prompt sent to the LLM.
 *
 * Three fields capture concerns the LLM processes differently:
 *   - `core`        : Operational contract — how the agent should function (tool use, safety, mode awareness).
 *   - `personality` : Identity and voice — who the agent is and how it sounds.
 *   - `guidelines`  : Task- or application-specific customization layered on top.
 *
 * All three render into a single system-prompt string via [[render]]. The order
 * is deliberate: personality first (establishes context), core next (operational
 * discipline), guidelines last (specifics that may reference earlier context).
 */
case class Instructions private (core: String, personality: String, guidelines: List[String]) derives RW {
  assert(core.nonEmpty, "Core must not be empty!")
  assert(personality.nonEmpty, "Personality must not be empty!")

  def withPersonality(personality: String): Instructions = copy(personality = personality)

  def withGuidelines(guidelines: String*): Instructions = copy(guidelines = this.guidelines ::: guidelines.toList)

  /**
   * Render into a single system-prompt string.
   */
  lazy val render: String = (List(personality, core) ::: guidelines).mkString("\n\n")
}

object Instructions {
  val DefaultPersonality: String = "You are a helpful assistant."

  /**
   * Safety posture around destructive and external-facing actions.
   */
  val SafetyGuidance: String =
    """SAFETY
      |- Read, search, inspect, and organize freely.
      |- External-facing actions (sending messages, posting publicly, publishing content) require user confirmation first.
      |- Destructive operations (delete, overwrite, drop data) always require explicit confirmation.""".stripMargin

  /**
   * General response behavior — direct, concise, opinionated.
   */
  val BehaviorGuidance: String =
    """BEHAVIOR
      |- Be direct and specific. Skip filler like "Great question!" — just help.
      |- Have opinions; push back when something seems wrong.
      |- Keep responses concise unless the user asks for detail.
      |- Ask for clarification only when a request is genuinely ambiguous, not to confirm obvious intent.""".stripMargin

  /**
   * The sigil-default operational `core`. Holds only cross-cutting posture
   * (safety, behavior). Tool-specific guidance — when and how to call a
   * particular tool — lives on that tool's own `description`, where it's
   * co-located with the tool's schema and travels with the tool whether
   * an app keeps, removes, or replaces it.
   */
  val DefaultCore: String =
    List(SafetyGuidance, BehaviorGuidance).mkString("\n\n")

  /**
   * Build an Instructions with the sigil-default operational `core` pre-applied.
   * The caller supplies personality and any task-specific guidelines.
   */
  def apply(core: String = DefaultCore, personality: String = DefaultPersonality, guidelines: List[String] = Nil): Instructions =
    new Instructions(core = core, personality = personality, guidelines = guidelines)
}
