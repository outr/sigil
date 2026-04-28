package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `respond` tool.
 *
 * **Field order is load-bearing.** `topicLabel` and `topicSummary` come
 * BEFORE `content` deliberately: when an LLM streams args under a tight
 * token budget, longer-tail truncation hits `content` (often verbose)
 * while leaving the small required topic fields intact. Putting topic
 * fields first means a truncated reply still parses to a typed
 * `RespondInput` with usable topic data; the partial markdown is what
 * the markdown parser sees, which is fine — the user gets whatever was
 * generated.
 *
 * `topicLabel` — REQUIRED. A concise 3-6 word label describing the
 * subject of THIS turn. The framework's two-step topic classifier
 * compares this against the conversation's current topic and its
 * priors to decide whether the conversation has shifted, refined, or
 * stayed put.
 *
 * `topicSummary` — REQUIRED. A 1-2 sentence summary. Doubles as UI
 * display content (sidebar / breadcrumb hover) and as the rich semantic
 * context the classifier uses to compare against prior topics' summaries.
 *
 * `content` — REQUIRED. Plain markdown. Code fences for code blocks,
 * `# heading` for headings, `![alt](url)` for images, lists / links /
 * tables work. The framework parses the markdown into typed
 * [[ResponseContent]] blocks via [[MarkdownContentParser]] at
 * turn-settle time. For interactive choices, labeled fields, or typed
 * failure signals — markdown can't express those — call the dedicated
 * atomic tools (`respond_options`, `respond_field`, `respond_failure`)
 * instead of (or alongside) `respond`.
 */
case class RespondInput(topicLabel: String,
                        topicSummary: String,
                        content: String) extends ToolInput derives RW
