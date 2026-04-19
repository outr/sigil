package sigil.tool.core

import rapid.Task
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolInput}

/**
 * Discovers tools accessible to a chain of participants. The
 * implementation is application-provided — sigil core makes no
 * assumption about what each `ParticipantId` in the chain means
 * (user/agent/service, authenticated/anonymous, admin/normal). Interpreting
 * the chain into a permitted tool set is entirely the app's responsibility.
 *
 * Consumed by:
 *   - [[FindCapabilityTool]] when the agent asks "what tools exist for this
 *     request?"
 *   - application-level `/find_capability` slash commands a user may invoke
 *     directly (in which case the chain is typically just `[userId]`)
 *
 * See [[sigil.tool.ToolContext]] for how the chain is constructed at
 * invocation time.
 */
trait ToolManager {

  /**
   * Return tools matching `query`, scoped to the combined access of
   * `participants`. The chain's order is typically origin-first, immediate
   * caller last. Ordering of the returned list is implementation-defined
   * (usually by match score).
   */
  def find(query: String, participants: List[ParticipantId]): Task[List[Tool[? <: ToolInput]]]

  /**
   * The full app-specific tool catalog this manager knows about. Used by
   * `Sigil` at initialization to register each tool's `ToolInput` RW into
   * the polymorphic discriminator — without this, a deserialized
   * `ToolInvoke.input` field would fail to dispatch to the correct subtype.
   *
   * Synchronous because it's metadata read once at startup. Apps with truly
   * dynamic tool catalogs that can't enumerate cheaply may return `Nil` here
   * and register input RWs through some other path; in that case
   * `find_capability` results that include those tools won't round-trip
   * through the persisted state.
   *
   * Should NOT include framework core tools — those are registered by sigil
   * automatically.
   */
  def all: List[Tool[? <: ToolInput]]
}
