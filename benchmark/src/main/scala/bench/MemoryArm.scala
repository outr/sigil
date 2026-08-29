package bench

/**
 * One arm of [[MemoryArmsBench]]. The arms bracket the memory
 * machinery the way an honest benchmark must: a do-nothing baseline,
 * the feature under test in its two shapes (passive and agentic), the
 * ingest upgrade, and a naive control that must NOT win — if stuffing
 * every fact into the prompt scores as well as retrieval, the
 * retrieval machinery isn't paying for itself.
 */
enum MemoryArm {
  /** No memory at all — the floor. The model answers from its own
    * weights; on corpus-specific facts it must hedge or confabulate. */
  case Baseline

  /** Passive per-turn recall: `StandardMemoryRetriever` (default
    * knobs, limit 5) injects memories into the prompt; the agent has
    * no retrieval tool. This is the #413 surface under test. */
  case Passive

  /** Agentic recall: NO passive injection; the agent gets
    * `semantic_search` and must formulate its own queries (and
    * re-query when thin). */
  case Agentic

  /** Passive recall over a corpus that was distilled at ingest
    * (`ConsultMemoryDistiller`): summaries render, retrieval text is
    * embedded/indexed. Measures the 1:1 ingest upgrade on top of
    * Passive. */
  case Distilled

  /** Passive recall over a corpus ingested through
    * `Sigil.ingestCorpusMemories`: each dense passage split into
    * atomic single-fact memories. Measures the 1:N ingest upgrade —
    * the answer to a small model grabbing the wrong half of a dense
    * clause. */
  case Split

  /** Naive control: every corpus fact stuffed into the user message,
    * no retrieval machinery at all. The trivial substitute the
    * machinery has to beat on token cost while matching on accuracy. */
  case Stuffed
}
