package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, Message, MessageRole, ToolApproval}
import sigil.tool.ToolName
import sigil.tool.core.RecordConsentTool
import sigil.tool.model.{RecordConsentInput, ResponseContent}

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

    "emit a ToolApproval AND a Tool-role Message on approve" in {
      for {
        ctx    <- turnContextFor()
        events <- RecordConsentTool.execute(
                    RecordConsentInput(toolName = testToolName, approved = true,
                      reason = Some("user picked Claude state in setup options")), ctx).toList
      } yield {
        val approvals = events.collect { case t: ToolApproval => t }
        approvals should have size 1
        approvals.head.toolName shouldBe ToolName(testToolName)
        approvals.head.approved shouldBe true

        val toolMessages = events.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val text = toolMessages.head.content.collect { case ResponseContent.Text(t) => t }.mkString
        text should include(testToolName)
        text should include("approved")
        text should include("user picked Claude state")
      }
    }

    "emit a Tool-role Message on decline carrying the decline reason" in {
      for {
        ctx    <- turnContextFor()
        events <- RecordConsentTool.execute(
                    RecordConsentInput(toolName = testToolName, approved = false,
                      reason = Some("user explicitly did not select")), ctx).toList
      } yield {
        events.collect { case t: ToolApproval => t } should have size 1
        val toolMessages = events.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val text = toolMessages.head.content.collect { case ResponseContent.Text(t) => t }.mkString
        text should include("declined")
        text should include("user explicitly did not select")
      }
    }

    "emit a Tool-role Message even when reason is absent" in {
      for {
        ctx    <- turnContextFor()
        events <- RecordConsentTool.execute(
                    RecordConsentInput(toolName = testToolName, approved = true), ctx).toList
      } yield {
        val toolMessages = events.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
