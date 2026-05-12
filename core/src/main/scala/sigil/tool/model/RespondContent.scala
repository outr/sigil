package sigil.tool.model

import fabric.rw.*

/**
 * Tagged-union content slot for [[RespondInput]] — sigil bug #157.
 *
 * Collapses the four previous `respond_*` tools
 * (`respond`, `respond_failure`, `respond_field`, `respond_options`)
 * into a single `respond` tool whose `content` discriminator
 * picks the reply shape. The model emits one tool call instead of
 * a four-way decision between near-identical tools; the framework's
 * tool roster shrinks by ~30% on the default surface.
 *
 * Each case maps 1:1 to a [[ResponseContent]] subtype:
 *
 *   - [[Text]]    — Markdown reply. Parsed via [[MarkdownContentParser]]
 *                   into typed [[ResponseContent.Text]] / [[ResponseContent.Code]]
 *                   / etc. blocks at turn-settle time.
 *   - [[Failure]] — Honest "can't do that" signal.
 *   - [[Field]]   — Single labeled key/value (status badges, metadata rows).
 *   - [[Options]] — Structured multi-choice prompt (single- or multi-select).
 *
 * The empirical probe (`bench.RespondUnificationProbe`) verified
 * qwen3.5-9b-q4_k_m picks the right discriminator + emits valid
 * payloads at 4/4 across all four kinds, on par with the prior
 * four-tool baseline. Larger models trivially handle the same
 * surface.
 */
enum RespondContent derives RW {

  /** Plain markdown / text reply. The `content` field accepts
    * markdown — code fences, headings, links, images, lists,
    * tables. Parsed into typed [[ResponseContent]] blocks at
    * turn-settle time. */
  case Text(content: String)

  /** Failure signal — the agent could not complete the requested
    * task. `recoverable = true` indicates the failure may succeed
    * on retry (transient: network blips, rate limits); `false`
    * indicates a permanent failure for this request (missing
    * permissions, unsupported input). */
  case Failure(reason: String, recoverable: Boolean = false)

  /** Labeled key/value field — status summary, news metadata,
    * product attribute. Renderers display this as an icon-prefixed
    * row, Slack `field` element, or horizontal field strip. */
  case Field(label: String, value: String, icon: Option[String] = None)

  /** Structured multi-choice prompt. `allowMultiple = true` =
    * independent choices the user can pick in any combination;
    * `false` = forced single selection. The user is always free to
    * reply with natural language instead of picking from the
    * options — the framework does not require a structured
    * response. */
  case Options(prompt: String, options: List[SelectOption], allowMultiple: Boolean)
}
