package sigil.provider

import fabric.rw.*

final case class GenerationSettings(temperature: Option[Double] = None,
                                    maxOutputTokens: Option[Int] = None,
                                    effort: Option[Effort] = None,
                                    topP: Option[Double] = None,
                                    stopSequences: Vector[String] = Vector.empty,
                                    /**
                                     * Reasoning-mode toggle for thinking-capable
                                     * models. Providers translate to their own
                                     * protocol; non-reasoning models ignore.
                                     * Default `Auto` preserves model / deployment
                                     * defaults. Bug #155.
                                     */
                                    reasoningMode: ReasoningMode = ReasoningMode.Auto)
  derives RW

object GenerationSettings {

  /**
   * Defaults tuned for [[sigil.tool.consult.ConsultTool]]-style
   * narrow-output decisions — a single tool call carrying a small
   * structured payload (work-type classifier, complexity tier,
   * memory extractor, topic-shift judge, etc.).
   *
   * Bounded `maxOutputTokens` prevents reasoning-mode models from
   * running away on internal `reasoning_content` and emitting
   * `finish_reason: length` with no tool_call (sigil bug #196).
   * `reasoningMode = Off` keeps thinking-channel tokens from
   * competing with the structured emission. Callers that genuinely
   * need long-form free-text generation override per call.
   */
  val classifierDefault: GenerationSettings = GenerationSettings(
    maxOutputTokens = Some(1500),
    reasoningMode = ReasoningMode.Off
  )
}
