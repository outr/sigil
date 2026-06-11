package sigil.tool.model

import fabric.rw.*

/**
 * Paging metadata for a chunked [[LookupOutput.Found]] (sigil #389).
 *
 * `lookup` is RETRIEVAL — a large stored record (e.g. the HTML behind a
 * `browser_save_html` Information id) can't fit the inline content cap, and
 * (unlike generated output) there's nothing for the agent to "narrow". So
 * the record's dominant text field is returned in windows: this block names
 * the windowed `field`, the byte range returned (`offset` … `offset +
 * returned`), the `total` size, and `nextOffset`. When `nextOffset` is
 * `Some`, call `lookup` again with `offset = nextOffset` to read the next
 * chunk; `None` means the end was reached.
 */
case class LookupChunk(field: String,
                       offset: Int,
                       returned: Int,
                       total: Int,
                       nextOffset: Option[Int]) derives RW
