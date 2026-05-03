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
  /** Whether jtokkit is on the classpath. Probed once at startup; the result
    * is captured in `available` and consulted by [[OpenAIChatGpt]] /
    * [[OpenAIO200k]] so a missing dep degrades to [[HeuristicTokenizer]]
    * instead of throwing `NoClassDefFoundError` at the first agent turn.
    * Bug #76 — `sigil-core`'s declared `jtokkit` dependency doesn't always
    * resolve transitively into a downstream consumer's classpath; the
    * sigil-all umbrella explicitly redeclares it, but this fallback
    * protects future packaging regressions in the same shape. */
  val available: Boolean = {
    try {
      Class.forName("com.knuddels.jtokkit.Encodings")
      true
    } catch {
      case _: ClassNotFoundException | _: NoClassDefFoundError => false
    }
  }

  /** Per-JVM warn-once flag — emit a single WARN the first time a caller
    * asks for a Jtokkit-backed tokenizer when jtokkit isn't on the
    * classpath. Repeating the WARN per call would be noise. */
  @volatile private var warnedMissing: Boolean = false

  private def warnFallback(name: String): Unit = synchronized {
    if (!warnedMissing) {
      warnedMissing = true
      scribe.warn(
        s"jtokkit is not on the classpath — `JtokkitTokenizer.$name` falling back to " +
          s"HeuristicTokenizer (4-chars-per-token approximation). Token-budget estimates " +
          s"will be coarser. Add `\"com.knuddels\" % \"jtokkit\" % \"<version>\"` to your " +
          s"dependencies (or depend on `sigil-all`, which re-declares it explicitly) to restore " +
          s"accurate counts. See bugs/76."
      )
    }
  }

  private lazy val registry =
    if (available) Encodings.newDefaultEncodingRegistry() else null

  /** Decision helper extracted so both `OpenAIChatGpt` / `OpenAIO200k`
   * lazy vals AND test code can verify the fallback path
   * deterministically. Production callers always pass `available`
   * for `probe`; the spec for bug #76 passes `false` to verify the
   * heuristic path is wired correctly without needing to actually
   * remove jtokkit from the classpath. Public for test access; not
   * intended as a stable API. */
  def selectTokenizer(probe: Boolean,
                      encodingFactory: () => Tokenizer,
                      slotName: String): Tokenizer =
    if (probe) encodingFactory()
    else { warnFallback(slotName); HeuristicTokenizer }

  /** cl100k_base — used by GPT-3.5 and GPT-4 chat completions. Best default
    * for OpenAI Chat Completions and a good approximation for Anthropic
    * and DeepSeek. Returns a [[HeuristicTokenizer]] when jtokkit is
    * missing; logs a one-time WARN at first access. */
  lazy val OpenAIChatGpt: Tokenizer = selectTokenizer(
    probe = available,
    encodingFactory = () => new JtokkitTokenizer(registry.getEncoding(EncodingType.CL100K_BASE)),
    slotName = "OpenAIChatGpt"
  )

  /** o200k_base — used by GPT-4o / o-series. Returns a [[HeuristicTokenizer]]
    * when jtokkit is missing; logs a one-time WARN at first access. */
  lazy val OpenAIO200k: Tokenizer = selectTokenizer(
    probe = available,
    encodingFactory = () => new JtokkitTokenizer(registry.getEncoding(EncodingType.O200K_BASE)),
    slotName = "OpenAIO200k"
  )
}
