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

