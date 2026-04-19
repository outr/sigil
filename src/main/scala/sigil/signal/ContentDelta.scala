package sigil.signal

import fabric.rw.*

/**
 * An incremental content update nested inside a [[MessageDelta]].
 *
 * @param kind     the block type this delta contributes to
 * @param arg      optional header argument (e.g. language for `Code`)
 * @param complete true if this delta closes the current block; the next
 *                 `ContentDelta` for the same Message starts a new block.
 *                 Block-level only — the overall Message's completion is a
 *                 separate `MessageDelta.state = Some(Complete)` transition.
 * @param delta    text to append to the current block (or the full JSON body
 *                 for structured kinds like `Options` / `Field`).
 */
case class ContentDelta(kind: ContentKind, arg: Option[String] = None, complete: Boolean, delta: String) derives RW
