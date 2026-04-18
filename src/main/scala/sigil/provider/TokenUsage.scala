package sigil.provider

import fabric.rw.*

case class TokenUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) derives RW
