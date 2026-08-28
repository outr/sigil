package sigil.conversation.compression

import rapid.Task
import sigil.Sigil
import sigil.conversation.ContextMemory

/**
 * Ingest-time distillation of a memory: produce the compressed render
 * `summary` (and optionally retrieval-optimized embedding text) for a
 * fact whose full text is too large to inject into every turn.
 *
 * Wired via `Sigil.memoryDistiller` (default `None` — off). When set,
 * [[sigil.Sigil.persistMemory]] / `persistMemories` consult it after
 * classification and before the write, so the stored record carries a
 * genuine one-line `summary` distinct from its `fact` — which is what
 * lets the Memories section inject five tight lines instead of five
 * full passages, with the `[full: lookup(...)]` drill-down handle
 * recovering the verbatim text on demand.
 *
 * This is a BUILD-TIME hook: it runs when memories are created
 * (corpus imports, extraction), not on the turn hot path, so an
 * implementation may use a strong model without affecting the runtime
 * model's cost or latency. Return `None` to leave a memory untouched
 * — the shipped [[ConsultMemoryDistiller]] skips short facts and
 * facts whose caller already authored a distinct summary.
 */
trait MemoryDistiller {
  def distill(sigil: Sigil, memory: ContextMemory): Task[Option[MemoryDistillation]]
}
