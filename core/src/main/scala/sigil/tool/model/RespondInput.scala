package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the respond tool.
 *
 * **Field order is load-bearing.** `topicLabel` and `topicSummary` come
 * BEFORE `content` deliberately: when an LLM streams args under a tight
 * token budget, longer-tail truncation hits `content` (often verbose)
 * while leaving the small required topic fields intact. Putting topic
 * fields first means a truncated reply still parses to a typed
 * `RespondInput` with usable topic data; the partial content is what
 * the multipart parser sees, which is fine — the user gets whatever
 * was generated. Reversing this order makes truncation drop the
 * required `topicLabel` field entirely and the whole call becomes
 * unparseable.
 *
 * `topicLabel` — REQUIRED. A concise 3-6 word label describing the
 * subject of THIS turn. The framework's two-step topic classifier
 * compares this against the conversation's current topic and its
 * priors (using both labels and summaries) to decide whether the
 * conversation has shifted, refined, or stayed put.
 *
 * `topicSummary` — REQUIRED. A 1-2 sentence summary of the subject.
 * Doubles as UI display content (sidebar / breadcrumb hover) and as
 * the rich semantic context the classifier uses to compare against
 * prior topics' summaries — so a label like "GIL and I/O-bound code"
 * with summary "Discussion of how Python's GIL affects I/O-bound
 * performance" can be matched back to a prior "Python GIL" entry.
 *
 * `content` is the multipart-format reply (`▶Text`, `▶Code <lang>`,
 * etc.) documented on the tool's description. The `@pattern` annotation
 * enforces the first header at the JSON Schema level so
 * grammar-constrained decoders cannot emit a bare string.
 */
case class RespondInput(topicLabel: String,
                        topicSummary: String,
                        @pattern("""^▶[A-Z][A-Za-z0-9]*(\s+\S+)?\n""") content: String) extends ToolInput derives RW
