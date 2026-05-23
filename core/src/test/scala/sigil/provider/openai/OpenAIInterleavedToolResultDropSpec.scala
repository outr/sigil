package sigil.provider.openai

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, Conversation, ToolCallState, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.{Event, MessageVisibility}
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions}
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import spec.{TestAgent, TestSigil, TestUser}
import spice.net.url

/**
 * Coverage for the OpenAI Responses request shape when a turn carries
 * tool outputs alongside a cached `previous_response_id`.
 *
 * Trimming the transcript against `previous_response_id` would ship a
 * bare `function_call_output` and rely on the prev-id chain to resolve
 * its `function_call` server-side. A stale or broken chain makes that
 * resolution fail and OpenAI rejects the whole turn ("No tool call
 * found for function call output ...").
 *
 * The request renderer therefore takes the full-render path on any
 * tool-output turn: it ships the complete, well-paired transcript —
 * every `function_call` next to its `function_call_output` — and omits
 * `previous_response_id`. These specs verify both halves of each pair
 * reach the wire and that the request does not depend on the chain.
 */
class OpenAIInterleavedToolResultDropSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = OpenAIProvider("sk-test-placeholder", TestSigil, url"https://api.openai.com")
  private val modelId: Id[Model] = Model.id("openai", "gpt-5")
  private val vectorLookupCallId = "call_vlu_abc123"

  "OpenAI Responses request for a turn carrying a prior respond call" should {

    "ship the function_call paired with its function_call_output and omit previous_response_id" in {
      val convId = Conversation.id(s"respond-pair-${rapid.Unique()}")
      val topic  = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
      val priorId = "resp_prior_respond"
      val priorCount = 1
      val respondCallId = "call_resp_abc"

      val frames = Vector[ContextFrame](
        ContextFrame.Text(
          content = "hi",
          participantId = TestUser,
          sourceEventId = Id[Event]("user-1"),
          visibility = MessageVisibility.All
        ),
        ContextFrame.ToolCall(
          toolName = ToolName("respond"),
          argsJson = """{"content":"hi back","topicLabel":"Greeting","topicSummary":"Hi","disposition":"Success","endsTurn":true,"keywords":[]}""",
          callId = Id[Event](respondCallId),
          participantId = TestAgent,
          sourceEventId = Id[Event]("invoke-respond-1"),
          visibility = MessageVisibility.All,
          state = ToolCallState.Complete("""{"text":""}""")
        ),
        ContextFrame.Text(
          content = "hi back",
          participantId = TestAgent,
          sourceEventId = Id[Event]("agent-text-1"),
          visibility = MessageVisibility.All
        ),
        ContextFrame.Text(
          content = "follow-up",
          participantId = TestUser,
          sourceEventId = Id[Event]("user-2"),
          visibility = MessageVisibility.All
        )
      )

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
               _id = convId, topics = List(topic), participants = Nil
             ))))
        // Prev-id IS cached, but the turn carries tool outputs — the
        // renderer must take the full-render path regardless.
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
        // Tool-output turn — prev-id omitted, full transcript shipped.
        body shouldNot include(""""previous_response_id"""")
        body shouldNot include(priorId)
        // The respond call AND its function_call_output both reach the
        // wire — OpenAI sees a well-paired call, never an orphan output.
        body should include(""""type":"function_call"""")
        body should include(""""type":"function_call_output"""")
        body should include(respondCallId)
        body should include("follow-up")
      }
    }
  }

  "OpenAI Responses request for a turn with an interleaved tool result" should {

    "ship the function_call + function_call_output pair and the full transcript" in {
      val convId = Conversation.id(s"interleaved-${rapid.Unique()}")
      val topic  = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
      val priorId = "resp_prior_widge"
      val priorCount = 3

      // Chronological frames: a tool call, its result, the model's
      // reply, then a fresh user follow-up.
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
          visibility = MessageVisibility.All,
          state = ToolCallState.Complete("""{"results":[{"text":"RD2500 spec sheet excerpt"}]}""")
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
        // Tool-output turn — prev-id omitted; the call resolution can
        // never depend on a possibly-stale chain.
        body shouldNot include(""""previous_response_id"""")
        body shouldNot include(priorId)
        // The vector_lookup call + its output both reach the wire.
        body should include(""""type":"function_call"""")
        body should include(""""type":"function_call_output"""")
        body should include(vectorLookupCallId)
        // Full render — the complete transcript ships, including the
        // prior user message and the model's reply.
        body should include("Find info on RD2500")
        body should include("Found the RD2500")
        body should include("RD5000")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
