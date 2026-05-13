package sigil.provider.openai

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.{Event, MessageVisibility}
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions
}
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import spec.{TestAgent, TestSigil, TestUser}
import spice.net.url

/**
 * Reproduces sigil bug #167 (round 3 — interleaved-tool-result drop).
 *
 * widge-server's failing turn shape:
 *   - Prior turn's request: 1 user message
 *   - Prior turn's response output_items:
 *       function_call(vector_lookup), web_search_call, message-text
 *   - Framework added ProviderMessages in this order:
 *       Assistant(tool_calls=[vector_lookup]) , ToolResult, Assistant(text)
 *   - Round-2 fix: `outputItemCount` skips web_search_call → equals 2
 *       (function_call + message).
 *   - `priorMessageCount = sentMessageCount(1) + outputItemCount(2) = 3`
 *
 * Next turn after the user sends a follow-up: 5 ProviderMessages:
 *   [0] User (prior input)
 *   [1] Assistant w/ tool_calls (the vector_lookup function_call)
 *   [2] ToolResult (the vector_lookup output — NEW info OpenAI doesn't have)
 *   [3] Assistant text (the model's reply — OpenAI emitted, already in
 *       its server-side state)
 *   [4] User (new follow-up)
 *
 * Current `messages.drop(3)` keeps only `[Assistant text, User]` —
 * **the ToolResult at position 2 gets dropped**. OpenAI's
 * `previous_response_id` state has the function_call but receives no
 * matching `function_call_output`, so the API 400s with "No tool
 * output found for function call <id>".
 *
 * The correct semantics: when `previous_response_id` is set, the input
 * array should only contain entries OpenAI doesn't already know:
 * `User` messages + `ToolResult`s. Assistant messages and Reasoning
 * items are server-side already.
 */
class OpenAIInterleavedToolResultDropSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = OpenAIProvider("sk-test-placeholder", TestSigil, url"https://api.openai.com")
  private val modelId: Id[Model] = Model.id("openai", "gpt-5")
  private val vectorLookupCallId = "call_vlu_abc123"

  "OpenAI Responses input rendering with prev_id (Bug #167 r3)" should {

    "include function_call_output for an interleaved ToolResult even when later Assistant text exists" in {
      val convId = Conversation.id(s"interleaved-${rapid.Unique()}")
      val topic  = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
      val priorId    = "resp_prior_widge"
      val priorCount = 3 // sentMessageCount(1) + outputItemCount(2: function_call + message)

      // Frames in chronological order — mirrors what widge-server's
      // conversation has after the model's mixed-output response and
      // a fresh user follow-up.
      val frames = Vector[ContextFrame](
        ContextFrame.Text(
          content = "Find info on RD2500",
          participantId = TestUser,
          sourceEventId = Id[Event]("user-1"),
          visibility = MessageVisibility.All
        ),
        ContextFrame.ToolCall(
          toolName = ToolName("vector_lookup"),
          argsJson = """{"query":"RD2500"}""",
          callId = Id[Event](vectorLookupCallId),
          participantId = TestAgent,
          sourceEventId = Id[Event]("invoke-1"),
          visibility = MessageVisibility.All
        ),
        ContextFrame.ToolResult(
          callId = Id[Event](vectorLookupCallId),
          content = """{"results":[{"text":"RD2500 spec sheet excerpt"}]}""",
          sourceEventId = Id[Event]("result-1"),
          visibility = MessageVisibility.All
        ),
        ContextFrame.Text(
          content = "Found the RD2500 specs.",
          participantId = TestAgent,
          sourceEventId = Id[Event]("agent-text-1"),
          visibility = MessageVisibility.All
        ),
        ContextFrame.Text(
          content = "What about RD5000?",
          participantId = TestUser,
          sourceEventId = Id[Event]("user-2"),
          visibility = MessageVisibility.All
        )
      )

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
               _id = convId, topics = List(topic), participants = Nil
             ))))
        _ <- TestSigil.updateProjection(convId, TestAgent)(_.copy(
               latestProviderResponseId           = Some(priorId),
               latestProviderResponseMessageCount = Some(priorCount)
             ))
        body <- {
          val req = ConversationRequest(
            conversationId     = convId,
            modelId            = modelId,
            instructions       = Instructions(),
            turnInput          = TurnInput(conversationId = convId, frames = frames),
            currentMode        = ConversationMode,
            currentTopic       = topic,
            generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
            tools              = CoreTools.all,
            chain              = List(TestUser, TestAgent)
          )
          provider.requestConverter(req).map(_.content match {
            case Some(c: spice.http.content.StringContent) => c.value
            case _                                         => ""
          })
        }
      } yield {
        // prev_id is carried — confirms we're on the chained path.
        body should include(priorId)
        // The function_call_output for vector_lookup MUST be present.
        // Without the bug fix the ToolResult gets dropped along with
        // the prior user message + assistant turn, and OpenAI 400s.
        body should include("function_call_output")
        body should include(vectorLookupCallId)
        // The new user follow-up must survive too.
        body should include("RD5000")
        // The prior user msg + prior assistant text are server-side
        // via prev_id and must NOT be re-shipped on this request.
        // (The ToolResult's `output` field legitimately contains the
        // RD2500 spec excerpt — that's the function_call_output the
        // wire SHOULD carry. The exclusions below cover only the
        // OpenAI-emitted artifacts the role filter drops.)
        body shouldNot include("Found the RD2500")
        body shouldNot include("Find info on RD2500")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
