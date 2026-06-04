package sigil.provider

import fabric.rw.*

/**
 * The agent's safety posture — drives whether the orchestrator's
 * `requiresUserConsent` gate fires or is bypassed.
 *
 * Sigil bug #160 — autonomous-mode apps (single-user-local agents
 * the user has implicitly authorized by running them) used to be
 * forced to call `record_consent` themselves to clear the gate,
 * fabricating a "user reason" the user never spoke. The posture
 * exposes the intent structurally so the gate can bypass cleanly
 * without each `requiresUserConsent` tool burning an extra LLM
 * iteration on self-approval theater.
 *
 * Pair this with [[Instructions.safety]] — `Confirming` posture
 * with `Instructions.ConfirmingSafety` keeps the confirm-before-
 * destructive UX; `Autonomous` posture with
 * `Instructions.AutonomousSafety` lets the agent execute the user's
 * task end-to-end.
 */
enum SafetyPosture derives RW {

  /**
   * The framework's default. Tools flagged `requiresUserConsent`
   * gate on a `ToolApproval` record; the agent prompts the user
   * via `respond_options` (or similar) and records the verdict
   * with `record_consent`. Right for production deployments where
   * the agent's actions have user-visible consequences.
   */
  case Confirming

  /**
   * The user has pre-authorized the agent to act on their behalf.
   * The orchestrator bypasses the consent gate entirely — tools
   * flagged `requiresUserConsent` dispatch directly. The agent
   * never calls `record_consent` because there's nothing to
   * record; the user already consented by running the app in this
   * posture. Right for benchmarks (AgentDojo, etc.) and
   * single-user-local apps where the agent has full delegated
   * authority.
   */
  case Autonomous
}
