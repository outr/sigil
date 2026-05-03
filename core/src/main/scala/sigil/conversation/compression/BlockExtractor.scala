package sigil.conversation.compression

import rapid.Task
import sigil.Sigil
import sigil.conversation.ContextFrame

/**
 * Pre-compression pass that pulls long content blocks out of frames
 * into persistent [[sigil.information.Information]] records, replacing
 * the long content inline with a one-line placeholder reference that
 * the LLM can resolve on demand via
 * [[sigil.tool.util.LookupTool]].
 *
 * This keeps large blobs (code listings, big JSON payloads, fetched
 * documents) out of the rolling context AND out of any subsequent
 * summarization prompt — the summarizer sees a terse reference
 * instead of 2000 characters, and its output mentions the reference
 * id rather than re-summarizing the blob.
 *
 * Apps opt in by wiring a [[StandardBlockExtractor]] with an
 * `Information` factory. The framework ships [[NoOpBlockExtractor]]
 * as the default — no extraction occurs and
 * [[sigil.Sigil.putInformation]] is never called.
 */
trait BlockExtractor {
  def extract(sigil: Sigil, frames: Vector[ContextFrame]): Task[BlockExtractionResult]
}
