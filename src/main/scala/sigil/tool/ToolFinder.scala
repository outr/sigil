package sigil.tool

import fabric.rw.RW
import rapid.Task
import sigil.participant.ParticipantId

/**
 * Resolves tool matches for a capability-discovery query. Implementations may
 * be backed by an in-memory catalog, a database, a remote registry, or any
 * combination — sigil core makes no assumption about where tools come from.
 *
 * `participants` is the chain-of-responsibility for the current invocation
 * (origin followed by propagators). Implementations that enforce per-
 * participant access control scope matches accordingly.
 *
 * Invoked from [[sigil.tool.core.FindCapabilityTool]] and any slash-command
 * that dispatches to capability discovery.
 *
 * The finder also declares the bounded set of `ToolInput` RWs its tools use.
 * Tool *instances* may be generated dynamically (DB-backed, per-user), but
 * their Input *types* are a fixed set colocated with whichever finder
 * materializes the tools. Sigil registers these into the polymorphic
 * discriminator at init.
 */
trait ToolFinder {
  def toolInputRWs: List[RW[? <: ToolInput]]

  def apply(keywords: String, participants: List[ParticipantId]): Task[List[Tool[? <: ToolInput]]]

  /**
   * Resolve a single tool by its `schema.name`. Used when a persisted
   * [[sigil.participant.AgentParticipant]] references tools by name and the
   * dispatcher needs to rehydrate them to live `Tool` instances at call
   * time.
   *
   * Default: delegate to `apply(name, participants)` and pick the first
   * returned tool whose name matches case-insensitively. Implementations
   * that can look up by name directly (e.g. in-memory catalogs, indexed DB
   * queries) should override.
   */
  def byName(name: ToolName, participants: List[ParticipantId]): Task[Option[Tool[? <: ToolInput]]] =
    apply(name.value, participants).map(_.find(_.schema.name.value.equalsIgnoreCase(name.value)))
}
