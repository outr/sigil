package sigil.conversation

import fabric.rw.*

/**
 * Approval state of a [[ContextMemory]].
 *
 *   - `Approved` — visible to retrievers / tools; the default for
 *                  explicitly-written memories.
 *   - `Pending`  — extracted automatically (per-turn or compression)
 *                  and awaiting user approval; hidden from recall by
 *                  default so auto-extracted noise doesn't leak into
 *                  the agent's context.
 *   - `Rejected` — user-rejected; kept on disk for lineage but hidden
 *                  from retrievers.
 *
 * Apps drive transitions via `Sigil.approveMemory` / `Sigil.rejectMemory`.
 */
enum MemoryStatus derives RW {
  case Pending
  case Approved
  case Rejected
}
