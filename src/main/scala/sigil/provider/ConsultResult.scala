package sigil.provider

/**
 * Result of a one-shot [[Provider.consult]] call.
 *
 * Either the model returned free-form text, OR it called the supplied
 * tool with structured input — depending on whether tools were supplied
 * to consult and whether the model chose to invoke one.
 */
case class ConsultResult(text: Option[String],
                         toolCall: Option[ConsultToolCall],
                         usage: TokenUsage,
                         stopReason: StopReason)
