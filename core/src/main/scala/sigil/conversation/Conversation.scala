package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.{GlobalSpace, SpaceId}
import sigil.participant.Participant
import sigil.provider.{ConversationMode, Mode}

/**
 * A conversation is a durable scope for events, the participants involved,
 * and any per-conversation metadata. Stored in [[sigil.db.SigilDB.conversations]].
 *
 * `participants` drives the dispatcher's fan-out: when a Signal lands, the
 * framework reads this list directly and fires any `AgentParticipant` whose
 * `TriggerFilter` predicate matches. Participants serialize through the
 * `Participant` poly — agents are persistent by value (modelId, toolNames,
 * instructions, …), with live `Provider` and `Tool` instances resolved at
 * call time via `Sigil.providerFor` and `ToolFinder.byName`.
 *
 * `currentMode` is the conversation's active operating mode, kept up to
 * date by the framework as [[sigil.event.ModeChange]] events land. All
 * agents acting on this conversation read this field for their next
 * provider request — mode is conversation-level state, not agent-level.
 *
 * `topics` is the navigation stack of [[TopicEntry]]s for this
 * conversation. The LAST entry is the active thread (`currentTopic`); the
 * preceding entries are subjects the conversation has been on before and
 * could return to. The framework maintains this stack via
 * [[sigil.event.TopicChange]] events:
 *
 *   - `Switch` to a new label → push a new entry
 *   - `Switch` to a label already on the stack → truncate the stack back
 *     to that entry (the natural "return to prior subject" flow)
 *   - `Rename` → mutate the active entry in place
 *
 * Each `TopicEntry` carries the Topic's id plus a denormalized `label` +
 * `summary` so the system prompt and UI sidebar can render the stack
 * without a join. Rename events update both the Topic record and the
 * matching stack entries to keep these in sync.
 *
 * `Sigil.newConversation` bootstraps an initial entry so `topics.last`
 * always resolves.
 *
 * `cost` is a running total in USD across every settled [[Message]]
 * whose [[sigil.event.Message.modelId]] resolves to a known
 * [[sigil.db.Model]] in the [[sigil.cache.ModelRegistry]]. Maintained
 * by the framework's projection step on `Sigil.publish`; callers
 * never set it directly. Messages with `modelId = None` (user input,
 * tool results) and Messages whose model is unknown to the registry
 * contribute zero. Each increment fires a
 * [[sigil.signal.ConversationCostUpdated]] Notice carrying the new
 * total plus the per-Message delta.
 *
 * `parentConversationId`, when set, points at the conversation that
 * spawned this one — typically the user-facing conversation that
 * delegated work into a worker scratchpad conversation. The framework
 * uses this for sub-conversation cost rollup ("total cost for this
 * top-level conversation" sums own `cost` plus every transitively
 * descendant child's `cost`) and for UI drill-down (clicking into a
 * worker's history navigates from the user-facing conv to its
 * children). `None` for top-level conversations.
 *
 * `archived = true` soft-hides the conversation from default
 * listings without deleting it. The framework sets this when a
 * worker conversation's owning workflow run settles (the worker's
 * scratchpad is no longer "live" but the audit trail stays
 * queryable). Apps' default conversation queries should filter
 * `archived === false`; explicit drill-down still resolves
 * archived conversations.
 *
 * `RecordDocument` brings `created` / `modified` timestamps — useful for
 * "last activity" sorting in UIs.
 */
case class Conversation(topics: List[TopicEntry],
                        participants: List[Participant] = Nil,
                        currentMode: Mode = ConversationMode,
                        space: SpaceId = GlobalSpace,
                        currentKeywords: Vector[String] = Vector.empty,
                        clearedAt: Option[Timestamp] = None,
                        cost: BigDecimal = BigDecimal(0),
                        parentConversationId: Option[Id[Conversation]] = None,
                        stagingFor: Option[Id[Conversation]] = None,
                        archived: Boolean = false,
                        /** Conversation-level pinned model — when set, every LLM
                          * dispatch in the conversation (agent turns AND framework
                          * auxiliary calls — classifier, memory extractor, curate
                          * compression) routes to this model, overriding mode-
                          * driven strategy selection and space-level strategy
                          * assignment. Cleared via the `unpin_model` tool. */
                        pinnedModelId: Option[lightdb.id.Id[sigil.db.Model]] = None,
                        /** Conversation-level pinned complexity tier — when set,
                          * every per-turn classification skips
                          * [[sigil.provider.RoutedStrategy.inferComplexity]] and
                          * uses this tier instead. Lets the user lock the
                          * routing chain to a specific tier without naming a
                          * model (cost ceiling, classifier override, diagnostic
                          * forcing). Cleared via the `unpin_complexity` tool.
                          * Pin wins over inference; inference wins over the
                          * strategy's `Complexity.Medium` default. Bug #152. */
                        pinnedComplexity: Option[sigil.provider.Complexity] = None,
                        created: Timestamp = Timestamp(),
                        modified: Timestamp = Timestamp(),
                        _id: Id[Conversation] = Conversation.id())
  extends RecordDocument[Conversation] {

  /**
   * Convenience alias for `_id`.
   */
  def id: Id[Conversation] = _id

  /**
   * The active topic — last entry on the stack. Throws if the stack is
   * empty (which violates the invariant that `newConversation` upholds).
   */
  def currentTopic: TopicEntry = topics.lastOption.getOrElse {
    throw new IllegalStateException(
      s"Conversation $_id has no topics — newConversation must be used to bootstrap one."
    )
  }

  /**
   * Convenience alias for the active topic's id. Most call sites that
   * used to read `currentTopicId` migrate to this.
   */
  def currentTopicId: Id[Topic] = currentTopic.id

  /**
   * Entries earlier than the active topic — i.e. priors the conversation
   * could return to. Empty when this conversation only has its bootstrap
   * topic.
   */
  def previousTopics: List[TopicEntry] = topics.dropRight(1)
}

object Conversation extends RecordDocumentModel[Conversation] with JsonConversion[Conversation] {
  implicit override def rw: RW[Conversation] = RW.gen

  override def id(value: String = Unique()): Id[Conversation] = Id(value)

  /** Index on `stagingFor` so the orphan-staging maintenance sweep
    * can scan staging conversations cheaply, and apps can list
    * "imports in progress for conversation X." */
  val stagingFor: I[Option[Id[Conversation]]] = field.index(_.stagingFor)

  /** Index on `created` so the orphan-staging sweep can age-out
    * abandoned imports without a full table scan. */
  val createdAt: I[Long] = field.index("createdAt", _.created.value)
}
