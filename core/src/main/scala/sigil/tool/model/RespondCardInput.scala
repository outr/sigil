package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `respond_card` tool. Builds a single composite Card
 * block from the standard [[ResponseContent]] palette (Heading,
 * Field, Code, ItemList, Image, Options, Table, …) and emits one
 * Message containing it.
 *
 *   - `topicLabel` / `topicSummary` — same semantics as `respond`
 *     (3-6 word label, 1-2 sentence summary). Topic-shift resolution
 *     uses these.
 *   - `card` — the composed card. Recursive: card sections can
 *     themselves be Cards for nested groups.
 *
 * For multi-card responses (e.g. a list of dashboard cards), use
 * [[RespondCardsInput]] instead.
 */
case class RespondCardInput(topicLabel: String,
                            topicSummary: String,
                            card: ResponseContent.Card) extends ToolInput derives RW
