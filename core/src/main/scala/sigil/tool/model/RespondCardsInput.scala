package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `respond_cards` tool — multi-card variant of
 * [[RespondCardInput]]. Useful when the agent's response is a
 * sequence of distinct cards (e.g. several dashboard tiles, a list
 * of search results each rendered as its own card).
 *
 *   - `topicLabel` / `topicSummary` — same semantics as `respond`.
 *   - `cards` — the sequence of cards, rendered in order.
 */
case class RespondCardsInput(topicLabel: String,
                             topicSummary: String,
                             cards: Vector[ResponseContent.Card]) extends ToolInput derives RW
