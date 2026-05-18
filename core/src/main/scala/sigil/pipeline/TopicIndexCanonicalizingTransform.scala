package sigil.pipeline

import rapid.Task
import sigil.Sigil
import sigil.event.{
  AgentState, CapabilityResults, Event, Message, ModeChange,
  Reaction, ReadState, Reasoning, Stop, ToolInvoke, ToolLog,
  ToolResults, TopicChange
}
import sigil.signal.Signal

/**
 * Pre-persist transform that overwrites every topic-bearing
 * event's [[Event.topicIndex]] with the server-canonical index
 * derived from the conversation's current topic stack
 * (`conversation.topics.indexWhere(_.id == topicId)`). Sigil bug
 * #80.
 *
 * Stable client-side topic visualisation (per-topic colour strips,
 * dividers, ordinal badges) needs an integer ordinal — hashing
 * `topicId.value` collides on small palettes (~50% chance by
 * topic 5 in a 12-colour palette) and walking `TopicChange`
 * history to reconstruct order duplicates work the server already
 * has. The server is the single source of truth for "which
 * position in the stack does this topicId occupy"; clients trust
 * the stamped index without re-deriving.
 *
 * Inbound events that arrive with a stale or made-up index
 * (placeholder pushed by a client that hasn't seen the latest
 * stack, regenerated id, etc.) are overwritten by the canonical
 * value. A `topicId` not currently on the stack falls back to
 * `0` — the bootstrap topic's index — rather than `-1` so
 * downstream colour-palette indexing stays in-bounds. Worker /
 * sub-conversation events whose `topicId` doesn't resolve to
 * the parent conversation's stack also fall back to `0`.
 */
object TopicIndexCanonicalizingTransform extends InboundTransform {

  override def apply(signal: Signal, self: Sigil): Task[Signal] = signal match {
    case e: Event =>
      self.withDB(_.conversations.transaction(_.get(e.conversationId))).map {
        case Some(conv) =>
          val canonical = conv.topics.indexWhere(_.id == e.topicId) match {
            case n if n >= 0 => n
            case _ => 0
          }
          if (e.topicIndex == canonical) e
          else withTopicIndex(e, canonical)
        case None => e
      }
    case other => Task.pure(other)
  }

  /**
   * Pattern-match dispatch for `withTopicIndex(idx)`. The trait
   * doesn't carry the method (would force an abstract on every
   * Event subtype to implement, churning unrelated callsites);
   * each concrete subtype copies through here. New event types
   * must be added — the catch-all returns the event unchanged
   * so downstream consumers don't break, but the canonical
   * index won't apply until the case is added.
   */
  private def withTopicIndex(e: Event, idx: Int): Event = e match {
    case m: Message => m.copy(topicIndex = idx)
    case ti: ToolInvoke => ti.copy(topicIndex = idx)
    case tr: ToolResults => tr.copy(topicIndex = idx)
    case tl: ToolLog => tl.copy(topicIndex = idx)
    case mc: ModeChange => mc.copy(topicIndex = idx)
    case tc: TopicChange => tc.copy(topicIndex = idx)
    case cr: CapabilityResults => cr.copy(topicIndex = idx)
    case s: Stop => s.copy(topicIndex = idx)
    case as: AgentState => as.copy(topicIndex = idx)
    case r: Reaction => r.copy(topicIndex = idx)
    case rs: ReadState => rs.copy(topicIndex = idx)
    case rr: Reasoning => rr.copy(topicIndex = idx)
    case te: sigil.workflow.event.TaskExecuted => te.copy(topicIndex = idx)
    case wc: sigil.workflow.event.WorkflowRunCompleted => wc.copy(topicIndex = idx)
    case wf: sigil.workflow.event.WorkflowRunFailed => wf.copy(topicIndex = idx)
    case wt: sigil.workflow.event.WorkflowRunStarted => wt.copy(topicIndex = idx)
    case ws: sigil.workflow.event.WorkflowStepCompleted => ws.copy(topicIndex = idx)
    case other => other
  }
}
