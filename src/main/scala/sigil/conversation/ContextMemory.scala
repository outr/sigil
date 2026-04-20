package sigil.conversation

import fabric.rw.*

/**
 * A remembered fact attached to a conversation context. Surfaced to the LLM
 * either as part of every provider call (for `Critical`) or as a curated
 * subset bounded by token budget. Keys make memories addressable — a key
 * collision overwrites rather than duplicating.
 */
case class ContextMemory(key: String, fact: String, source: MemorySource) derives RW
