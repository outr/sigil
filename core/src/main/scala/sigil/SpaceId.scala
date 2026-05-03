package sigil

import fabric.rw.PolyType

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
  /** Stable string identifier — used for indexing, equality, and wire
    * round-trip. Must be unique across all spaces an app registers. */
  def value: String

  /** Human-readable label shown to agents and users when they reason
    * about the space — e.g. picking which space a new memory belongs
    * in. Defaults to `value.capitalize`; concrete spaces override for
    * nicer presentation (`"Project Atlas"`, `"User preferences"`). */
  def displayName: String = value.capitalize

  /** What the space contains and when a memory belongs here. Shown to
    * the unified memory classifier and the agent so the space picker
    * can disambiguate. Apps SHOULD override with one or two sentences;
    * `None` (the default) leaves the classifier with only `displayName`
    * to go on. */
  def description: Option[String] = None
}

object SpaceId extends PolyType[SpaceId]()(using scala.reflect.ClassTag(classOf[SpaceId]))
