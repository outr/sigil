package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, ToolApproval, ToolOutcome}
import sigil.signal.ToolDelta
import sigil.tool.{TextToolOutput, ToolName}
import sigil.tool.core.RecordConsentTool
import sigil.tool.model.RecordConsentInput

/**
 * Coverage for sigil bug #84 — `record_consent` previously emitted
 * only a [[ToolApproval]] (a [[sigil.event.ControlPlaneEvent]] with
 * `role = MessageRole.Standard`), leaving the orchestrator's
 * `function_call` ↔ `function_call_output` invariant unsatisfied.
 * OpenAI Responses API rejected the next turn with "No tool output
 * found for function call X" and the agent loop terminated.
 *
 * Verifies:
 *   1. `RecordConsentTool.execute` emits BOTH a `ToolApproval` AND a
 *      `MessageRole.Tool` confirmation Message — so wire pairing
 *      stays intact regardless of whether `requiresUserConsent` is
 *      checked later.
 *   2. The confirmation Message's content references the recorded
 *      decision (approved / declined + reason).
 *   3. Approved + declined records both emit the pair.
 */
class RecordConsentPairingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Sigil bug #160 — `record_consent` validates `toolName` against
  // the live registry. Use a real, framework-shipped tool name so
  // the pair-emission assertions exercise the happy path rather
  // than the validation refusal path. `RespondTool` is always
  // registered by `CoreTools.all`.
  private val testToolName: String = sigil.tool.core.RespondTool.schema.name.value

  private def turnContextFor(): Task[TurnContext] = {
    val convId = Conversation.id(s"consent-pair-${rapid.Unique()}")
    val topic  = TopicEntry(
      id      = sigil.conversation.Topic.id(s"topic-$convId"),
      label   = "test",
      summary = "test"
    )
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map { stored =>
      TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser, TestAgent),
        conversation = stored,
        turnInput    = TurnInput(conversationId = stored._id)
      )
    }
  }

  "RecordConsentTool.execute (#84)" should {

    "emit a ToolApproval AND settle the ToolInvoke on approve" in {
      for {
        ctx    <- turnContextFor()
        events <- RecordConsentTool.execute(
                    RecordConsentInput(toolName = testToolName, approved = true,
                      reason = Some("user picked Claude state in setup options")), ctx, Event.id()).toList
      } yield {
        val approvals = events.collect { case t: ToolApproval => t }
        approvals should have size 1
        approvals.head.toolName shouldBe ToolName(testToolName)
        approvals.head.approved shouldBe true

        val settling = events.collect {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        settling should have size 1
        val text = settling.head.output.collect { case TextToolOutput(t) => t }.getOrElse("")
        text should include(testToolName)
        text should include("approved")
        text should include("user picked Claude state")
      }
    }

    "settle the ToolInvoke with the decline reason on decline" in {
      for {
        ctx    <- turnContextFor()
        events <- RecordConsentTool.execute(
                    RecordConsentInput(toolName = testToolName, approved = false,
                      reason = Some("user explicitly did not select")), ctx, Event.id()).toList
      } yield {
        events.collect { case t: ToolApproval => t } should have size 1
        val settling = events.collect {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        settling should have size 1
        val text = settling.head.output.collect { case TextToolOutput(t) => t }.getOrElse("")
        text should include("declined")
        text should include("user explicitly did not select")
      }
    }

    "settle the ToolInvoke even when reason is absent" in {
      for {
        ctx    <- turnContextFor()
        events <- RecordConsentTool.execute(
                    RecordConsentInput(toolName = testToolName, approved = true), ctx, Event.id()).toList
      } yield {
        val settling = events.collect {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        settling should have size 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
