package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.{Event, Message}
import sigil.participant.{Participant, ParticipantId}
import sigil.tool.model.{RelayMessageInput, ResponseContent}
import sigil.tool.util.RelayMessageTool
import sigil.tool.{ToolContext, ToolName, ToolResult}

/**
 * #329 — cross-conversation relay. An agent that is a member of two
 * conversations posts a Message into the *other* one (the bridge for the
 * agent-bridge delegation model #327). Authorization is membership-
 * scoped; with #328's addressing the relay can be directed at a specific
 * participant of the target.
 */
class RelayMessageSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Non-agent member participant — relay (#329) is membership-scoped and
  // agent-agnostic, so we use plain members to keep the relayed Message
  // from auto-firing a (provider-less) agent turn in the test harness.
  // The agent-wake behavior is covered separately (#328 / #327).
  private case class Member(override val id: ParticipantId,
                            override val displayName: String = "member",
                            override val avatarUrl: Option[String] = None)
    extends Participant derives RW
  Participant.register(summon[RW[Member]])

  // The worker participant in the sub-conversation.
  private case object WorkerAgent extends sigil.participant.AgentParticipantId {
    override val value = "relay-worker"
  }
  ParticipantId.register(RW.static(WorkerAgent))

  private def toolCtx(conv: Conversation): ToolContext =
    ToolContext(
      TurnContext(
        sigil = TestSigil,
        chain = List(TestAgent), // caller = chain.last = TestAgent
        conversation = conv,
        turnInput = TurnInput(ConversationView(conversationId = conv._id)),
        model = TestSigil.defaultTestModel
      ),
      Event.id(),
      ToolName("relay_test")
    )

  private def parentConv(id: Id[Conversation]) = Conversation(
    topics = List(TopicEntry(TestTopicId, "parent", "parent")),
    participants = List(Member(TestAgent)),
    _id = id
  )

  private def workerConv(id: Id[Conversation], parent: Id[Conversation]) = Conversation(
    topics = List(TopicEntry(TestTopicId, "worker", "worker")),
    participants = List(Member(TestAgent), Member(WorkerAgent)),
    parentConversationId = Some(parent),
    _id = id
  )

  private def messagesIn(convId: Id[Conversation]) =
    TestSigil.withDB(_.eventsTransaction(convId)(_.list))
      .map(_.collect { case m: Message if m.conversationId == convId => m })

  "relay_message" should {
    "post a directed message into another conversation the agent belongs to" in {
      val pId = Conversation.id(s"relay-parent-${rapid.Unique()}")
      val wId = Conversation.id(s"relay-worker-${rapid.Unique()}")
      val p = parentConv(pId)
      val w = workerConv(wId, pId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(p)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(w)))
        // Agent is anchored in the parent P, relays a brief down into W.
        _ <- RelayMessageTool.invoke(
          RelayMessageInput(conversationId = wId, content = "do the work", addressees = Some(List(WorkerAgent.value))),
          toolCtx(p)
        )
        msgs <- messagesIn(wId)
      } yield {
        val relayed = msgs.find(_.content.collect { case ResponseContent.Text(t) => t }.mkString.contains("do the work"))
        relayed should not be empty
        relayed.get.participantId shouldBe TestAgent
        relayed.get.addressees shouldBe Some(Set[sigil.participant.ParticipantId](WorkerAgent))
        relayed.get.source.exists(_.startsWith("relay:")) shouldBe true
      }
    }

    "reject a relay into a conversation the agent is not a member of" in {
      val foreignId = Conversation.id(s"relay-foreign-${rapid.Unique()}")
      val foreign = Conversation(
        topics = List(TopicEntry(TestTopicId, "foreign", "foreign")),
        participants = List(Member(WorkerAgent)), // TestAgent is NOT a member
        _id = foreignId
      )
      // Anchor the caller in a conversation it does belong to.
      val homeId = Conversation.id(s"relay-home-${rapid.Unique()}")
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(foreign)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(parentConv(homeId))))
        res <- RelayMessageTool.invoke(
          RelayMessageInput(conversationId = foreignId, content = "intrude"),
          toolCtx(parentConv(homeId))
        ).map(_ => true).handleError(_ => rapid.Task.pure(false))
        msgs <- messagesIn(foreignId)
      } yield {
        res shouldBe false
        msgs.exists(_.content.collect { case ResponseContent.Text(t) => t }.mkString.contains("intrude")) shouldBe false
      }
    }

    "reject a relay addressed to a non-participant of the target" in {
      val pId = Conversation.id(s"relay-bad-addr-parent-${rapid.Unique()}")
      val wId = Conversation.id(s"relay-bad-addr-worker-${rapid.Unique()}")
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(parentConv(pId))))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(workerConv(wId, pId))))
        res <- RelayMessageTool.invoke(
          RelayMessageInput(conversationId = wId, content = "hi", addressees = Some(List("nobody-here"))),
          toolCtx(parentConv(pId))
        ).map(_ => true).handleError(_ => rapid.Task.pure(false))
      } yield res shouldBe false
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
