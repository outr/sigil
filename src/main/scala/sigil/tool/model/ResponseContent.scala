package sigil.tool.model

import fabric.rw.*
import spice.net.URL

/**
 * Structured response content blocks produced by the LLM as part of an assistant
 * response. Each variant represents a different kind of content the UI can render.
 *
 * Discriminated by `type` in JSON form — auto-derived schema produces a
 * strict `oneOf` via DefinitionToSchema.
 */
enum ResponseContent derives RW {

  /**
   * Plain text.
   */
  case Text(text: String)

  /**
   * Source code, shell commands, or configuration.
   */
  case Code(code: String, language: Option[String] = None)

  /**
   * Structured table.
   */
  case Table(headers: List[String], rows: List[List[String]])

  /**
   * Code diff or patch.
   */
  case Diff(diff: String, filename: Option[String] = None)

  /**
   * Ordered or unordered list.
   */
  case ItemList(items: List[String], ordered: Boolean = false)

  /**
   * Navigable link.
   */
  case Link(url: URL, label: String)

  /**
   * Citation / source reference.
   */
  case Citation(source: String, excerpt: Option[String] = None, url: Option[URL] = None)

  /**
   * Formatted prose — last resort, only when no other type fits.
   */
  case Markdown(text: String)

  /**
   * Section or card heading.
   */
  case Heading(text: String)

  /**
   * Labeled key/value field — the workhorse for card-shaped content (news
   * items, status summaries, product cards). Renderers decide whether to
   * display as a horizontal row, an icon-prefixed line, or a Slack field
   * element. The optional `icon` is a semantic hint — renderers map it to
   * whatever icon set they support.
   */
  case Field(label: String, value: String, icon: Option[String] = None)

  /**
   * Visual separator between logical sections of a response. Carries no
   * content — each renderer translates to its native separator (HR in HTML,
   * a blank Block Kit divider in Slack, `---` in plain text).
   */
  case Divider

  /**
   * A structured set of options the user can choose from. Lets an agent ask a
   * bounded multiple-choice question as part of a respond message — typically
   * alongside prose (e.g. `▶Text` + `▶Options`).
   *
   * Semantics:
   *   - `allowMultiple = false` (default) — exactly one option may be selected.
   *   - `allowMultiple = true` — zero or more options may be selected; any option
   *     marked `exclusive = true` cannot be combined with others (useful for a
   *     "None of the above" escape hatch).
   *
   * A free-text answer is always available — the user can ignore the options
   * and reply in natural language. This block only describes the structured
   * choices the agent is offering.
   *
   * UI renders the list in the order given; numbering is implicit.
   */
  case Options(prompt: String, options: List[SelectOption], allowMultiple: Boolean = false)

  /**
   * Signal that the agent could not complete the task. Use this content type
   * when responding with a failure — the orchestrator can pattern-match on it
   * to decide whether to retry, alert, or surface the message as an error UI.
   *
   * `recoverable = true` indicates the failure may succeed on retry (transient
   * issues like network errors). `false` indicates the failure is permanent
   * for this request (missing permissions, unsupported input, etc.).
   */
  case Failure(reason: String, recoverable: Boolean = false)
}
