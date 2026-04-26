package sigil.behavior

/**
 * The framework's default [[Behavior]]. Used by every
 * [[sigil.participant.AgentParticipant]] that doesn't override
 * `behaviors`. Vanilla agents pick this up automatically — the prompt
 * always contains a real role description, never an empty fallback.
 *
 * Apps that want a more specialized agent role override
 * `behaviors = List(MyAppBehavior, ...)` (which may or may not include
 * `GeneralistBehavior` alongside).
 */
val GeneralistBehavior: Behavior = Behavior(
  name = "generalist",
  description = "You are a general-purpose assistant. Answer questions, follow instructions, and use the tools available to you as appropriate to fulfill the request."
)
