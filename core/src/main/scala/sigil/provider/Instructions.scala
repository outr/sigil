package sigil.provider

import fabric.rw.*

/**
 * Structured instructions composed into the system prompt sent to the LLM.
 *
 * Five fields capture concerns the LLM processes differently:
 *   - `safety`      : Safety posture — what the agent should and shouldn't do without confirmation.
 *   - `behavior`    : Response style — how the agent should sound (tone, terseness, opinions).
 *   - `tools`       : Discovery framing — telling the model that most capabilities are not preloaded.
 *   - `personality` : Identity and voice — who the agent is.
 *   - `guidelines`  : Task- or application-specific customization layered on top.
 *
 * All five render into a single system-prompt string via [[render]]. The
 * order is deliberate: personality first (establishes context), then the
 * operational `core` (safety + behavior + tools — operational discipline),
 * then guidelines (specifics that may reference earlier context).
 *
 * Each slot is independent so callers can swap just one. Disable any slot
 * by passing `""`. The framework defaults are tuned so that
 * `Instructions()` produces an agent that respects safety, replies
 * concisely, AND uses `find_capability` for discovery — without any
 * consumer-side overrides.
 */
case class Instructions private (safety: String = Instructions.ConfirmingSafety,
                                 behavior: String = Instructions.DefaultBehavior,
                                 tools: String = Instructions.DefaultToolsGuidance,
                                 personality: String = Instructions.DefaultPersonality,
                                 guidelines: List[String] = Nil,
                                 toolsTrailer: String = Instructions.DefaultToolsTrailer,
                                 /**
                                  * The agent's safety posture (sigil bug
                                  * #160). `Confirming` keeps the
                                  * orchestrator's `requiresUserConsent`
                                  * gate active; `Autonomous` bypasses
                                  * it because the user has pre-
                                  * authorized the agent. Independent of
                                  * the [[safety]] prose — that's the
                                  * model-facing wording; this is the
                                  * structural flag the orchestrator
                                  * keys off. The two should usually
                                  * agree (set both via
                                  * [[Instructions.autonomous]] or
                                  * [[Instructions.apply]]).
                                  */
                                 posture: SafetyPosture = SafetyPosture.Confirming)
  derives RW {
  assert(personality.nonEmpty, "Personality must not be empty!")

  def withPersonality(personality: String): Instructions = copy(personality = personality)

  def withSafety(safety: String): Instructions = copy(safety = safety)

  def withBehavior(behavior: String): Instructions = copy(behavior = behavior)

  def withTools(tools: String): Instructions = copy(tools = tools)

  def withGuidelines(guidelines: String*): Instructions = copy(guidelines = this.guidelines ::: guidelines.toList)

  /**
   * Set or disable the trailing tool-call recap. Pass `""` to suppress.
   */
  def withToolsTrailer(toolsTrailer: String): Instructions = copy(toolsTrailer = toolsTrailer)

  /**
   * Set the structural safety posture. Distinct from [[withSafety]] —
   * that swaps the model-facing safety prose; this flips the
   * orchestrator's consent-gate behaviour.
   */
  def withPosture(posture: SafetyPosture): Instructions = copy(posture = posture)

  /**
   * Operational core — concatenation of safety + behavior + tools.
   * Empty slots drop out so callers can disable one by passing `""`.
   */
  lazy val core: String =
    List(safety, behavior, tools).filter(_.nonEmpty).mkString("\n\n")

  /**
   * Render into a single system-prompt string.
   *
   * Order: `personality → core → guidelines → toolsTrailer`. The
   * `toolsTrailer` lands last so it sits within the model's
   * recency-biased attention even after long conversation histories
   * push the front-of-prompt content out of view.
   */
  lazy val render: String = (List(personality, core) ::: guidelines ::: List(toolsTrailer))
    .filter(_.nonEmpty).mkString("\n\n")

  /**
   * Render variant that omits the [[tools]] discovery block — used
   * when `find_capability` isn't in the agent's effective tool roster
   * (pointing the model at a tool it can't call creates a dead loop).
   * The trailing [[toolsTrailer]] recap still renders.
   */
  lazy val renderWithoutTools: String = {
    val coreNoTools = List(safety, behavior).filter(_.nonEmpty).mkString("\n\n")
    (List(personality, coreNoTools) ::: guidelines ::: List(toolsTrailer))
      .filter(_.nonEmpty).mkString("\n\n")
  }
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

  /**
   * Default tool-discovery framing — establishes discovery-first as the
   * framework's CORE ideology, not a tip.
   *
   * Deliberately TOOL-AGNOSTIC: it teaches the discovery-first BEHAVIOR
   * and names no specific tool. Every tool-specific instruction (how to
   * query the discovery tool, when to switch mode, how a reply tool
   * renders) lives in that tool's own `description`, which the model
   * already receives whenever the tool is in its roster — so the prompt
   * never points the model at a capability that isn't present.
   *
   * Triage framing (1 / 2) is what moves smaller quantised models off
   * the "I'll just answer" default. Load-bearing rules: "even ONE word
   * of action means action" closes the trivial-fake loophole; "when in
   * doubt, choose action" biases toward discovery.
   */
  val DefaultToolsGuidance: String =
    """TOOLS — discovery-first is the framework's CORE ideology. Internalize this.
      |
      |Most of your capabilities are NOT preloaded. The visible roster is intentionally small; the full catalog is large, and almost every action the user asks for has a dedicated capability you reach by DISCOVERING it — not by faking it through a plain-text reply.
      |
      |Triage every user message:
      |
      |1. The user asked you to DO something — wait, fetch, save, look up, send, run, edit, search, write code, anything action-shaped. Even ONE word of action means action.
      |   → Discover the capability that fits the task, then use it. The discovery tool in your roster explains how to search; each capability it surfaces carries its own usage in its own description.
      |   → Self-referential requests ("switch models", "what can you do", anything you're tempted to treat as out-of-scope) are STILL actions. Don't refuse based on assumed limits — the catalog usually has what's needed. A refusal not preceded by a discovery search is a bug.
      |   → **Ambiguity is NOT a reason to skip discovery.** If you're tempted to ask the user to clarify before acting, search first with your best-guess terms — a matching capability often resolves the ambiguity on the spot. Fall through to a clarifying question only after discovery surfaces nothing relevant, and say what you searched for.
      |
      |2. The user is chatting / asking a knowledge question / following up and no action is needed.
      |   → Reply directly.
      |
      |When in doubt between 1 and 2, choose 1. The cost of an unnecessary search is one extra turn; the cost of skipping it is silently degrading the user's task.
      |
      |**Your roster is EPHEMERAL** — the capabilities offered this turn may differ from earlier turns, and a record of what you used before does not guarantee current availability. If a capability you relied on earlier isn't offered now, rediscover it rather than giving up or improvising a worse path.
      |
      |**Every reply MUST be a tool call.** Plain text output is dropped silently — everything you do, including delivering a message to the user, happens through a tool. If the tool you need isn't in the current roster, discover it first.
      |
      |**Tool failures carry structured context.** When a tool result has disposition `Failure` with `errorContext`, read `classification` to pick a response shape:
      |  - `UserInputError` — fix the args and retry, or explain the expected input shape.
      |  - `TransientError` — retry once before giving up.
      |  - `ResourceExhausted` — narrow / page the inputs, not a retry.
      |  - `FrameworkBug` (high `frameworkBugLikelihood`) — surface the class + message to the user; don't keep retrying.
      |  - `ProviderError` — report the upstream issue verbatim.
      |  - `Unknown` — explain what failed; defer to the user.""".stripMargin

  /**
   * Tail recap of the "every reply MUST be a tool call" rule — rendered
   * LAST so it sits within the model's recency-biased attention even
   * after long histories push the front-of-prompt guidance out of view.
   * Smaller / quantised models drift to plain-text output without it.
   *
   * Tool-agnostic by design: it prevents plain-text drift without naming
   * any specific tool. Suppress with `Instructions(toolsTrailer = "")`.
   */
  val DefaultToolsTrailer: String =
    """REMINDER: every reply MUST be a tool call. For ACTIONS (anything the user asked you to DO),
      |discover the capability that fits and use it; deliver any reply only once the action has run,
      |not instead of it. Plain text output is dropped silently — route everything through a tool.""".stripMargin

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
            tools: String = DefaultToolsGuidance,
            personality: String = DefaultPersonality,
            guidelines: List[String] = Nil,
            toolsTrailer: String = DefaultToolsTrailer,
            posture: SafetyPosture = SafetyPosture.Confirming): Instructions =
    new Instructions(
      safety = safety,
      behavior = behavior,
      tools = tools,
      personality = personality,
      guidelines = guidelines,
      toolsTrailer = toolsTrailer,
      posture = posture)

  /**
   * Convenience factory for autonomous-action posture — the agent acts
   * directly on user instructions without asking for confirmation. Use
   * this for benchmarks (AgentDojo, etc.) and "agent has been granted
   * full authority" deployments.
   *
   * Sets both the model-facing safety prose ([[AutonomousSafety]])
   * AND the structural [[SafetyPosture.Autonomous]] — the latter
   * bypasses the orchestrator's `requiresUserConsent` gate so the
   * agent doesn't have to call `record_consent` on itself to clear
   * gates the user has already implicitly authorized (sigil bug
   * #160).
   */
  def autonomous(personality: String = DefaultPersonality,
                 guidelines: List[String] = Nil): Instructions =
    apply(
      safety = AutonomousSafety,
      personality = personality,
      guidelines = guidelines,
      posture = SafetyPosture.Autonomous)
}
