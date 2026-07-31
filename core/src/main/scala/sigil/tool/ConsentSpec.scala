package sigil.tool

/**
 * Declares that a tool is consent-gated and carries the consent
 * question the agent asks the user before the first call in a
 * conversation. The prompt is required at declaration — a gated tool
 * whose consent question is left to improvisation is exactly the
 * drift this type removes.
 */
final case class ConsentSpec(prompt: String)
