package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType,
  ToolCallAccumulator
}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.signal.{MessageDelta, Signal}
import sigil.tool.core.RespondTool
import spice.http.HttpRequest

/**
 * End-to-end coverage for the split-finish_reason dedup: the
 * provider parses the OpenRouter+Kimi two-chunk shape via the real
 * `OpenAIChatCompletions.parseLine` machinery, the resulting
 * `ProviderEvent` stream flows through `Orchestrator.process`, and
 * the conversation settles with exactly one agent Message — proving
 * the wire-layer guard prevents the duplicate `ToolCallComplete`
 * from reaching the orchestrator. The wire-layer unit spec
 * (`StreamingFinishReasonSplitSpec`) asserts the same dedup at the
 * decoder boundary; this spec covers the full path so a future
 * regression where the guard's bypass path appears would surface
 * here as a second user-visible Message rather than a silent debug
 * log.
 */
class StreamingFinishReasonSplitOrchestratorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "split-finish-model")

  private val cfg: OpenAIChatCompletions.Config =
    OpenAIChatCompletions.Config(providerNamespace = "openrouter", providerName = "OpenRouter")

  /** The five SSE lines that exercise the split-finish path:
    * tool_calls header → arguments fragment → bare finish_reason →
    * finish_reason + usage → [DONE]. */
  private val splitFinishLines: List[String] = List(
    """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"respond"}}]}}]}""",
    s"""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"arguments":${
      val args = """{"topicLabel":"Test","topicSummary":"split-finish repro","content":"Hi.","disposition":"Success","endsTurn":true,"keywords":[]}"""
      fabric.io.JsonFormatter.Compact(fabric.str(args))
    }}}]}}]}""",
    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
    """data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1234,"completion_tokens":56,"total_tokens":1290}}""",
    "data: [DONE]"
  )

  /** Provider that drives `splitFinishLines` through the real wire
    * decoder and exposes the resulting `ProviderEvent` sequence. The
    * captured events are inspectable so the spec can assert the wire
    * layer emits a single `ToolCallComplete`. */
  private class SplitFinishProvider extends Provider {
    val capturedEvents: scala.collection.mutable.ListBuffer[ProviderEvent] =
      scala.collection.mutable.ListBuffer.empty

    override def `type`: ProviderType = ProviderType.OpenRouter
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))

    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val state = new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(input.tools))
      val all = splitFinishLines.iterator
        .flatMap(line => OpenAIChatCompletions.parseLine(line, state, cfg))
        .toList
      capturedEvents ++= all
      Stream.emits(all)
    }
  }

  private def run(): Task[(SplitFinishProvider, List[Signal])] = {
    val convId  = Conversation.id("orchestrator-split-finish")
    val conv    = Conversation(topics = TestTopicStack, _id = convId)
    val provider = new SplitFinishProvider
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(100), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector(RespondTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield (provider, signals)
  }

  "Orchestrator on a split-finish_reason SSE stream" should {

    "see exactly one ToolCallComplete from the wire decoder" in {
      run().map { case (provider, _) =>
        val completes = provider.capturedEvents.collect { case c: ProviderEvent.ToolCallComplete => c }
        completes should have size 1
        completes.head.callId.value shouldBe "call_abc"
      }
    }

    "settle the conversation with exactly one agent Message" in {
      run().map { case (_, signals) =>
        val agentMessages = signals.collect {
          case m: Message if m.participantId == TestAgent && m.role == sigil.event.MessageRole.Standard => m
        }
        agentMessages should have size 1
        agentMessages.head.modelId shouldBe Some(modelId)
      }
    }

    "still propagate the usage block from the second (followup) chunk" in {
      run().map { case (_, signals) =>
        val usageDeltas = signals.collect {
          case md: MessageDelta if md.usage.isDefined => md
        }
        usageDeltas should have size 1
        val u = usageDeltas.head.usage.get
        u.promptTokens shouldBe 1234
        u.completionTokens shouldBe 56
        u.totalTokens shouldBe 1290
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
