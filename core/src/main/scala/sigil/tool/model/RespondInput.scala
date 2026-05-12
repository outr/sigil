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
 * `content` — REQUIRED. Tagged-union picking the reply shape
 * (sigil bug #157): [[RespondContent.Text]] for plain markdown,
 * [[RespondContent.Failure]] when the task can't be completed,
 * [[RespondContent.Field]] for a single labeled key/value,
 * [[RespondContent.Options]] for a structured multi-choice prompt.
 * One tool call covers every user-facing reply shape; the framework
 * dispatches on the discriminator at turn-settle time.
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
                        content: RespondContent,
                        endsTurn: Boolean,
                        keywords: List[String] = Nil) extends ToolInput derives RW
