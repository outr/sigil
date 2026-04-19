package sigil.signal

import fabric.rw.*

/**
 * The block type on a streaming content delta. Mirrors the variants of
 * [[sigil.tool.model.ResponseContent]] but represents only the *kind* of a
 * block in flight — not the block's data, which streams through
 * [[ContentDelta.delta]].
 *
 * Subscribers use the kind to decide how to accumulate and render partial
 * content (e.g., markdown rendering for `Markdown`, syntax highlighting for
 * `Code`, JSON-body buffering for `Options` / `Field`).
 */
enum ContentKind derives RW {
  case Text
  case Markdown
  case Code
  case Heading
  case Field
  case Divider
  case Options
  case Table
  case Diff
  case ItemList
  case Link
  case Citation
  case Failure
}
