package sigil

/**
 * A general-purpose scope identifier used for multi-tenancy across
 * Sigil's persisted resources — memories, tools, and any future
 * app-scoped record.
 *
 * Sigil core makes no assumption about what spaces exist — apps define
 * their own concrete subtypes (GlobalSpace, ProjectSpace, UserSpace,
 * per-conversation session spaces, whatever) and register them via
 * `Sigil.spaceIds` so the polymorphic RW can round-trip them.
 *
 * Used as:
 *   - `ContextMemory.spaceId` — the space a memory lives in.
 *   - `Tool.spaces` — the spaces a tool is visible to (empty = global).
 *   - `Sigil.findMemories(spaces: Set[SpaceId])` — which spaces to
 *     search when assembling turn-relevant memory.
 *   - `Sigil.accessibleSpaces(chain)` — the caller's authorized set,
 *     used to filter `find_capability` results.
 */
trait SpaceId {
  def value: String
}

object SpaceId extends PolyType[SpaceId]
