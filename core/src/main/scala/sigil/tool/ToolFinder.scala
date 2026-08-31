package sigil.tool

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

  /**
   * Wire-shape contributions for the tools this finder may surface —
   * each [[ToolIO]] carries the input AND output codec registered
   * into the polymorphic `ToolInput` / `ToolOutput` RWs at init. A
   * finder override contributes its codecs by construction; the
   * static roster's codecs are derived independently at the
   * registration site, so overriding the finder can never silently
   * drop them.
   */
  def toolIO: List[ToolIO[?, ?]]

  /**
   * Find tools matching a discovery request: keyword + mode + space
   * filters, scored.
   */
  def apply(request: DiscoveryRequest): Task[List[Tool]]

  /**
   * Exact-name lookup. Used by the orchestrator and the agent
   * dispatcher to resolve a tool the caller already named.
   */
  def byName(name: ToolName): Task[Option[Tool]]
}
