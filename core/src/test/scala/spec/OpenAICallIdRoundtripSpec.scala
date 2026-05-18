package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.openai.OpenAIProvider
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, ProviderCall, ProviderEvent
}
import sigil.signal.{EventState, Signal}
import sigil.tool.core.{CoreTools, FindCapabilityTool}
import spice.http.{HttpRequest, HttpResponse}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.{ContentType, url}

/**
 * **Live OpenAI** regression coverage for sigil bug #167 r5 — the
 * orchestrator captures OpenAI's wire `call_id` only in transient
 * state; the persisted `ToolInvoke` carries the framework's generated
 * `Event.id` instead. FrameBuilder + renderFrames then render the
 * framework id as the wire's `tool_call.id` / `function_call_output.
 * call_id`. OpenAI's `previous_response_id` state has the original
 * call_id from its response and 400s "No tool output found for
 * function call <id>" when the framework's id doesn't match.
 *
 * The spec self-skips when `OPENAI_API_KEY` is unset (the env-var
 * key the framework's `OpenAIProvider` reads). It exercises the real
 * `/v1/responses` API end-to-end:
 *
 *   1. User message → Orchestrator.process driving the real
 *      OpenAIProvider for Turn 1. The model emits a function_call
 *      with OpenAI's wire `call_<hash>` id.
 *   2. Events get persisted via `Sigil.publish` (ToolInvoke + the
 *      Tool-role Message from the fake tool's emission), plus
 *      `ResponseStateCaptured` writes `latestProviderResponseId`
 *      to the participant projection.
 *   3. Frames rebuilt, Turn 2's request body rendered via
 *      OpenAIProvider.requestConverter, then POSTed back to OpenAI's
 *      `/v1/responses` endpoint. Pre-fix: HTTP 400 "No tool output
 *      found for function call <openai's id>". Post-fix: 2xx and the
 *      response carries a new output_item.
 */
class OpenAICallIdRoundtripSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val apiKey: String = sys.env.getOrElse("OPENAI_API_KEY", "")
  private val live: Boolean = apiKey.startsWith("sk-")

  private val modelId: Id[Model] = Model.id("openai", "gpt-5-mini")
  private lazy val openai = OpenAIProvider(apiKey, TestSigil, url"https://api.openai.com")

  private def turn1Request(convId: Id[Conversation]): ConversationRequest =
    ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(
        conversationId = convId,
        frames = Vector(
          sigil.conversation.ContextFrame.Text(
            content = "Find a tool to look up information about the manufacturing product RD2500.",
            participantId = TestUser,
            sourceEventId = Id[Event]("user-1"),
            visibility = sigil.event.MessageVisibility.All
          )
        )
      ),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(1000)),
      chain = List(TestUser, TestAgent),
      // FindCapability is already registered via CoreTools.toolInputRWs,
      // so its input RW round-trips through fabric's PolyType. The
      // model is heavily nudged to call it by the standard system
      // prompt (discovery-first).
      tools = Vector(FindCapabilityTool)
    )

  "Live OpenAI Responses call_id roundtrip (Bug #167 r5)" should {

    "round-trip the wire call_id so a chained Turn 2 doesn't 400 on the OpenAI Responses API" in {
      if (!live) cancel("OPENAI_API_KEY not set — skipping live OpenAI spec")
      val convId = Conversation.id(s"live-callid-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        // --- Turn 1: real OpenAI → orchestrator translation ---
        // Drive Orchestrator.process with the real OpenAIProvider.
        // Returns the Signal stream — collect to list to settle.
        req1 = turn1Request(convId)
        sigs <- Orchestrator.process(TestSigil, openai, req1, conv).toList
        // Persist Events to the conversation so FrameBuilder picks them
        // up on the next pass. Signals that are Events flow into
        // Sigil.publish so the projection state updates too (the
        // ResponseStateCaptured path lives inside Orchestrator.process
        // and already publishes via setProviderResponseState).
        // Publish ALL signals (Events AND Deltas) so deltas transition
        // events to Complete state in the DB. framesFor filters by
        // state == Complete; without delta application, ToolInvoke
        // would stay Active and its ContextFrame.ToolCall wouldn't
        // surface.
        _ <- Task.sequence(sigs.map(TestSigil.publish))
        invokes = sigs.collect { case ti: ToolInvoke => ti }
        _ = withClue(
          s"Turn 1 must have produced a ToolInvoke (find_capability). Signals: ${sigs.map(_.getClass.getSimpleName).mkString(", ")}") {
          invokes should not be empty
        }
        // --- Turn 2: render with prev_id chain, POST to OpenAI ---
        frames <- TestSigil.framesFor(convId)
        proj <- TestSigil.projectionFor(TestAgent, convId)
        _ = withClue("Turn 1's response should have captured a previous_response_id on the projection.") {
          proj.latestProviderResponseId.isDefined shouldBe true
        }
        req2 = req1.copy(
          turnInput = TurnInput(conversationId = convId, frames = frames)
        )
        httpReq <- openai.requestConverter(req2)
        // Force stream:false so we get a single JSON response we can
        // parse for a clean status check.
        body = httpReq.content.collect { case s: StringContent => s.value }.getOrElse("")
        nonStreamBody = body.replace("\"stream\":true", "\"stream\":false")
        nonStreamReq = httpReq.withContent(StringContent(nonStreamBody, ContentType.`application/json`))
        // POST to OpenAI directly.
        response <- HttpClient.modify(_ => nonStreamReq).noFailOnHttpStatus.send()
      } yield {
        val status = response.status
        val respBody = response.content.collect { case s: StringContent => s.value }.getOrElse("")
        withClue(
          s"Turn 2 chained via previous_response_id must NOT 400. Got status=$status. " +
            s"Response body (first 600 chars): ${respBody.take(600)} " +
            s"Sigil's Turn 2 wire body (first 600 chars): ${nonStreamBody.take(600)}"
        ) {
          status.isSuccess shouldBe true
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
