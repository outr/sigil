package sigil.conversation

import fabric.rw.*
import sigil.event.Event
import sigil.information.Information
import sigil.participant.ParticipantId
import sigil.signal.{Delta, Signal}

/**
 * Structured conversation context — the rich data the orchestrator builds a
 * provider request from. Replaces a flat `Vector[Event]` with semantic
 * sections that have different lifecycle policies (memories never prune,
 * events prune oldest-first when over budget, summaries replace pruned
 * runs).
 *
 * The orchestrator + a context curator collaborate: the curator runs
 * between turns to enforce token budgets, extract memories from older
 * events, and summarize compressed segments. The orchestrator builds the
 * next provider call from the curated context.
 *
 * Tool-call events (`ToolInvoke`) live in `events` alongside messages —
 * they're first-class, persisted, and subject to the same curation
 * policies as any other event type. The curator may prune or collapse
 * tool events more or less aggressively than messages since the event's
 * type is retained.
 *
 * Cross-app reusable; each app injects its own `extraContext` keys for
 * domain-specific augmentation (current page URL, active doc id, project
 * metadata, etc.).
 *
 * @param criticalMemories user/agent directives that can never be pruned
 *                         ("always reply in JSON", "never give medical
 *                         advice", etc.). Injected at the top of every
 *                         provider call.
 * @param memories         extracted facts surfaced by curation. Evictable
 *                         when the memory token budget is full (oldest
 *                         first).
 * @param summaries        summaries of older compressed conversation
 *                         segments. Replace runs of pruned events.
 * @param events           active event log — the working set (Messages,
 *                         ToolInvokes, ModeChanges, etc.). Append-only
 *                         within a turn; subject to curation between turns.
 * @param participantContext per-participant state (active skills, recent
 *                         tools, last-seen index, extra context).
 * @param information      referenced content items the LLM can look up by
 *                         id (article catalog, document index, etc.).
 *                         Injected as a brief catalog block.
 * @param extraContext     app-keyed strings injected into every provider
 *                         call (not participant-scoped — for things like
 *                         "current document title" that all participants
 *                         see).
 */
case class ConversationContext(criticalMemories: Vector[ContextMemory] = Vector.empty,
                               memories: Vector[ContextMemory] = Vector.empty,
                               summaries: Vector[ContextSummary] = Vector.empty,
                               events: Vector[Event] = Vector.empty,
                               participantContext: Map[ParticipantId, ParticipantContext] = Map.empty,
                               information: Vector[Information] = Vector.empty,
                               extraContext: Map[ContextKey, String] = Map.empty) derives RW {

  /**
   * Get or create the per-participant context for the given id.
   */
  def forParticipant(id: ParticipantId): ParticipantContext =
    participantContext.getOrElse(id, ParticipantContext())

  /**
   * Update the per-participant context via a transform function.
   */
  def updateParticipant(id: ParticipantId)(f: ParticipantContext => ParticipantContext): ConversationContext =
    copy(participantContext = participantContext + (id -> f(forParticipant(id))))

  /**
   * Flat list of every active skill contributed by any participant in the
   * given chain. SkillSource keying is strictly per-participant (so within
   * one participant, Mode/Discovery/User don't overwrite each other).
   * Across the chain, skills are unioned without deduplication — two
   * different participants legitimately can (and typically do) have their
   * own Mode-source skill, Discovery-source skill, etc. active
   * simultaneously, and all of them should reach the prompt. The
   * orchestrator calls this at prompt-assembly time; if domain policy
   * warrants deduplication (e.g. by `name`), it happens in the
   * orchestrator, not here.
   */
  def aggregatedSkills(chain: List[ParticipantId]): Vector[ActiveSkillSlot] =
    chain.flatMap(id => forParticipant(id).activeSkills.values).toVector

  /**
   * In-memory analog of `SigilDB.apply(signal)`. Folds a Signal into this
   * context:
   *   - `Event` is appended to `events`; `totalAppended` increments.
   *   - `Delta` finds its target in `events` and replaces it with
   *     `delta.apply(target)`. Deltas whose target isn't in the working
   *     window are silent no-ops (the target was likely pruned).
   *
   * Apps fold this over the orchestrator's `Stream[Signal]` to maintain a
   * live in-memory view that mirrors the persisted state.
   */
  def apply(signal: Signal): ConversationContext = signal match {
    case e: Event =>
      copy(events = events :+ e)
    case d: Delta =>
      val targetId = d.target
      copy(events = events.map(e => if (e._id == targetId) d.apply(e) else e))
  }
}
