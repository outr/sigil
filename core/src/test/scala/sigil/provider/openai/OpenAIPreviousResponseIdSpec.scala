package sigil.provider.openai

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextFrame, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.{Event, MessageVisibility}
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  ProviderEvent, ToolCallAccumulator
}
import sigil.tool.ToolName
import sigil.tool.core.CoreTools
import spec.{TestAgent, TestSigil, TestUser}
import spice.net.url

/**
 * Coverage for the OpenAI Responses `previous_response_id` chain.
 *
 *   - The SSE parser captures `response.id` from `response.created`
 *     and emits a `ResponseStateCaptured` carrying it (plus the
 *     next-turn drop count) at `response.completed`.
 *   - The request body picks up a cached id from the agent's
 *     ParticipantProjection and trims the rendered messages by the
 *     stored count before sending.
 *   - A `previous_response_not_found` error emits a clear-cache
 *     marker so the next turn falls back to the full-transcript
 *     shape.
 */
class OpenAIPreviousResponseIdSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = OpenAIProvider("sk-test-placeholder", TestSigil, url"https://api.openai.com")
  private val modelId: Id[Model] = Model.id("openai", "gpt-5")

  private def runLines(lines: List[String]): Vector[ProviderEvent] = {
    val state = new provider.StreamState(new ToolCallAccumulator())
    state.sentMessageCount = 1
    lines.flatMap(line => provider.parseLine(line, state)).toVector
  }

  private def sse(events: List[String]): List[String] =
    events.flatMap(e => List(s"data: $e", ""))

  "OpenAI Responses SSE capture" should {

    "emit ResponseStateCaptured(Some(id), count) when response.completed settles" in Task {
      val events = runLines(sse(List(
        """{"type":"response.created","response":{"id":"resp_abc123","status":"in_progress"}}""",
        """{"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg1"}}""",
        """{"type":"response.output_text.delta","delta":"hello"}""",
        """{"type":"response.completed","response":{"status":"completed"}}"""
      )))
      val captured = events.collect { case rc: ProviderEvent.ResponseStateCaptured => rc }
      captured should have size 1
      captured.head.responseId shouldBe Some("resp_abc123")
      // messageCount captures `sentMessageCount` at call-entry (= 1).
      // Output items aren't added to it — the next turn's renderInput
      // role-filters the post-cutoff tail rather than skipping past
      // them positionally (sigil bug #167 r3).
      captured.head.messageCount shouldBe 1
      // Done is the terminator and the only one.
      events.last shouldBe a[ProviderEvent.Done]
    }

    "emit no ResponseStateCaptured when the stream settles without seeing response.created" in Task {
      val events = runLines(sse(List(
        """{"type":"response.completed","response":{"status":"completed"}}"""
      )))
      events.collect { case rc: ProviderEvent.ResponseStateCaptured => rc } shouldBe empty
    }

    "capture sentMessageCount regardless of output item mix (server tools, function_calls, messages)" in Task {
      // messageCount is now just sentMessageCount — the rendered count
      // at call entry. Output item types don't affect it. The next
      // turn's renderInput role-filters the post-cutoff tail to keep
      // only User + ToolResult, so server-managed tools (web_search,
      // image_generation, file_search, code_interpreter) and Assistant
      // outputs are correctly excluded without needing per-item
      // counting. Sigil bug #167 r3.
      val events = runLines(sse(List(
        """{"type":"response.created","response":{"id":"resp_xyz","status":"in_progress"}}""",
        """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc1","call_id":"call_abc","name":"vector_lookup"}}""",
        """{"type":"response.output_item.added","output_index":1,"item":{"type":"web_search_call","id":"ws1"}}""",
        """{"type":"response.output_item.added","output_index":2,"item":{"type":"message","id":"msg1"}}""",
        """{"type":"response.completed","response":{"status":"completed"}}"""
      )))
      val captured = events.collect { case rc: ProviderEvent.ResponseStateCaptured => rc }
      captured should have size 1
      // runLines seeds state.sentMessageCount = 1 (line 40).
      captured.head.messageCount shouldBe 1
    }

    "emit ResponseStateCaptured(None, _) on previous_response_not_found error to clear the cache" in Task {
      val events = runLines(sse(List(
        """{"type":"response.error","error":{"code":"previous_response_not_found","message":"Response not found"}}"""
      )))
      val captured = events.collect { case rc: ProviderEvent.ResponseStateCaptured => rc }
      captured should have size 1
      captured.head.responseId shouldBe None
      // An Error event also fires so the agent loop's error path observes the failure.
      events.collect { case e: ProviderEvent.Error => e } should have size 1
    }
  }

  "OpenAI Responses request body" should {

    "carry previous_response_id and trim rendered messages when a prior id is cached" in {
      val convId = Conversation.id(s"prev-id-${rapid.Unique()}")
      val topic  = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
      // Seed the agent's projection with a cached response id +
      // message count. The translate pass reads it back via
      // `Sigil.projectionFor` and populates the ProviderCall.
      val priorId    = "resp_prior_xyz"
      val priorCount = 1
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
               _id = convId, topics = List(topic), participants = Nil
             ))))
        _ <- TestSigil.updateProjection(convId, TestAgent)(_.copy(
               latestProviderResponseId           = Some(priorId),
               latestProviderResponseMessageCount = Some(priorCount)
             ))
        body <- {
          // Two Text frames render to two User-role messages. With
          // priorCount = 1 the first one is server-side (dropped) and
          // only the second survives onto the wire.
          val frames = Vector[ContextFrame](
            ContextFrame.Text("first user msg", TestUser, Id[Event]("e1"), MessageVisibility.All),
            ContextFrame.Text("new user follow-up", TestUser, Id[Event]("e3"), MessageVisibility.All)
          )
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
        body should include(""""previous_response_id":"""")
        body should include(priorId)
        // The first frame (user "first user msg") is dropped — its
        // content must not appear on the wire. The trailing frame
        // (new follow-up) survives.
        body shouldNot include("first user msg")
        body should include("new user follow-up")
      }
    }

    "omit previous_response_id and ship the full transcript when no prior id is cached" in {
      val convId = Conversation.id(s"no-prev-${rapid.Unique()}")
      val topic  = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
               _id = convId, topics = List(topic), participants = Nil
             ))))
        // Explicit projection clear so no stale state leaks from a
        // prior spec invocation against the same TestSigil.
        _ <- TestSigil.clearProviderResponseState(convId, TestAgent)
        body <- {
          val frames = Vector[ContextFrame](
            ContextFrame.Text("only message so far", TestUser, Id[Event]("e1"), MessageVisibility.All)
          )
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
        body shouldNot include(""""previous_response_id"""")
        body should include("only message so far")
      }
    }
  }

  "ParticipantProjection persistence helpers" should {

    "round-trip latestProviderResponseId + count through set / clear" in {
      val convId = Conversation.id(s"projection-rt-${rapid.Unique()}")
      for {
        _ <- TestSigil.setProviderResponseState(convId, TestAgent, "resp_id_99", 7)
        afterSet <- TestSigil.projectionFor(TestAgent, convId)
        _ <- TestSigil.clearProviderResponseState(convId, TestAgent)
        afterClear <- TestSigil.projectionFor(TestAgent, convId)
      } yield {
        afterSet.latestProviderResponseId shouldBe Some("resp_id_99")
        afterSet.latestProviderResponseMessageCount shouldBe Some(7)
        afterClear.latestProviderResponseId shouldBe None
        afterClear.latestProviderResponseMessageCount shouldBe None
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
