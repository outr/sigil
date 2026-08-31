package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, TopicEntry}
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{DefaultAgentParticipant, ParticipantId, WorkerParticipantId}
import sigil.pipeline.WorkerConversationAddressingTransform
import sigil.tool.model.ResponseContent

/**
 * #327 — the agent-bridge becomes addressing-driven so it terminates.
 * In a directed worker conversation (linked to a parent, two+ agent
 * participants) an agent's outbound Standard Message that carries no
 * explicit addressees is rewritten to address the OTHER agent
 * participant(s). Combined with #328's wake-only-when-addressed filter,
 * this is what lets the supervisor end the task by relaying up and
 * simply not re-addressing the worker.
 */
class WorkerConversationAddressingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val workerId = WorkerParticipantId("addr-worker")
  private def agent(id: sigil.participant.AgentParticipantId) =
    DefaultAgentParticipant(id = id, modelId = Model.id("test", "model"))

  private def msg(conv: Id[Conversation], from: ParticipantId, addressees: Option[Set[ParticipantId]] = None) =
    Message(
      participantId = from,
      conversationId = conv,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text("reply")),
      role = MessageRole.Standard,
      addressees = addressees
    )

  private def persistConv(conv: Conversation) =
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))

  "WorkerConversationAddressingTransform" should {
    "address an agent's reply to the co-participant in a directed worker conversation" in {
      val parentId = Conversation.id(s"addr-parent-${rapid.Unique()}")
      val wId = Conversation.id(s"addr-worker-${rapid.Unique()}")
      val w = Conversation(
        topics = List(TopicEntry(TestTopicId, "w", "w")),
        participants = List(agent(TestAgent), agent(workerId)),
        parentConversationId = Some(parentId),
        _id = wId
      )
      for {
        _ <- persistConv(w)
        out <- WorkerConversationAddressingTransform.apply(msg(wId, TestAgent), TestSigil)
      } yield out match {
        case m: Message => m.addressees shouldBe Some(Set[ParticipantId](workerId))
        case other => fail(s"expected a Message, got $other")
      }
    }

    "leave messages in a non-directed (no parent) conversation untouched" in {
      val cId = Conversation.id(s"addr-plain-${rapid.Unique()}")
      val c = Conversation(
        topics = List(TopicEntry(TestTopicId, "c", "c")),
        participants = List(agent(TestAgent), agent(workerId)),
        _id = cId // no parentConversationId
      )
      for {
        _ <- persistConv(c)
        out <- WorkerConversationAddressingTransform.apply(msg(cId, TestAgent), TestSigil)
      } yield out.asInstanceOf[Message].addressees shouldBe None
    }

    "honor an explicit addressee set (does not overwrite)" in {
      val parentId = Conversation.id(s"addr-explicit-parent-${rapid.Unique()}")
      val wId = Conversation.id(s"addr-explicit-${rapid.Unique()}")
      val w = Conversation(
        topics = List(TopicEntry(TestTopicId, "w", "w")),
        participants = List(agent(TestAgent), agent(workerId)),
        parentConversationId = Some(parentId),
        _id = wId
      )
      for {
        _ <- persistConv(w)
        out <- WorkerConversationAddressingTransform.apply(
          msg(wId, TestAgent, addressees = Some(Set[ParticipantId](TestUser))),
          TestSigil)
      } yield out.asInstanceOf[Message].addressees shouldBe Some(Set[ParticipantId](TestUser))
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
