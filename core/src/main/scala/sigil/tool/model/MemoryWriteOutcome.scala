package sigil.tool.model

import fabric.rw.*

/**
 * Outcome of a `save_memory` write, mirroring `UpsertMemoryResult`
 * semantics for keyed saves.
 */
enum MemoryWriteOutcome derives RW {

  /**
   * A new memory record was persisted — either an unkeyed append
   * or the first save under a previously-unused key.
   */
  case Stored

  /**
   * A keyed save found an existing record with unchanged content;
   * only the timestamp moved forward.
   */
  case Refreshed

  /**
   * A keyed save superseded a prior version — the old version was
   * archived and a new one written.
   */
  case Versioned
}
