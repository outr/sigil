package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, Topic, TurnInput}
import sigil.event.{Event, Message, Stop, ToolOutcome, TopicChange}
import sigil.information.Information
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.core.{RespondTool, CancelTool}
import sigil.tool.discovery.CapabilityType
import sigil.tool.util.LookupTool
import sigil.tool.model.{LookupInput, LookupOutput, RespondInput, CancelInput}

/**
 * Round-trip coverage for framework tools where direct `execute` semantics
 * are non-obvious:
 *   - [[RespondTool]] — every call carries `topicLabel` + `topicSummary`;
 *     the Message it emits is tagged with the conversation's
 *     `currentTopicId`. Topic-change resolution itself lives in
 *     [[sigil.orchestrator.Orchestrator]], not this tool — so the direct
 *     `execute` path here emits only the Message, not any `TopicChange`.
 *   - [[LookupTool]] — resolves an Information id via
 *     [[sigil.Sigil.getInformation]] (backed by
 *     [[sigil.information.InMemoryInformation]] in tests) and returns a
 *     Message carrying the resolved content.
 */
class CoreToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConversationId(suffix: String): Id[Conversation] =
    Conversation.id(s"core-tools-$suffix-${rapid.Unique()}")

  private def turnContextFor(convId: Id[Conversation]): TurnContext = {
    val viewConvId = convId
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = Conversation(topics = TestTopicStack, _id = convId),
      turnInput = TurnInput(conversationId = viewConvId),
      model = TestSigil.defaultTestModel
    )
  }

  "RespondTool" should {
    "emit a Message tagged with the conversation's currentTopicId" in {
      val convId = freshConversationId("respond-topic-tag")
      val input = RespondInput(
        topicLabel = "Refactoring Notes",
        topicSummary = "Notes on refactoring strategies.",
        content = "Hello!",
        endsTurn = true
      )
      val events = RespondTool
        .execute(input, turnContextFor(convId), Event.id())
        .toList
      events.map { list =>
        val messages = list.collect { case m: Message => m }
        messages should have size 1
        val m = messages.head
        m.conversationId shouldBe convId
        m.topicId shouldBe TestTopicId
        m.participantId shouldBe TestAgent
      }
    }
  }

  "CancelTool" should {
    "emit an Active Stop event with the submitted target + force + reason (orchestrator settles via StateDelta)" in {
      val convId = freshConversationId("stop-targeted")
      val events = CancelTool
        .execute(
          CancelInput(targetParticipantId = Some(TestAgent), force = true, reason = Some("too risky")),
          turnContextFor(convId),
          Event.id()
        )
        .toList
      events.map { list =>
        val stops = list.collect { case s: Stop => s }
        stops should have size 1
        val stop = stops.head
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
      val events = CancelTool
        .execute(CancelInput(), turnContextFor(convId), Event.id())
        .toList
      events.map { list =>
        val stops = list.collect { case s: Stop => s }
        stops should have size 1
        val stop = stops.head
        stop.targetParticipantId shouldBe None
        stop.force shouldBe false
        stop.reason shouldBe None
      }
    }
  }

  "LookupTool" should {
    "emit a Message carrying the resolved information when the store has it" in {
      val convId = freshConversationId("lookup-hit")
      val infoId = Id[Information]("info-hit")
      val full = TestInformationWithBody(id = infoId, body = "HIT_BODY_42")
      // Register the concrete Information subtype so its poly RW is
      // available to the tool's JSON serialization path. Safe to call
      // repeatedly — PolyType registration is idempotent.
      Information.register(summon[RW[TestInformationWithBody]])
      TestSigil.information.put(full)

      val signals = LookupTool
        .execute(LookupInput(capabilityType = CapabilityType.Information, name = infoId.value), turnContextFor(convId), Event.id())
        .toList
      signals.map { list =>
        list should have size 1
        val out = list.head.asInstanceOf[ToolDelta].output
          .collect { case o: LookupOutput => o }
          .getOrElse(fail(s"expected a ToolDelta carrying LookupOutput; got: $list"))
        out match {
          case LookupOutput.Found(_, _, payload) =>
            fabric.io.JsonFormatter.Compact(payload) should include("HIT_BODY_42")
          case other => fail(s"expected Found, got $other")
        }
      }
    }

    "emit a NotFound when the id doesn't resolve" in {
      val convId = freshConversationId("lookup-miss")
      val missingId = Id[Information]("info-missing")
      val signals = LookupTool
        .execute(LookupInput(capabilityType = CapabilityType.Information, name = missingId.value), turnContextFor(convId), Event.id())
        .toList
      signals.map { list =>
        list should have size 1
        val out = list.head.asInstanceOf[ToolDelta].output
          .collect { case o: LookupOutput => o }
          .getOrElse(fail(s"expected a ToolDelta carrying LookupOutput; got: $list"))
        out match {
          case LookupOutput.NotFound(_, name) => name shouldBe missingId.value
          case other => fail(s"expected NotFound, got $other")
        }
      }
    }

    "decline retrieval for Tool capability type" in {
      val convId = freshConversationId("lookup-tool-not-supported")
      val signals = LookupTool
        .execute(LookupInput(capabilityType = CapabilityType.Tool, name = "respond"), turnContextFor(convId), Event.id())
        .toList
      signals.map { list =>
        list should have size 1
        val out = list.head.asInstanceOf[ToolDelta].output
          .collect { case o: LookupOutput => o }
          .getOrElse(fail(s"expected a ToolDelta carrying LookupOutput; got: $list"))
        out match {
          case LookupOutput.NotRetrievable(_, _, hint) =>
            hint should include("not retrievable")
          case other => fail(s"expected NotRetrievable, got $other")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/**
 * Synthetic Information subtype used only by CoreToolsSpec. Named
 * distinctly from `TestInformation` in other specs to avoid a collision
 * in the shared `spec` package.
 */
case class TestInformationWithBody(id: Id[Information], body: String) extends Information derives RW
