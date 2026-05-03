package sigil

/**
 * Framework sentinel [[SpaceId]] — visible to every caller regardless
 * of `Sigil.accessibleSpaces`. The default `Tool.space` (so
 * framework-shipped tools work in every app without extra wiring), and
 * the default the `ScriptSigil.scriptToolSpace` hook returns when the
 * agent doesn't specify a target.
 *
 * Apps that want a fully tenanted tool catalog never assign
 * `GlobalSpace` to their own records — they use their own `SpaceId`
 * subtypes and override `accessibleSpaces` to scope visibility.
 *
 * Auto-registered by `Sigil.instance` via `RW.static(GlobalSpace)` so
 * apps don't list it in their `spaceIds` overrides.
 */
case object GlobalSpace extends SpaceId {
  override val value: String = "global"
  override val displayName: String = "Global"
  override val description: String =
    "Visible to every caller in every conversation. Use for facts that apply to " +
      "all users / projects (framework defaults, universal safety rules). Apps " +
      "that scope memory to user / project spaces never assign this to their own " +
      "records."
}
