package sigil.conversation.compression

import rapid.Task
import sigil.Sigil
import sigil.conversation.ContextFrame

/**
 * Default [[BlockExtractor]] — returns the input frames unchanged and
 * emits no [[sigil.information.InformationSummary]] entries. Wire a
 * [[StandardBlockExtractor]] (or a custom impl) to enable extraction.
 */
object NoOpBlockExtractor extends BlockExtractor {
  override def extract(sigil: Sigil, frames: Vector[ContextFrame]): Task[BlockExtractionResult] =
    Task.pure(BlockExtractionResult(frames, Vector.empty))
}
