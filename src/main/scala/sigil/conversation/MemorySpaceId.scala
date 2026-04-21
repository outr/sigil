package sigil.conversation

import sigil.PolyType

/**
 * Identifies the scope a [[ContextMemory]] belongs to. Sigil core makes no
 * assumption about what spaces exist — apps define their own concrete
 * subtypes (GlobalSpace, ProjectSpace, UserSpace, whatever)
 * and register them via `Sigil.memorySpaceIds` so the polymorphic RW can
 * round-trip them.
 *
 * Used as:
 *   - `ContextMemory.spaceId` — the space a memory lives in (one per memory)
 *   - `Sigil.findMemories(spaces: Set[MemorySpaceId])` — which spaces to
 *     search when the curator assembles `TurnInput.memories`
 */
trait MemorySpaceId {
  def value: String
}

object MemorySpaceId extends PolyType[MemorySpaceId]
