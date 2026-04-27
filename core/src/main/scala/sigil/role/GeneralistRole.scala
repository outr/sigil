package sigil.role

/**
 * The framework's default [[Role]]. Used by every
 * [[sigil.participant.AgentParticipant]] that doesn't override
 * `roles`. Vanilla agents pick this up automatically — the prompt
 * always contains a real role description, never an empty fallback.
 *
 * Apps that want a more specialized agent identity override
 * `roles = List(MyAppRole, ...)` (which may or may not include
 * `GeneralistRole` alongside).
 */
val GeneralistRole: Role = Role(
  name = "generalist",
  description = "You are a general-purpose assistant. Answer questions, follow instructions, and use the tools available to you as appropriate to fulfill the request."
)
