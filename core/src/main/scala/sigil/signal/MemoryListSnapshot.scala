package sigil.signal

import fabric.rw.*
import sigil.tool.model.MemoryListEntry

/**
 * Sigil #292 — server→client [[Notice]] carrying the list of
 * [[MemoryListEntry]] rows matching the recipient viewer's
 * [[RequestMemoryList]] (or an unsolicited push on state change).
 *
 * Each entry reuses the same shape the agent's `list_memories` tool
 * surfaces — UIs that mirror the agent's view of memory get the same
 * fields without re-deriving from the underlying [[sigil.conversation.ContextMemory]].
 */
case class MemoryListSnapshot(memories: List[MemoryListEntry]) extends Notice derives RW
