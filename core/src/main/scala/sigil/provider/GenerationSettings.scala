package sigil.provider

import fabric.rw.*

final case class GenerationSettings(temperature: Option[Double] = None,
                                    maxOutputTokens: Option[Int] = None,
                                    effort: Option[Effort] = None,
                                    topP: Option[Double] = None,
                                    stopSequences: Vector[String] = Vector.empty)
  derives RW
