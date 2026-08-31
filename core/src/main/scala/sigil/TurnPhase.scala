package sigil

import fabric.rw.*

/**
 * The contiguous segments a single agent turn's wall clock divides into,
 * in temporal order. Every instant between the triggering publish and
 * the claim release belongs to exactly one of them, so a turn's phase
 * durations sum to its wall clock.
 *
 * A multi-iteration turn folds every iteration's segments into the same
 * buckets. Iterations after the first have no claim of their own, so the
 * work between one iteration's terminal handling and the next
 * iteration's routing (governor votes, intra-turn compaction, the
 * batched-events commit) lands in [[ClaimToRouting]] alongside the first
 * iteration's trigger assembly.
 */
enum TurnPhase derives RW {

  /**
   * Triggering event's publish → the agent claim being won. Covers the
   * inbound-transform chain, the persist, projections, the hub
   * broadcast, and the fan-out that reaches the claim.
   */
  case PublishToClaim

  /**
   * Claim won → per-turn model routing starting. Covers the iteration's
   * trigger load and chain construction.
   */
  case ClaimToRouting

  /**
   * Model routing — strategy resolution, the latest-user-message read,
   * and the work-type / complexity classifier's own round-trip.
   */
  case Routing

  /**
   * Routing resolved → the [[TurnContext]] being built. Covers curation
   * (frame load, budget resolution, shedding) and always-on skills.
   */
  case ContextAssembly

  /**
   * Context assembled → the main request reaching the wire. Covers
   * roster resolution, request assembly, and the provider pre-flight
   * gate.
   */
  case DispatchToWire

  /**
   * Request on the wire → the last event of the provider's stream. This
   * is the turn's model time. The framework consumes the stream as it
   * arrives, so the per-chunk orchestration between two provider events
   * is inside this phase too — the two cannot be separated after the
   * fact, and on a streaming provider the per-chunk work is small
   * against the inter-chunk wait.
   */
  case ModelStream

  /**
   * Last provider event → the turn's user-visible reply settling.
   * Covers outcome governors, inline tool execution, topic resolution,
   * and the settle's own publish. Pure framework cost with the model
   * already done talking.
   */
  case StreamEndToTerminal

  /**
   * Reply settled → the claim released. Covers the turn's remaining
   * publishes, the batched-events commit, and the release.
   */
  case TerminalToRelease
}
