package sigil.tool.model

import fabric.rw.*

/** Typed result for [[sigil.tool.util.SaveMemoryTool]]. `outcome`
  * mirrors `UpsertMemoryResult` semantics for keyed saves —
  * `"Stored"` (new), `"Refreshed"` (key existed, content unchanged
  * apart from timestamp), `"Versioned"` (prior version archived).
  * Unkeyed appends report `"Stored"`. `memoryId` is the persisted
  * record's id. */
case class SaveMemoryOutput(outcome: String, memoryId: String) derives RW
