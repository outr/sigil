package sigil.conversation

/**
 * Discriminator on what happened during
 * [[sigil.Sigil.upsertMemoryByKey]]:
 *
 *   - `Stored`    — no prior memory with this key; a brand-new record
 *                    was inserted.
 *   - `Refreshed` — prior memory exists with identical `fact`; only
 *                    metadata (label/summary/tags/type) was updated
 *                    in place. Same `_id` as the prior.
 *   - `Versioned` — prior memory exists with different `fact`; the
 *                    prior was archived and a new record now
 *                    supersedes it. Different `_id` from the prior.
 */
enum UpsertMemoryResult(val memory: ContextMemory) {
  case Stored(override val memory: ContextMemory) extends UpsertMemoryResult(memory)
  case Refreshed(override val memory: ContextMemory) extends UpsertMemoryResult(memory)
  case Versioned(override val memory: ContextMemory, archived: ContextMemory) extends UpsertMemoryResult(memory)

  /** The same outcome carrying an updated record — used by the write
    * paths that stamp the written memory after the fact (the
    * vector-index provenance stamp) so callers see the stored shape. */
  def withMemory(memory: ContextMemory): UpsertMemoryResult = this match {
    case Stored(_)              => Stored(memory)
    case Refreshed(_)           => Refreshed(memory)
    case Versioned(_, archived) => Versioned(memory, archived)
  }
}
