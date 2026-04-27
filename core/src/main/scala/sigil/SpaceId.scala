package sigil

/**
 * A general-purpose scope identifier used for multi-tenancy across
 * Sigil's persisted resources — memories, tools, and any future
 * app-scoped record.
 *
 * **Single-assignment rule:** a record gets exactly one `SpaceId`.
 * Never `Option[SpaceId]`, never `Set[SpaceId]`. If a tool needs to be
 * accessible under a different space, copy the record. Multi-space
 * *queries* (find me memories across these spaces) do still take a
 * `Set[SpaceId]` — the rule applies to assignment, not lookup.
 *
 * Sigil ships exactly one concrete case — [[GlobalSpace]] — because
 * every framework-shipped tool (`respond`, `change_mode`, `stop`, …)
 * needs a real space and "visible to everyone" is a real concept the
 * framework owns. Apps define their own subtypes (ProjectSpace,
 * UserSpace, per-conversation session spaces) and register them via
 * `Sigil.spaceIds` so the polymorphic RW can round-trip them.
 *
 * Used as:
 *   - `ContextMemory.spaceId` — the space a memory lives in.
 *   - `Tool.space` — the single space a tool is visible to.
 *   - `Sigil.findMemories(spaces: Set[SpaceId])` — which spaces to
 *     search when assembling turn-relevant memory.
 *   - `Sigil.accessibleSpaces(chain)` — the caller's authorized set,
 *     used to filter `find_capability` results.
 */
trait SpaceId {
  def value: String
}

object SpaceId extends PolyType[SpaceId]
