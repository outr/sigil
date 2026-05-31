package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, TopicEntry}
import sigil.db.Model
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant, WorkerParticipantId}

/**
 * Locks the classification that gates three worker-specific behaviors
 * (sigil #327/#330): addressing-driven silent rest, the worker turn
 * budget, and — the #330 fix — skipping the redundant/misfiring progress
 * reflection. A *directed worker sub-conversation* is one linked to a
 * parent that carries two or more agent participants (a supervisor + at
 * least one worker). Everything else (top-level user conversations,
 * single-agent sub-conversations, parent-less multi-agent rooms) is not.
 */
class DirectedWorkerConversationSpec extends AnyWordSpec with Matchers {
  private def agent(id: AgentParticipantId) = DefaultAgentParticipant(id = id, modelId = Model.id("test", "model"))
  private val worker = WorkerParticipantId("dwc-worker")
  private def topics = List(TopicEntry(TestTopicId, "t", "t"))

  "isDirectedWorkerConversation" should {
    "be true for a parent-linked conversation with a supervisor + worker" in {
      val conv = Conversation(
        topics = topics,
        participants = List(agent(TestAgent), agent(worker)),
        parentConversationId = Some(Conversation.id("parent")),
        _id = Conversation.id("dwc-1")
      )
      TestSigil.isDirectedWorkerConversation(conv) shouldBe true
    }

    "be false for a top-level conversation (no parent), even with two agents" in {
      val conv = Conversation(
        topics = topics,
        participants = List(agent(TestAgent), agent(worker)),
        _id = Conversation.id("dwc-2")
      )
      TestSigil.isDirectedWorkerConversation(conv) shouldBe false
    }

    "be false for a single-agent sub-conversation (parent but only one agent)" in {
      val conv = Conversation(
        topics = topics,
        participants = List(agent(worker)),
        parentConversationId = Some(Conversation.id("parent")),
        _id = Conversation.id("dwc-3")
      )
      TestSigil.isDirectedWorkerConversation(conv) shouldBe false
    }

    "be false for a parent-linked conversation with no agents" in {
      val conv = Conversation(
        topics = topics,
        participants = Nil,
        parentConversationId = Some(Conversation.id("parent")),
        _id = Conversation.id("dwc-4")
      )
      TestSigil.isDirectedWorkerConversation(conv) shouldBe false
    }
  }
}
