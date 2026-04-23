package sigil.conversation.compression

import sigil.conversation.ContextFrame
import sigil.information.InformationSummary

/**
 * Output of a [[BlockExtractor]] pass. `frames` is the frame vector
 * with long content blocks replaced by placeholder references;
 * `information` is the catalog of extracted blocks that should be
 * appended to [[sigil.conversation.TurnInput.information]] so the LLM
 * can look them up on demand.
 */
case class BlockExtractionResult(frames: Vector[ContextFrame],
                                 information: Vector[InformationSummary])
