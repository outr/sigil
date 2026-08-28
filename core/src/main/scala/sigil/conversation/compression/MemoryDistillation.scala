package sigil.conversation.compression

/**
 * Output of a [[MemoryDistiller]] pass over one memory.
 *
 *   - `summary` — the compressed per-turn render form (one line): what
 *     the Memories / Pinned sections inject instead of the full fact,
 *     with the drill-down handle pointing at the full record.
 *   - `embeddingText` — optional retrieval-optimized rewrite of the
 *     fact (self-contained, entities named explicitly). When set it is
 *     what gets embedded and lexically indexed in place of the raw
 *     fact; the fact itself is untouched and stays the record of
 *     truth.
 */
case class MemoryDistillation(summary: String,
                              embeddingText: Option[String] = None)
