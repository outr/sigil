package sigil.tokenize

/**
 * Char-count heuristic for budget headroom checks. Uses 3.5
 * chars/token (`length × 2 / 7`) so the estimate skews conservative
 * — schema-dense JSON and code average ~3 chars/token in cl100k,
 * English prose averages ~4.
 *
 * Not for billing; for that the real per-provider tokenizer is the
 * answer (jtokkit cl100k for OpenAI/Anthropic, the Google /
 * llama.cpp fallbacks elsewhere). This default is what providers
 * use when they don't override [[Tokenizer]].
 *
 * Stateless singleton.
 */
case object HeuristicTokenizer extends Tokenizer {
  override def count(text: String): Int = (text.length.toLong * 2 / 7).toInt
}
