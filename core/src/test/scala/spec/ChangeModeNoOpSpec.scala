package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, ModeChange, ToolOutcome}
import sigil.signal.ToolDelta
import sigil.tool.core.ChangeModeTool
import sigil.tool.model.ChangeModeInput

/**
 * Regression for sigil bug #286 — `change_mode` must refuse same-mode
 * re-entry with a didactic [[ToolResult.Failure]] instead of emitting
 * a no-op [[ModeChange]] event. Field evidence (Sage 2026-05-26)
 * showed an agent re-calling `change_mode("coding")` 5× while
 * already in coding under the (incorrect) belief that re-entering
 * would unblock a stuck loop. Each no-op cost an iteration AND
 * polluted the durable event log.
 */
class ChangeModeNoOpSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def turnContextFor(currentMode: sigil.provider.Mode): Task[TurnContext] = {
    val convId = Conversation.id(s"change-mode-noop-${rapid.Unique()}")
    val topic  = TopicEntry(
      id      = sigil.conversation.Topic.id(s"topic-$convId"),
      label   = "test",
      summary = "test"
    )
    val conv = Conversation(_id = convId, topics = List(topic), currentMode = currentMode)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map { stored =>
      TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser, TestAgent),
        conversation = stored,
        turnInput    = TurnInput(conversationId = stored._id),
        model        = TestSigil.defaultTestModel
      )
    }
  }

  private def runChange(target: String, ctx: TurnContext): Task[List[sigil.signal.Signal]] =
    ChangeModeTool.execute(ChangeModeInput(mode = target), ctx, Event.id()).toList

  "Sigil bug #286 — change_mode same-mode no-op refusal" should {

    "refuse same-mode re-entry with a didactic Failure naming the current mode" in {
      for {
        ctx     <- turnContextFor(TestCodingMode)
        signals <- runChange("coding", ctx)
      } yield {
        signals.collect { case mc: ModeChange => mc } shouldBe empty
        val failure = signals.collectFirst {
          case d: ToolDelta => d.outcome
        }.flatten.collect {
          case f: ToolOutcome.Failure => f
        }.getOrElse(fail("expected a Failure outcome on the settling ToolDelta"))
        failure.reason should include("already in")
        failure.reason should include("coding")
      }
    }

    "refuse an unknown mode with a Failure listing the available modes" in {
      for {
        ctx     <- turnContextFor(sigil.provider.ConversationMode)
        signals <- runChange("ferocity", ctx)
      } yield {
        signals.collect { case mc: ModeChange => mc } shouldBe empty
        val failure = signals.collectFirst {
          case d: ToolDelta => d.outcome
        }.flatten.collect {
          case f: ToolOutcome.Failure => f
        }.getOrElse(fail("expected a Failure outcome on the settling ToolDelta"))
        failure.reason should include("ferocity")
        failure.reason.toLowerCase should include("no mode named")
        // The hint enumerates the actually-registered modes so the
        // agent can pick a real one on its next iteration.
        val combined = failure.reason + "  " + signals.collectFirst {
          case d: ToolDelta => d.summary
        }.flatten.getOrElse("")
        combined should include("coding")
        combined should include("conversation")
      }
    }

    "accept a genuine mode change (current ≠ target) and emit ModeChange" in {
      for {
        ctx     <- turnContextFor(sigil.provider.ConversationMode)
        signals <- runChange("coding", ctx)
      } yield {
        val changes = signals.collect { case mc: ModeChange => mc }
        changes should have size 1
        changes.head.mode shouldBe TestCodingMode
        val success = signals.collectFirst {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        success should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
