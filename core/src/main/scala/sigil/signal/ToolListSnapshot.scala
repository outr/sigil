package sigil.signal

import fabric.rw.*

/**
 * Server reply to [[RequestToolList]] — the set of [[ToolSummary]]
 * records the viewer is authorized to see, after applying the
 * request's `spaces` / `kinds` filters and the framework's
 * `accessibleSpaces` authz.
 *
 * Delivered via `publishTo(viewer, ...)` so it lands only on the
 * subscriber that asked. Apps that watch tool changes (e.g. agent
 * just authored a new script) push fresh snapshots on relevant
 * events; the request/snapshot vocabulary stays the same either way.
 */
case class ToolListSnapshot(tools: List[ToolSummary]) extends Notice derives RW
