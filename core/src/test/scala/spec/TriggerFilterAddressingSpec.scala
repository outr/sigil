package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, Topic}
import sigil.dispatcher.TriggerFilter
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant, ParticipantId}
import lightdb.id.Id

/**
 * #328 — addressed-message (`@mention`) triggering. A Message with a
 * non-empty `addressees` set wakes ONLY the named participants; an
 * unaddressed Message keeps broadcast semantics. Lets an agent direct a
 * question at one co-participant (and a co-resident that's never
 * addressed stays passive — woken zero times) without changing existing
 * single-/multi-agent behavior.
 */
class TriggerFilterAddressingSpec extends AnyWordSpec with Matchers {
  private case object AgentA extends AgentParticipantId { override val value = "agent-a" }
  private case object AgentB extends AgentParticipantId { override val value = "agent-b" }
  private case object AgentC extends AgentParticipantId { override val value = "agent-c" }

  private def agent(id: AgentParticipantId) = DefaultAgentParticipant(id = id, modelId = Model.id("test", "model"))
  private val convId = Conversation.id("addressing-spec")
  private val topicId = Id[Topic]("addressing-topic")

  private def msgFrom(sender: ParticipantId, addressees: Option[Set[ParticipantId]]) =
    Message(
      participantId  = sender,
      conversationId = convId,
      topicId        = topicId,
      addressees     = addressees
    )

  "TriggerFilter addressing" should {
    "wake only the addressed participant, not other co-residents" in {
      val m = msgFrom(AgentA, Some(Set(AgentB)))
      TriggerFilter.isTriggerFor(agent(AgentB), m) shouldBe true
      TriggerFilter.isTriggerFor(agent(AgentC), m) shouldBe false
    }

    "wake every co-resident on an unaddressed (broadcast) message" in {
      val m = msgFrom(AgentA, None)
      TriggerFilter.isTriggerFor(agent(AgentB), m) shouldBe true
      TriggerFilter.isTriggerFor(agent(AgentC), m) shouldBe true
    }

    "treat an empty addressee set as broadcast" in {
      val m = msgFrom(AgentA, Some(Set.empty))
      TriggerFilter.isTriggerFor(agent(AgentB), m) shouldBe true
      TriggerFilter.isTriggerFor(agent(AgentC), m) shouldBe true
    }

    "never wake the sender on its own addressed message" in {
      val m = msgFrom(AgentA, Some(Set(AgentA, AgentB)))
      TriggerFilter.isTriggerFor(agent(AgentA), m) shouldBe false
      TriggerFilter.isTriggerFor(agent(AgentB), m) shouldBe true
    }

    "wake multiple addressed participants" in {
      val m = msgFrom(AgentA, Some(Set(AgentB, AgentC)))
      TriggerFilter.isTriggerFor(agent(AgentB), m) shouldBe true
      TriggerFilter.isTriggerFor(agent(AgentC), m) shouldBe true
    }
  }
}
