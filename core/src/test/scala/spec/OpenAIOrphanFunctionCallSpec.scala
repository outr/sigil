package spec

import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, Conversation, Topic, ToolCallState, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.openai.OpenAIProvider
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions}
import sigil.tool.ToolName
import sigil.tool.core.CoreTools

/**
 * Coverage for `OpenAIProvider.renderInput` tool-call rendering.
 *
 * Under the typed tool-execution model every tool call is paired
 * with its result event by construction (atomic dispatch builds a
 * `ToolResults`; streaming `respond` emits one too), so the
 * wire-side `ensureFunctionCallsPaired` safety net was removed —
 * `renderInput` no longer fabricates a placeholder
 * `function_call_output`. This spec pins that: a (synthetic)
 * dangling `function_call` renders WITHOUT a synthesized output,
 * and a properly-paired call renders exactly one.
 */
class OpenAIOrphanFunctionCallSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = OpenAIProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)
  private val convId   = Conversation.id("orphan-fc-spec")

  private def renderBody(frames: Vector[ContextFrame]): String = {
    val req = ConversationRequest(
      conversationId     = convId,
      model            = TestSigil.testModel(Model.id("openai", "gpt-5.4-nano")),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId, frames = frames),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools              = CoreTools.all,
      chain              = List(TestUser, TestAgent)
    )
    provider.requestConverter(req).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => fail("expected StringContent body from requestConverter")
    }
  }

  private def inputItems(body: String): List[fabric.Json] = {
    val json = JsonParser(body)
    json.get("input") match {
      case Some(arr: fabric.Arr) => arr.value.toList
      case _                     => fail(s"no `input` array in body: ${body.take(200)}")
    }
  }

  "OpenAIProvider.renderInput tool-call rendering" should {

    "render an unpaired function_call WITHOUT synthesizing a paired output" in {
      // A ToolCall frame with NO matching ToolResult should never
      // happen under the typed model — every call is paired by
      // construction. If one ever does, `renderInput` ships it as-is
      // and the orchestrator logs the framework bug; it does NOT
      // fabricate a placeholder `function_call_output` (the old
      // `ensureFunctionCallsPaired` safety net is removed).
      val orphanCallId = Id[Event]("orphan-call-7nTbS97zIPJe9PQo2sGTq6V7nX11hQsT")
      val sourceId     = Id[Event]("source-1")
      val frames: Vector[ContextFrame] = Vector(
        ContextFrame.Text(
          content       = "Help me",
          participantId = TestUser,
          sourceEventId = Id[Event]("user-msg-1")
        ),
        ContextFrame.ToolCall(
          toolName      = ToolName("record_consent"),
          argsJson      = """{"toolName":"load_claude_state","approved":true}""",
          callId        = orphanCallId,
          participantId = TestAgent,
          sourceEventId = sourceId
        )
        // NO ContextFrame.ToolResult for orphanCallId
      )

      val body  = renderBody(frames)
      val items = inputItems(body)

      val callIds = items.collect {
        case it if it.get("type").map(_.asString).contains("function_call") =>
          it.get("call_id").map(_.asString).getOrElse("")
      }
      val outputCallIds = items.collect {
        case it if it.get("type").map(_.asString).contains("function_call_output") =>
          it.get("call_id").map(_.asString).getOrElse("")
      }
      withClue(s"callIds=$callIds outputCallIds=$outputCallIds body=${body.take(500)}") {
        callIds should contain(orphanCallId.value)
        // No safety-net synthesis — the orphan stays unpaired.
        outputCallIds should not contain orphanCallId.value
      }
    }

    "leave properly-paired function_calls / function_call_outputs untouched (no double-output)" in {
      val callId   = Id[Event]("paired-call-1")
      val frames: Vector[ContextFrame] = Vector(
        ContextFrame.ToolCall(
          toolName      = ToolName("record_consent"),
          argsJson      = """{"toolName":"x","approved":true}""",
          callId        = callId,
          participantId = TestAgent,
          sourceEventId = callId,
          state         = ToolCallState.Complete("Consent recorded: x approved")
        )
      )
      val body  = renderBody(frames)
      val items = inputItems(body)

      val outputs = items.count(it => it.get("type").map(_.asString).contains("function_call_output")
        && it.get("call_id").map(_.asString).contains(callId.value))
      // Exactly ONE output for the call — safety net didn't add a duplicate.
      outputs shouldBe 1
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
