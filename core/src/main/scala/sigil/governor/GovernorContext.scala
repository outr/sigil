package sigil.governor

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.AgentState
import sigil.participant.AgentParticipant
import sigil.provider.ModelProfile

/** Everything a [[TurnGovernor]] reads at an agent-loop iteration
  * boundary. Assembled once per boundary by the loop and shared across
  * every governor in the fold, so the profile lookup and the two cadence
  * derivations happen once and are threaded from here into the work they
  * gate rather than being recomputed downstream.
  *
  * @param agent              the agent whose turn is running
  * @param conversation       the conversation, reloaded this iteration
  * @param claimed            the `Active` [[AgentState]] holding the claim
  * @param iteration          the iteration that just drained
  * @param nextIteration      `iteration + 1` — the cadence anchor, and the
  *                           iteration a checkpoint reports against
  * @param modelProfile       the running model's resolved profile
  * @param checkpointInterval progress-checkpoint cadence, already tightened
  *                           for `modelProfile`'s instruction tier (0 = off)
  * @param plannerCadence     planner-consult cadence, tightened the same way
  */
final case class GovernorContext(agent: AgentParticipant,
                                 conversation: Conversation,
                                 claimed: AgentState,
                                 iteration: Int,
                                 nextIteration: Int,
                                 modelProfile: ModelProfile,
                                 checkpointInterval: Int,
                                 plannerCadence: Int) {
  def conversationId: Id[Conversation] = conversation._id
}
