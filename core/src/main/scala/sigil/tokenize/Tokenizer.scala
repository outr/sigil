package sigil.tokenize

/**
 * Per-provider token-count strategy. The framework consults a tokenizer
 * before sending wire requests to validate that the request fits within
 * the model's context window — driving both the curator's proactive
 * shedding and the provider's pre-flight gate.
 *
 * Implementations:
 *   - [[HeuristicTokenizer]] — chars / 4 fallback for providers without
 *     a published tokenizer.
 *   - [[JtokkitTokenizer]] — pure-Java port of OpenAI's tiktoken; accurate
 *     for OpenAI/DeepSeek, ~10% optimistic for Anthropic. No network.
 *
 * Providers override `Provider.tokenizer` to bind their preferred
 * implementation. Default is the heuristic.
 */
trait Tokenizer {
  /** Count tokens for a single string. */
  def count(text: String): Int

  /** Count tokens for a sequence of (role, content) pairs in chat-completion
    * shape. Concrete tokenizers may add per-message overhead beyond the raw
    * sum (OpenAI's chat format adds ~3 tokens of metadata per message — role
    * separator + name + ascii padding). The default sums per-content with a
    * conservative +3 per-message charge so consumers using the trait directly
    * still get a roughly-accurate estimate. */
  def countMessages(messages: Seq[(String, String)]): Int =
    messages.iterator.map { case (_, c) => count(c) + 3 }.sum
}
