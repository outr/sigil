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
                                 toolsTrailer: String = Instructions.DefaultToolsTrailer) derives RW {
  assert(personality.nonEmpty, "Personality must not be empty!")

  def withPersonality(personality: String): Instructions = copy(personality = personality)

  def withSafety(safety: String): Instructions = copy(safety = safety)

  def withBehavior(behavior: String): Instructions = copy(behavior = behavior)

  def withTools(tools: String): Instructions = copy(tools = tools)

  def withGuidelines(guidelines: String*): Instructions = copy(guidelines = this.guidelines ::: guidelines.toList)

  /** Set or disable the trailing tool-call recap. Pass `""` to suppress. */
  def withToolsTrailer(toolsTrailer: String): Instructions = copy(toolsTrailer = toolsTrailer)

  /** Swap in the pure-discovery tools text. Used by `Provider.renderSystem`
    * when the active roster has stripped the respond family — the default
    * tools text references `respond` as if it were callable, which misleads
    * the model when it isn't. */
  def forPureDiscovery: Instructions = copy(tools = Instructions.PureDiscoveryToolsGuidance)

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
  lazy val render: String =
    (List(personality, core) ::: guidelines ::: List(toolsTrailer))
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
   * Default tool-discovery framing. Bug #48 — establishes
   * discovery-first as the framework's CORE ideology, not a tip.
   *
   * Triage framing (1 / 2 / 3) is what finally moves smaller
   * quantised models off the "I'll just respond" default. The
   * earlier softer phrasings positioned `find_capability` as
   * appropriate "for specialized work", which the model read as a
   * narrow case it could route around for short prompts like
   * "wait 200ms then respond done" — splitting the action half
   * away and answering only the final-reply half via `respond`.
   *
   * Three load-bearing rules:
   *   - "Even ONE word of action means action" — closes the
   *     "this seems trivial enough to fake" loophole.
   *   - "If the user bundles an action AND a final reply, the action
   *     half is still an action" — closes the "wait 200ms then
   *     respond done" loophole specifically.
   *   - "When in doubt, choose action" — biases the model toward
   *     discovery; the cost of an unnecessary `find_capability` is
   *     one extra turn, the cost of skipping is silently degrading
   *     the user's task.
   */
  val DefaultToolsGuidance: String =
    """TOOLS — discovery-first is the framework's CORE ideology. Internalize this.
      |
      |The catalog is large. Your visible roster (`find_capability`, `respond`, `stop`, …) is intentionally tiny. Almost every action the user asks for has a dedicated tool — your job is to find it, not to fake it through `respond`.
      |
      |Triage every user message into one of these:
      |
      |1. The user asked you to DO something — wait, fetch, save, look up, send, run, edit, search, write code, anything action-shaped. Even ONE word of action means action.
      |   → Call `find_capability` FIRST with keywords describing the task. Most tools (filesystem, LSP, BSP, memory, web fetch, MCP) are universally discoverable — they surface from `find_capability` regardless of the active mode. Try the current mode before switching.
      |   → Only call `change_mode` when discovery surfaces a Mode-typed match (not a Tool match) for the task, or when you've tried a discovered tool and it returned `RequiresSetup` pointing at a mode-bundled skill. A Mode is for skill / persona shifts, not for unlocking tools — most tools are already unlocked.
      |   → Self-referential requests ("switch models", "what skills do you have", anything you're pattern-matching as out-of-scope) are STILL action requests. Don't refuse based on assumed limits — the catalog usually has the tool. A refusal not preceded by `find_capability` is a bug.
      |
      |2. The user is chatting / asking a knowledge question / following up in the current mode and no action is needed.
      |   → `respond` with the answer.
      |
      |3. The user has indicated the conversation is over.
      |   → `stop`.
      |
      |When in doubt between 1 and 2, choose 1. The cost of an unnecessary `find_capability` is one extra turn; the cost of skipping discovery is silently degrading the user's task.
      |
      |After `change_mode` succeeds, your visible roster is FRESH. Do NOT call `find_capability` again before invoking a tool — the new mode's tools are now directly callable. Pick a tool from the roster and run it. Only re-search if you've actually called a roster tool and it returned a structural failure (`RequiresSetup`, `NotApplicable`, missing precondition). Re-searching after `change_mode` without trying the roster first burns iterations on discovery the framework just handed you.
      |
      |**`find_capability` results are RANKED by relevance.** The top match is the framework's recommendation for your query — not a buffet to scroll through. Default to invoking the rank-1 tool unless its description makes it clearly inappropriate. Do NOT scroll past LSP/BSP/typed/domain-specific tools to pick a generic primitive (`grep`, `glob`, `bash`, `read_file`, `execute_script`) just because you're more familiar with it. The ranked tool is at the top because the framework knows it's the better answer for the query you typed; trust the rank. Generic primitives are scored to sit BELOW the domain-specific tool when both apply — that's not a rendering quirk, it's the framework telling you "use the typed tool when available."
      |
      |**TURN-FLOW DISCIPLINE.** Three tools end your turn — pick the right one for the situation:
      |  - `respond` — you have something to deliver to the user. The conversation is in a settled state from your side.
      |  - `no_response` — you have no message but you're done. Silent housekeeping (a settled internal classification, a tool-only chain with no user-facing output).
      |  - `cancel` — the turn should be abandoned. Use ONLY when the user has explicitly halted you, or you've hit an unrecoverable failure. NEVER use `cancel` to "pause", "transition", "end this step", or "wait for results" — those are not what `cancel` does. There IS no pausing or waiting between tool calls; just call the next tool directly. If `cancel`'s reason reads like a transition ("starting X", "need to fetch Y", "next step"), the framework refuses it and asks you to pick `respond` / `no_response` / the actual next tool.
      |
      |**Tool failures carry structured context.** When a tool result is a `Failure` block with `errorContext`, read `classification` to pick a response shape:
      |  - `UserInputError` — fix the args and retry, or explain to the user what input shape is needed.
      |  - `TransientError` — retry once before giving up.
      |  - `ResourceExhausted` — the operation needs different inputs (smaller, paged), not a retry.
      |  - `FrameworkBug` (high `frameworkBugLikelihood`) — surface to the user with the exception class + message and ask if they want this filed as feedback. Don't keep retrying the same call.
      |  - `ProviderError` — report the upstream issue verbatim.
      |  - `Unknown` — explain what you tried and what the error said; defer to the user on next steps.""".stripMargin

  /**
   * Pure-discovery variant of the TOOLS guidance — used when [[ToolPolicy.PureDiscovery]]
   * has stripped the respond family from the immediate roster. The model sees only
   * `find_capability`, `cancel`, plus any explicit Active tools — so the prompt teaches
   * it to discover `respond` itself rather than expecting it in the roster.
   */
  val PureDiscoveryToolsGuidance: String =
    """TOOLS
      |- You operate in pure-discovery mode. Your immediate tool list is intentionally minimal (`find_capability`, `cancel`, plus any tools your mode/role explicitly added). The full capability catalog — including the user-facing reply tool `respond`, focused modes (coding, planning, …), and specialized tools — is reachable only via `find_capability`.
      |- To produce ANY user-facing reply, you MUST first call `find_capability` with relevant keywords (e.g. `find_capability("respond")` for a plain reply, or keywords describing the task). The returned matches will surface `respond` (and other reply variants), which then become callable on the next turn.
      |- Do NOT call `cancel` unless the user has explicitly halted you or you've hit an unrecoverable failure — `cancel` is for cancellation, NOT for ending a normal turn or transitioning between steps. The right path for "I should answer this" is `find_capability` → call the discovered reply tool.
      |- For specialized work (code, scripts, plans, workflows, etc.), `find_capability` will surface a focused mode — call `change_mode` to switch into it before producing content.
      |- Do NOT answer from memory. Every reply path routes through `find_capability` first.""".stripMargin

  /**
   * Tail recap of the "every reply MUST be a tool call" rule —
   * rendered LAST so it sits within the model's recency-biased
   * attention even after long histories push the front-of-prompt
   * TOOLS guidance out of view. Smaller / quantised models drift to
   * plain-text output without it.
   *
   * Deliberately minimal and tool-neutral: the trailer prevents
   * plain-text drift; it does NOT prescribe which tool. Naming
   * specific tool families biases the model away from the others.
   * Discovery and selection live in [[DefaultToolsGuidance]] and in
   * `find_capability`'s results.
   *
   * Suppress with `Instructions(toolsTrailer = "")`.
   */
  val DefaultToolsTrailer: String =
    """REMINDER: every reply MUST be a tool call. For ACTIONS (anything the user asked you to DO),
      |route per the triage above (`change_mode` first when a listed mode matches; otherwise
      |`find_capability`). `respond` comes after the action runs, not instead of it. Plain text
      |output is dropped silently — wrap your output in whichever tool fits.""".stripMargin

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
            toolsTrailer: String = DefaultToolsTrailer): Instructions =
    new Instructions(safety = safety, behavior = behavior, tools = tools,
                     personality = personality, guidelines = guidelines,
                     toolsTrailer = toolsTrailer)

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

