package sigil.tokenize

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.{Encoding, EncodingType}

/**
 * Tokenizer backed by [[https://github.com/knuddelsgmbh/jtokkit jtokkit]] —
 * the pure-Java port of OpenAI's tiktoken. No network calls, no per-request
 * cost beyond the encoder's internal byte-pair lookup.
 *
 * Accurate for OpenAI ChatGPT-class models (cl100k_base) and the o-series
 * (o200k_base). Reasonable approximation for Anthropic and DeepSeek (within
 * ~10% in practice). Not appropriate for Google Gemini (non-BPE tokenizer)
 * or local models with custom vocabularies — those should fall back to
 * [[HeuristicTokenizer]] until a per-vendor tokenizer is wired.
 *
 * The encoder instance is held internally and shared across calls; jtokkit's
 * `Encoding` is thread-safe.
 */
final class JtokkitTokenizer(encoding: Encoding) extends Tokenizer {
  override def count(text: String): Int = encoding.countTokens(text)

  /** Adds OpenAI's chat-format overhead (~3 tokens per message: role
    * separator + name + ascii padding) to the raw content sum. */
  override def countMessages(messages: Seq[(String, String)]): Int =
    messages.iterator.map { case (role, c) => count(role) + count(c) + 3 }.sum + 3
}

object JtokkitTokenizer {
  private lazy val registry = Encodings.newDefaultEncodingRegistry()

  /** cl100k_base — used by GPT-3.5 and GPT-4 chat completions. Best default
    * for OpenAI Chat Completions and a good approximation for Anthropic
    * and DeepSeek. */
  lazy val OpenAIChatGpt: JtokkitTokenizer =
    new JtokkitTokenizer(registry.getEncoding(EncodingType.CL100K_BASE))

  /** o200k_base — used by GPT-4o / o-series. */
  lazy val OpenAIO200k: JtokkitTokenizer =
    new JtokkitTokenizer(registry.getEncoding(EncodingType.O200K_BASE))
}
