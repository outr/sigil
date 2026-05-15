package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageDisposition}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason, TokenUsage
}
import sigil.signal.{EventState, Signal}
import sigil.tool.core.CoreTools
import spice.http.HttpRequest

/**
 * The "silent turn" shape — provider stream ends with
 * `finish_reason: stop`, empty `delta.content`, and no `tool_calls`,
 * followed by a trailing usage chunk. The old code path dropped
 * `ProviderEvent.Usage` (no Message to attach it to) and `runAgentLoop`
 * later synthesized a placeholder Message with the default
 * `TokenUsage(0,0,0)`. Per-turn cost / token attribution vanished
 * — a paid frontier turn looked identical to a local free one.
 *
 * Fix: when Usage arrives and no target Message exists, synthesize
 * the user-visible placeholder right there with the usage attached.
 * The agent loop's silent-turn fallback no longer needs to fire.
 */
class SilentTurnUsageSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("openrouter", "moonshotai/kimi-k2.5")

  /** Provider that mimics the failing kimi-k2.5 wire shape: no tool
    * calls, no content, finish_reason=stop, trailing usage. */
  private class SilentStopProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
      ProviderEvent.Usage(TokenUsage(promptTokens = 27885, completionTokens = 7, totalTokens = 27892)),
      ProviderEvent.Done(StopReason.Complete)
    ))
  }

  "Silent-turn placeholder with usage" should {

    "synthesize a placeholder Message carrying the provider-reported usage" in {
      val convId = Conversation.id(s"silent-turn-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = ConversationRequest(
        conversationId     = convId,
        modelId            = modelId,
        instructions       = Instructions(),
        turnInput          = TurnInput(conversationId = convId),
        currentMode        = ConversationMode,
        currentTopic       = TestTopicEntry,
        previousTopics     = Nil,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        chain              = List(TestUser, TestAgent),
        tools              = CoreTools.all.toVector
      )
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new SilentStopProvider, request, conv).toList
      } yield {
        val placeholders = signals.collect {
          case m: Message
            if m.conversationId == convId &&
              m.participantId == TestAgent &&
              m.role == sigil.event.MessageRole.Standard => m
        }
        placeholders should have size 1
        val p = placeholders.head
        // The placeholder carries the actual provider usage, not the default zero.
        p.usage.promptTokens shouldBe 27885
        p.usage.completionTokens shouldBe 7
        p.usage.totalTokens shouldBe 27892
        // Attributed to the model that actually ran.
        p.modelId shouldBe Some(modelId)
        // Failure disposition so UI consumers distinguish from real replies.
        p.disposition shouldBe a [MessageDisposition.Failure]
        // Settled — no streaming placeholder still in flight.
        p.state shouldBe EventState.Complete
      }
    }

    "persist the placeholder with non-zero usage through the publish pipeline" in {
      val convId = Conversation.id(s"silent-turn-persist-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = ConversationRequest(
        conversationId     = convId,
        modelId            = modelId,
        instructions       = Instructions(),
        turnInput          = TurnInput(conversationId = convId),
        currentMode        = ConversationMode,
        currentTopic       = TestTopicEntry,
        previousTopics     = Nil,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        chain              = List(TestUser, TestAgent),
        tools              = CoreTools.all.toVector
      )
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new SilentStopProvider, request, conv).toList
        _       <- signals.foldLeft(Task.unit) { (acc, s) =>
                     acc.flatMap(_ => TestSigil.publish(s).handleError(_ => Task.unit))
                   }
        evs     <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val persisted = evs.collect {
          case m: Message
            if m.conversationId == convId &&
              m.participantId == TestAgent &&
              m.role == sigil.event.MessageRole.Standard => m
        }
        persisted should have size 1
        persisted.head.usage.promptTokens shouldBe 27885
        persisted.head.usage.totalTokens shouldBe 27892
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
