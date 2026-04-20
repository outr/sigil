package sigil.conversation

import fabric.rw.*

/**
 * How a [[ContextMemory]] was created. Drives eviction policy:
 *   - `Critical` memories are user directives ("always reply in Spanish") and
 *     are never pruned.
 *   - `Compression` memories are extracted by the curator when older messages
 *     get summarized; they're prunable when memory budget is exceeded
 *     (oldest first).
 *   - `Explicit` memories are saved by the agent through a memory tool;
 *     prunable like Compression but with their own retention policy.
 */
enum MemorySource derives RW {
  case Critical
  case Compression
  case Explicit
}
