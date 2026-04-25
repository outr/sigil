package sigil.tool

import fabric.rw.RW
import rapid.Task

/**
 * Resolves [[Tool]]s for capability-discovery and by-name lookup.
 * Backed by [[DbToolFinder]] in production; apps can override with
 * their own implementation for in-memory test catalogs, marketplace
 * integrations, or union-of-sources strategies.
 *
 * Filtering happens inside the finder — no framework post-filter. The
 * reference semantics live in [[DiscoveryFilter]].
 */
trait ToolFinder {
  /** Polymorphic-RW registrations for every [[ToolInput]] subclass the
    * finder's tools may emit. Sigil registers these into the
    * `ToolInput` poly at init. */
  def toolInputRWs: List[RW[? <: ToolInput]]

  /** Find tools matching a discovery request: keyword + mode + space
    * filters, scored. */
  def apply(request: DiscoveryRequest): Task[List[Tool]]

  /** Exact-name lookup. Used by the orchestrator and the agent
    * dispatcher to resolve a tool the caller already named. */
  def byName(name: ToolName): Task[Option[Tool]]
}
