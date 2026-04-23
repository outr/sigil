package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, Topic, TurnInput}
import sigil.event.{Message, Stop, TopicChange}
import sigil.information.Information
import sigil.signal.EventState
import sigil.tool.core.{RespondTool, StopTool}
import sigil.tool.util.LookupInformationTool
import sigil.tool.model.{LookupInformationInput, RespondInput, StopInput}

/**
 * Round-trip coverage for the new framework tools that close framework
 * gaps exposed by the audit:
 *   - [[RespondTool]] — every call carries a required `topic`; the Message
 *     it emits is tagged with the conversation's `currentTopicId`.
 *     Topic-change resolution itself lives in
 *     [[sigil.orchestrator.Orchestrator]], not this tool — so the direct
 *     `execute` path here emits only the Message, not any `TopicChange`.
 *   - [[LookupInformationTool]] — resolves an Information id via
 *     [[sigil.Sigil.getInformation]] (backed by
 *     [[sigil.information.InMemoryInformation]] in tests) and returns a
 *     Message carrying the resolved content.
 */
class CoreToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConversationId(suffix: String): Id[Conversation] =
    Conversation.id(s"core-tools-$suffix-${rapid.Unique()}")

  private def turnContextFor(convId: Id[Conversation]): TurnContext = {
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = Conversation(topics = TestTopicStack, _id = convId),
      conversationView = view,
      turnInput = TurnInput(view)
    )
  }

  "RespondTool" should {
    "emit a Message tagged with the conversation's currentTopicId" in {
      val convId = freshConversationId("respond-topic-tag")
      val input = RespondInput(
        topicLabel = "Refactoring Notes",
        topicSummary = "Notes on refactoring strategies.",
        content = "▶Text\nHello!"
      )
      val events = RespondTool
        .execute(input, turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val m = list.head.asInstanceOf[Message]
        m.conversationId shouldBe convId
        m.topicId shouldBe TestTopicId
        m.participantId shouldBe TestAgent
      }
    }
  }

  "StopTool" should {
    "emit an Active Stop event with the submitted target + force + reason (orchestrator settles via StateDelta)" in {
      val convId = freshConversationId("stop-targeted")
      val events = StopTool
        .execute(
          StopInput(targetParticipantId = Some(TestAgent), force = true, reason = Some("too risky")),
          turnContextFor(convId)
        )
        .toList
      events.map { list =>
        list should have size 1
        val stop = list.head.asInstanceOf[Stop]
        stop.targetParticipantId shouldBe Some(TestAgent)
        stop.force shouldBe true
        stop.reason shouldBe Some("too risky")
        stop.conversationId shouldBe convId
        stop.topicId shouldBe TestTopicId
        stop.participantId shouldBe TestAgent
        stop.state shouldBe EventState.Active
      }
    }

    "default to graceful (force=false) and broadcast (target=None) when inputs omitted" in {
      val convId = freshConversationId("stop-graceful-all")
      val events = StopTool
        .execute(StopInput(), turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val stop = list.head.asInstanceOf[Stop]
        stop.targetParticipantId shouldBe None
        stop.force shouldBe false
        stop.reason shouldBe None
      }
    }
  }

  "LookupInformationTool" should {
    "emit a Message carrying the resolved information when the store has it" in {
      val convId = freshConversationId("lookup-hit")
      val infoId = Id[Information]("info-hit")
      val full = TestInformationWithBody(id = infoId, body = "HIT_BODY_42")
      // Register the concrete Information subtype so its poly RW is
      // available to the tool's JSON serialization path. Safe to call
      // repeatedly — PolyType registration is idempotent.
      Information.register(summon[RW[TestInformationWithBody]])
      TestSigil.information.put(full)

      val events = LookupInformationTool
        .execute(LookupInformationInput(id = infoId), turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val msg = list.head.asInstanceOf[Message]
        val text = msg.content.collectFirst {
          case sigil.tool.model.ResponseContent.Text(t) => t
        }.getOrElse("")
        text should include("HIT_BODY_42")
      }
    }

    "emit a not-found Message when the id doesn't resolve" in {
      val convId = freshConversationId("lookup-miss")
      val missingId = Id[Information]("info-missing")
      val events = LookupInformationTool
        .execute(LookupInformationInput(id = missingId), turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val msg = list.head.asInstanceOf[Message]
        val text = msg.content.collectFirst {
          case sigil.tool.model.ResponseContent.Text(t) => t
        }.getOrElse("")
        text should include("No Information found")
        text should include(missingId.value)
      }
    }
  }
}

/**
 * Synthetic Information subtype used only by CoreToolsSpec. Named
 * distinctly from `TestInformation` in other specs to avoid a collision
 * in the shared `spec` package.
 */
case class TestInformationWithBody(id: Id[Information], body: String) extends Information derives RW
