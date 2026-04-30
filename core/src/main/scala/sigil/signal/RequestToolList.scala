package sigil.signal

import fabric.rw.*
import sigil.SpaceId
import sigil.tool.ToolKind

/**
 * Client-issued Notice asking the framework to enumerate the tools
 * accessible to the calling viewer. Replies arrive as
 * [[ToolListSnapshot]] addressed via `publishTo(viewer, ...)`.
 *
 * `spaces` (optional) narrows to a specific subset of authorized
 * spaces — useful when the UI is showing a single project's tools.
 * If omitted, every space the viewer can access is included.
 *
 * `kinds` (optional) narrows to specific [[ToolKind]] values — the
 * common case for a "Tools" panel that wants user-authored
 * `ScriptKind` records but not the dozens of framework
 * [[sigil.tool.BuiltinKind]] tools. If omitted, all kinds are
 * included.
 *
 * Both filters compose with the viewer's authorization: a record is
 * surfaced only when its `space` is in (`spaces.intersect(authorized)`)
 * AND its `kind` matches the `kinds` filter (when both are set).
 */
case class RequestToolList(spaces: Option[Set[SpaceId]] = None,
                           kinds: Option[Set[ToolKind]] = None) extends Notice derives RW
