package sigil.conversation.compression

import sigil.conversation.ContextFrame

/**
 * Pre-call token estimator — used by the curator to decide whether a
 * prospective turn will fit under the [[ContextBudget]]. A coarse
 * char-count heuristic beats calling a real tokenizer at ~4 chars per
 * token; errs on the high side (≈1 char/4 tokens, never underestimates
 * significantly) so the budget check is conservative.
 */
object TokenEstimator {
  private val CharsPerToken: Double = 4.0

  /** Estimate tokens used by a collection of frames (user-visible
    * content only). System prompts, memories, and summaries are
    * estimated separately via [[estimateText]]. */
  def estimateFrames(frames: Vector[ContextFrame]): Int =
    frames.iterator.map {
      case ContextFrame.Text(c, _, _)        => c.length
      case ContextFrame.ToolCall(_, args, _, _, _) => args.length
      case ContextFrame.ToolResult(_, c, _)  => c.length
      case ContextFrame.System(c, _)         => c.length
    }.sum / CharsPerToken.toInt

  def estimateText(text: String): Int =
    (text.length / CharsPerToken).toInt

  def estimateAll(systemPrompt: String, frames: Vector[ContextFrame], extras: Iterable[String] = Nil): Int =
    estimateText(systemPrompt) + estimateFrames(frames) + extras.iterator.map(estimateText).sum
}
