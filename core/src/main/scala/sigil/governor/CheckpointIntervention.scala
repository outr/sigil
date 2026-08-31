package sigil.governor

import sigil.orchestrator.Directive

/**
 * Outcome envelope for a progress checkpoint's intervention.
 * Distinguishes the two recoverable shapes the framework can hit:
 *
 *   - `askingUser = false` — stall detector trip, no-progress streak,
 *     or any other "agent should now do something different" case. The
 *     `directive` is the typed payload built where the reflection text
 *     was authored, so the persisted [[sigil.tool.DirectiveInput]] and
 *     the prose the agent reads come from the same value.
 *   - `askingUser = true` — the reflector self-reported `shouldAskUser`.
 *     Genuine "I need user input to proceed"; the governor substitutes
 *     the ask-user (or, in a directed worker, ask-supervisor) directive
 *     for the one carried here.
 *
 * `terminal = true` marks a hard stall — an identical-call streak past
 * [[sigil.Sigil.hardStallIdenticalCallLimit]] that ignored every
 * cooperative nudge — which forces terminal synthesis rather than
 * continuing the loop.
 */
final private[sigil] case class CheckpointIntervention(directive: Directive,
                                                       askingUser: Boolean,
                                                       terminal: Boolean = false)
