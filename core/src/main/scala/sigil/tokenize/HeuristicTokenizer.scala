package sigil.tokenize

/**
 * Char-count heuristic — `text.length / 4`, the same approximation
 * [[sigil.conversation.compression.TokenEstimator]] used historically.
 * Errs slightly on the low side for English prose (real tokens average
 * ~3.5 chars in cl100k) and slightly high for code-heavy / non-English
 * input. Good enough for a budget headroom check; not for billing.
 *
 * Stateless singleton — every provider that doesn't override falls
 * back to this.
 */
case object HeuristicTokenizer extends Tokenizer {
  override def count(text: String): Int = text.length / 4
}
