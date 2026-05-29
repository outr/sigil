package sigil.heal

import fabric.rw.*
import lightdb.id.Id
import sigil.event.Event

/**
 * One concrete repair the framework applied to the conversation log
 * during a heal pass. Captured verbatim on [[sigil.event.ConversationHealed]]
 * so the durable event log is a complete audit trail — the synthesised
 * event id is referenceable, the precondition is the human-readable
 * statement of "what was wrong before this action", and any caveats
 * surface partial fixes that need follow-up.
 *
 * @param strategyName        stable id of the [[HealingStrategy]] that
 *                            produced the action — matches
 *                            [[HealingStrategy.name]] of the running
 *                            strategy.
 * @param precondition        human-readable description of the
 *                            corruption this action repairs
 *                            (e.g. "orphan ToolInvoke call_xyz").
 * @param synthesisedEventId  the durable event id the heal created
 *                            or rewrote — pointable from log
 *                            aggregators back at the row.
 * @param synthesisedContent  short summary of the content the heal
 *                            stamped (typically the synthetic marker
 *                            text). Surfaced so log readers don't
 *                            need to chase the event id to know what
 *                            the heal said.
 * @param caveats             non-fatal warnings — partial fix shape,
 *                            assumption-driven branches, anything an
 *                            operator should investigate even though
 *                            the strategy succeeded.
 */
case class HealAction(strategyName: String,
                      precondition: String,
                      synthesisedEventId: Id[Event],
                      synthesisedContent: String,
                      caveats: List[String] = Nil) derives RW
