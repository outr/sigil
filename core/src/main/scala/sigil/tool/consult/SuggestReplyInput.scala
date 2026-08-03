package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Typed shape for the framework's reply-suggestion consult: the
 * messages the consulted model predicts the USER is most likely to
 * send next, given the agent's just-settled reply and a bounded tail
 * of the exchange.
 *
 * Each entry is written in the user's voice — first person, no
 * quoting, no markup — so a client can drop `suggestions.head`
 * straight into a composer as type-ahead text or render the whole
 * list as tappable chips.
 */
case class SuggestReplyInput(suggestions: List[String]) extends ToolInput derives RW
