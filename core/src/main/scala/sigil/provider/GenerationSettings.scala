package sigil.provider

import fabric.rw.*
import scala.annotation.nowarn

final case class GenerationSettings(temperature: Option[Double] = None,
                                    /**
                                     * Sigil #276 — the typed form of "how many tokens
                                     * may the model produce on this turn". Default
                                     * [[OutputTokenCap.ModelMax]] resolves to the
                                     * model's registered `maxCompletionTokens` at
                                     * provider-render time (with a known-Anthropic-
                                     * ceilings seed and a 4096 safety fallback). Apps
                                     * setting a deliberate cost / latency cap use
                                     * [[OutputTokenCap.Below]].
                                     */
                                    outputTokenCap: OutputTokenCap = OutputTokenCap.ModelMax,
                                    /**
                                     * @deprecated Use [[outputTokenCap]]. Sigil #276 —
                                     *   `Some(N)` historically meant both "I want
                                     *   the model's max" (consumers using it as a
                                     *   non-truncation knob) AND "deliberate cap
                                     *   below the model's max"; the new typed
                                     *   [[OutputTokenCap]] disambiguates. When set,
                                     *   this field maps to [[OutputTokenCap.Below]]
                                     *   and supersedes [[outputTokenCap]] for the
                                     *   compat shim; new code should set
                                     *   `outputTokenCap` directly.
                                     */
                                    @deprecated("Use `outputTokenCap = OutputTokenCap.Below(n)` — sigil #276", "1.1.0")
                                    maxOutputTokens: Option[Int] = None,
                                    effort: Option[Effort] = None,
                                    topP: Option[Double] = None,
                                    stopSequences: Vector[String] = Vector.empty,
                                    /**
                                     * Reasoning-mode toggle for thinking-capable
                                     * models. Providers translate to their own
                                     * protocol; non-reasoning models ignore.
                                     * Default `Auto` preserves model / deployment
                                     * defaults.
                                     */
                                    reasoningMode: ReasoningMode = ReasoningMode.Auto,
                                    /**
                                     * Per-call HTTP-transport override. `None`
                                     * (default) uses the provider's own default
                                     * (e.g. streaming for Anthropic/OpenAI,
                                     * non-streaming for Cloudflare's reasoning
                                     * models — see
                                     * [[sigil.provider.wire.OpenAIChatCompletions.Config.streaming]]).
                                     * `Some(true)` forces streaming, `Some(false)`
                                     * forces a single non-streaming request whose
                                     * events are synthesized from the one response.
                                     */
                                    streaming: Option[Boolean] = None)
  derives RW {

  /**
   * Sigil #276 — the effective cap, resolving the deprecation shim.
   * When both fields are set, the legacy `maxOutputTokens` wins so
   * pre-#276 calls keep their behaviour during the deprecation window.
   * Internal consumers (providers, paraphrase-loop tightening) read
   * this method instead of either field directly.
   */
  def effectiveCap: OutputTokenCap = (maxOutputTokens: @nowarn("cat=deprecation")) match {
    case Some(n) => OutputTokenCap.Below(n)
    case None => outputTokenCap
  }

  /**
   * Convenience for in-flight tightening (paraphrase loop, forced
   * synthesis) — produce a copy whose effective cap is at most `n`.
   * If the current cap is already lower, keep it; otherwise lower to
   * [[OutputTokenCap.Below]] `n`.
   */
  def tightenedTo(n: Int): GenerationSettings = effectiveCap match {
    case OutputTokenCap.Below(existing) if existing <= n => this
    case _ => copy(outputTokenCap = OutputTokenCap.Below(n), maxOutputTokens = None)
  }

  /**
   * Sigil #276 — the explicit wire-side cap, if any. Used by providers
   * where `max_tokens` is OPTIONAL on the wire (OpenAI, Google,
   * llama.cpp's chat-completions surface): emit the field when the
   * caller asked for a deliberate [[OutputTokenCap.Below]] cap;
   * omit it when [[OutputTokenCap.ModelMax]] (let the model produce
   * up to its own ceiling — server side decides). Anthropic, where
   * the field is required, resolves the registry-derived max via its
   * own helper instead.
   */
  def explicitWireMaxTokens: Option[Int] = effectiveCap match {
    case OutputTokenCap.Below(n) => Some(n)
    case OutputTokenCap.ModelMax => None
  }
}

object GenerationSettings {

  /**
   * Defaults tuned for [[sigil.tool.consult.ConsultTool]]-style
   *    88|     *
   *    89|     * Bounded `outputTokenCap` prevents reasoning-mode models from
   *    90|     * running away on internal `reasoning_content`.
   *    91|     * `reasoningMode = Off` keeps thinking-channel tokens from
   *    92|     * competing with the structured emission. Callers that genuinely
   *    93|     * need long-form free-text generation override per call.
   */
  val classifierDefault: GenerationSettings = GenerationSettings(
    outputTokenCap = OutputTokenCap.Below(1500),
    reasoningMode = ReasoningMode.Off
  )
}
