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
case class Instructions private (core: String,
                                 personality: String,
                                 guidelines: List[String]) derives RW {
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
   * Tool-use discipline — every user-facing output goes through a tool call.
   */
  val ToolUseGuidance: String =
    """TOOL USE
      |- Every user-facing reply goes through a tool call — the `respond` tool for natural-language replies. You cannot produce output by emitting plain text.
      |- When a request requires an action (search, create, modify, lookup), call the appropriate tool rather than describing what you would do.
      |- Do not narrate your tool calls. Describe what you did in natural language after the fact, via `respond`.
      |- If the latest message isn't directed at you, or another participant is better positioned to reply, call `no_response` instead of `respond`. Don't pad with filler like "I have nothing to add" — silent decline is cleaner.""".stripMargin

  /**
   * Mode awareness — the current mode is provided as context; switch when needed.
   */
  val ModeGuidance: String =
    """MODES
      |- Your current mode is stated at the top of this prompt. When the user's task belongs to a different mode, call `change_mode` to switch before proceeding.""".stripMargin

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
   * Conversation-title policy. The `respond` tool's `title` field is
   * required on every call; this guidance governs when to keep the
   * current title versus when to propose a new one.
   */
  val TitleGuidance: String =
    """TITLE
      |- The `title` field on `respond` is REQUIRED on every call.
      |- If the current title (shown at the top of this prompt) still fits the conversation, pass it UNCHANGED.
      |- Propose a new concise 3-6 word title ONLY when:
      |    a) the current title is "New Conversation" (a freshly-created conversation), or
      |    b) the topic has meaningfully shifted and the current title no longer fits.
      |- No quotes, no punctuation in titles.""".stripMargin

  /**
   * The sigil-default operational `core`. Sections cover tool-use discipline,
   * mode awareness, safety posture, response behavior, and title handling.
   */
  val DefaultCore: String =
    List(ToolUseGuidance, ModeGuidance, SafetyGuidance, BehaviorGuidance, TitleGuidance).mkString("\n\n")

  /**
   * Build an Instructions with the sigil-default operational `core` pre-applied.
   * The caller supplies personality and any task-specific guidelines.
   */
  def apply(core: String = DefaultCore,
            personality: String = DefaultPersonality,
            guidelines: List[String] = Nil): Instructions =
    new Instructions(core = core, personality = personality, guidelines = guidelines)
}
