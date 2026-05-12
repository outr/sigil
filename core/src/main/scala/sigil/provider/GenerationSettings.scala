package sigil.provider

import fabric.rw.*

final case class GenerationSettings(temperature: Option[Double] = None,
                                    maxOutputTokens: Option[Int] = None,
                                    effort: Option[Effort] = None,
                                    topP: Option[Double] = None,
                                    stopSequences: Vector[String] = Vector.empty,
                                    /** Reasoning-mode toggle for thinking-capable
                                      * models. Providers translate to their own
                                      * protocol; non-reasoning models ignore.
                                      * Default `Auto` preserves model / deployment
                                      * defaults. Bug #155. */
                                    reasoningMode: ReasoningMode = ReasoningMode.Auto)
  derives RW
