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
 * `content` — REQUIRED. Markdown body. Standard markdown (paragraphs,
 * code fences, tables, lists, links, images, headings) parses into
 * typed [[ResponseContent]] blocks via [[MarkdownContentParser]].
 * Two markdown extensions are also recognised:
 *
 *   - **`> [!Field icon="…"]\n> Label: Value`** — emits a typed
 *     [[ResponseContent.Field]] block for compact labeled metadata.
 *   - **`## Heading`** — opens a [[ResponseContent.Card]] section;
 *     every block under the heading (until the next `##`) becomes the
 *     Card's sections, with the heading as the title.
 *
 * `disposition` — REQUIRED. [[ResponseDisposition.Success]] for normal
 * answers / status / explanations. [[ResponseDisposition.Failure]]
 * when the agent has decided it cannot complete the requested work
 * (out-of-scope, missing capability, a tool failed and the agent is
 * reporting that). The framework stamps the resulting Message's
 * `disposition` accordingly so the orchestrator's refusal-challenge
 * intercept, downstream "show me failed turns" queries, and UI
 * chrome can all read it directly.
 *
 * `keywords` — OPTIONAL. The agent's keyword tagging of the active
 * subject for memory retrieval. The framework stores them on the
 * conversation's active [[sigil.conversation.TopicEntry]] and uses
 * `topicLabel + topicSummary + keywords` as the query signal that drives
 * non-critical memory retrieval on the next turn. Be specific — name
 * the framework, language, file, identifier, concept — generic keywords
 * (`"task"`, `"help"`, `"code"`) match nothing usefully. Aim for 5–10
 * keywords. Empty list is fine when no relevant memories are expected.
 *
 * `endsTurn` — REQUIRED. `true` when this respond is your COMPLETE
 * reply for this turn (the work is done, the user has the final
 * answer). `false` when you intend to continue working on this turn
 * after the user sees this message — e.g. status updates like
 * "Let me check…", "Reading the auth files now…", "Found 47 matches;
 * narrowing to admin/…". With `endsTurn = false` the framework
 * iterates the loop again immediately so you can run more tools;
 * the respond's content shows the user a progress pulse rather than
 * a permanent reply. No default — the agent makes the decision
 * explicitly every turn so "Let me X…" announcements never
 * accidentally end the turn before the work happens.
 */
case class RespondInput(topicLabel: String,
                        topicSummary: String,
                        content: String,
                        disposition: ResponseDisposition = ResponseDisposition.Success,
                        endsTurn: Boolean,
                        keywords: List[String] = Nil)
  extends ToolInput derives RW
