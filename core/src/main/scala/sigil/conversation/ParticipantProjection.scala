package sigil.conversation

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.tool.ToolName

/**
 * One entry in [[ParticipantProjection.discoveredCapabilities]] —
 * the tool matches a `find_capability` query returned, plus the
 * first / most-recent time the query was issued.
 */
case class DiscoveredCapability(matches: List[ToolName],
                                firstSeen: Timestamp,
                                lastSeen: Timestamp)
  derives RW

/**
 * Per-(participant, conversation) projection of materialized state:
 *
 *   - `activeSkills` — updated on `ModeChange` (Mode-source slot) and
 *     app-specific skill activation events
 *   - `lastDiscoverySkillByMode` — when the agent leaves a mode, the
 *     [[SkillSource.Discovery]] slot active at that moment is archived
 *     under the OUTGOING mode's id; on a later return to that mode,
 *     the slot is restored. Lets agents "remember" the skill they had
 *     loaded for a mode without re-discovering it.
 *   - `recentTools` — pushed onto the head when a `ToolInvoke` from this
 *     participant completes
 *   - `suggestedTools` — replaced when a `ToolResults` from this participant
 *     carries fresh `find_capability` matches
 *   - `extraContext` — app-driven (populated via curator or tool behavior)
 *
 * Persisted as its own [[RecordDocument]] (id derived from
 * `(participantId, conversationId)`) — bug #26 lifted projections out
 * of the now-deleted `ConversationView` so the rolling-window cache
 * has a single concern.
 *
 * Re-derivable from the event log: every settled `Sigil.publish` updates
 * this projection alongside the event itself, but a full rebuild is
 * available via [[Sigil.rebuildProjection]].
 */
case class ParticipantProjection(participantId: ParticipantId,
                                 conversationId: Id[Conversation],
                                 activeSkills: Map[SkillSource, ActiveSkillSlot] = Map.empty,
                                 lastDiscoverySkillByMode: Map[Id[Mode], ActiveSkillSlot] = Map.empty,
                                 discoverySkillMode: Option[Id[Mode]] = None,
                                 recentTools: List[ToolName] = Nil,
                                 suggestedTools: List[ToolName] = Nil,
                                 /**
                                  * Accumulated `find_capability` results across the
                                  * conversation, keyed by normalised query keywords.
                                  * Surfaces in the system prompt so the agent doesn't
                                  * re-discover tools it's already seen. Aged out after
                                  * [[sigil.Sigil.discoveredCapabilityTtl]] turns.
                                  */
                                 discoveredCapabilities: Map[String, DiscoveredCapability] = Map.empty,
                                 /**
                                  * Per-conversation cache of the most recent provider-
                                  * side response id. Today only OpenAI's Responses API
                                  * uses it — `previous_response_id` chains the prior
                                  * turn's server-side state so the next request can
                                  * ship only the delta (new user input + tool outputs)
                                  * rather than the full transcript. `latestProviderResponseMessageCount`
                                  * is the rendered-message count that produced the
                                  * cached id; on the next turn the provider drops that
                                  * many messages from the head before sending. Both
                                  * fields are cleared together on `previous_response_not_found`
                                  * (the id expired upstream).
                                  */
                                 latestProviderResponseId: Option[String] = None,
                                 latestProviderResponseMessageCount: Option[Int] = None,
                                 extraContext: Map[ContextKey, String] = Map.empty,
                                 created: Timestamp = Timestamp(),
                                 modified: Timestamp = Timestamp(),
                                 _id: Id[ParticipantProjection] = ParticipantProjection.id())
  extends RecordDocument[ParticipantProjection]

object ParticipantProjection extends RecordDocumentModel[ParticipantProjection] with JsonConversion[ParticipantProjection] {
  implicit override def rw: RW[ParticipantProjection] = RW.gen

  /**
   * Compose a deterministic id from `(participantId, conversationId)`
   * so lookups are O(1) and the same key always resolves to the same
   * record.
   */
  def idFor(participantId: ParticipantId, conversationId: Id[Conversation]): Id[ParticipantProjection] =
    Id(s"${conversationId.value}:${participantId.value}")

  val participantId: I[ParticipantId] = field.index(_.participantId)
  val conversationId: I[Id[Conversation]] = field.index(_.conversationId)

  override def id(value: String = rapid.Unique()): Id[ParticipantProjection] = Id(value)

  /**
   * Construct an empty projection for a (participantId, conversationId)
   * pair using the deterministic id derived from the pair.
   */
  def empty(participantId: ParticipantId, conversationId: Id[Conversation]): ParticipantProjection =
    ParticipantProjection(
      participantId = participantId,
      conversationId = conversationId,
      _id = idFor(participantId, conversationId)
    )
}
