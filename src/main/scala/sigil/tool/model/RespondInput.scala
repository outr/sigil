package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the respond tool.
 *
 * `content` is the multipart-format reply (`▶Text`, `▶Code <lang>`, etc.)
 * documented on the tool's description. The `@pattern` annotation enforces
 * the first header at the JSON Schema level so grammar-constrained decoders
 * cannot emit a bare string.
 *
 * `topicLabel` is REQUIRED on every call. A concise 3-6 word label
 * describing the subject of THIS turn. The framework's two-step topic
 * classifier compares this against the conversation's current topic and
 * its priors (using both labels and summaries) to decide whether the
 * conversation has shifted, refined, or stayed put.
 *
 * `topicSummary` is REQUIRED on every call. A 1-2 sentence summary of the
 * subject. Doubles as UI display content (sidebar / breadcrumb hover) and
 * as the rich semantic context the classifier uses to compare against
 * prior topics' summaries — so a labelLikely "GIL and I/O-bound code"
 * with summary "Discussion of how Python's GIL affects I/O-bound performance"
 * can be matched back to a prior "Python GIL" entry.
 */
case class RespondInput(@pattern("""^▶[A-Z][A-Za-z0-9]*(\s+\S+)?\n""") content: String,
                        topicLabel: String,
                        topicSummary: String) extends ToolInput derives RW
