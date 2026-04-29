package sigil.provider

/**
 * Canonical "coding" [[Mode]] shipped in core. Apps pointing at a
 * coding workflow opt in by listing this in their `modes` override:
 *
 * {{{
 *   override protected def modes: List[Mode] = List(CodingMode)
 * }}}
 *
 * Why ship it: every coding-shaped Sigil consumer would otherwise
 * define an identical 3-line case object. The `name = "coding"`
 * discriminator is what gets persisted in `ModeChange` events and what
 * `change_mode` resolves against, so cross-consumer tooling (analytics,
 * conversation export, downstream catalogs) can rely on a single
 * canonical value rather than each app picking its own.
 *
 * Default `tools = ToolPolicy.Standard` and no skill slot — apps wanting
 * coding-specific instructions or a curated tool roster compose by
 * defining their own `Mode` subclass that delegates to this one's
 * `name`/`description`, or override `Sigil.modes` with their own case
 * object that uses the `"coding"` discriminator.
 *
 * Not auto-registered in [[sigil.Sigil.modes]] — apps opt in explicitly,
 * matching the shape of [[ConversationMode]] (which is the only Mode
 * Sigil prepends automatically).
 */
case object CodingMode extends Mode {
  override val name: String = "coding"
  override val description: String =
    "Writing, debugging, or refactoring code. Optimizes for technical accuracy, syntax, and software-engineering principles."
}
