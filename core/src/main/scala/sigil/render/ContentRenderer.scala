package sigil.render

import sigil.tool.model.ResponseContent

/**
 * Renders a sequence of [[ResponseContent]] blocks into a target
 * representation. The same agent-emitted content can flow to multiple
 * destinations — a UI surface, a Slack channel, an email body, a plain
 * digest — by handing the content vector to a different renderer.
 *
 * Implementations must handle every [[ResponseContent]] variant; the
 * compiler enforces this via exhaustivity on the underlying enum.
 * `Card` is recursive — a renderer must call back into itself (or
 * `render`) for each `Card.section` to support nested cards.
 *
 * Output type is generic so non-text targets (`Vector[BlockKitElement]`,
 * `org.jsoup.nodes.Document`, …) compose naturally. The framework ships
 * four `ContentRenderer[String]` defaults — Markdown, Slack mrkdwn,
 * HTML, plain text — and apps register additional named renderers via
 * `Sigil.contentRenderers`.
 */
trait ContentRenderer[Output] {

  /** Render a single block. Recursive entry point for renderers that
   * compose per-block output (most String renderers concatenate; a
   * Slack Block Kit renderer would build elements). */
  def renderBlock(block: ResponseContent): Output

  /** Render a sequence of blocks into one Output. Default: render each
   * block and combine via [[combine]]; renderers may override for
   * sequence-aware behavior (e.g. inserting separators between blocks). */
  def render(blocks: Vector[ResponseContent]): Output =
    blocks.map(renderBlock).foldLeft(empty)(combine)

  /** The "no content" identity element — the starting point for `render`
   * and the result of an empty content vector. */
  def empty: Output

  /** Combine two rendered outputs. For string renderers this is typically
   * concatenation with a separator (`"\n\n"`); for structural renderers
   * it's vector concatenation. */
  def combine(a: Output, b: Output): Output
}
