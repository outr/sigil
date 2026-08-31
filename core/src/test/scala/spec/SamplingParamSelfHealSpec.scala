package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderStreamException, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil #390 — models that reject a deprecated sampling
 * parameter (Claude 5 generation — Fable 5 / Mythos 5 → HTTP 400
 * "`temperature` is deprecated for this model.") must not be unusable.
 * The provider self-heal (sibling to #387's tool_choice downgrade) detects
 * the rejection and retries the SAME call once with `temperature` / `topP`
 * stripped.
 */
class SamplingParamSelfHealSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "sampling-self-heal")
  TestSigil.testModel(modelId)

  private def rejection(message: String): ProviderStreamException =
    new ProviderStreamException(
      providerKey = "anthropic",
      code = 0,
      typ = "invalid_request_error",
      message_ = message,
      status = Some(400)
    )

  /**
   * Fake provider that records the temperature of every call. On the
   * first call WHILE a sampling param is set it raises the deprecation
   * 400; once the framework retries with sampling stripped it emits a
   * clean `respond`.
   */
  final private class SamplingRejectsThenSucceeds extends Provider {
    val temperatures: java.util.concurrent.ConcurrentLinkedQueue[Option[Double]] =
      new java.util.concurrent.ConcurrentLinkedQueue[Option[Double]]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      temperatures.add(input.generationSettings.temperature)
      if (input.generationSettings.temperature.isDefined || input.generationSettings.topP.isDefined) {
        Stream.force(Task.error(rejection("`temperature` is deprecated for this model.")))
      } else {
        val cid = CallId(s"respond-${temperatures.size()}")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.toolCall(cid, RespondTool)(RespondInput(topicLabel = "T", topicSummary = "s", content = "ok", endsTurn = true)),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      // The conversation's agent sends a sampling param — the thing these
      // models reject.
      generationSettings = GenerationSettings(temperature = Some(0.7))
    )

  private def runUserTurn(provider: Provider): Task[Id[Conversation]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"sampling-heal-${rapid.Unique()}")
    val agent = makeAgent()
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Say hi.")),
        state = EventState.Complete
      ))
      _ <- TestSigil.awaitSettled(convId)
    } yield convId
  }

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "Provider.isDeprecatedSamplingParam" should {
    "match the Anthropic marker case-insensitively, including a wrapped cause" in {
      Provider.isDeprecatedSamplingParam(rejection("`temperature` is deprecated for this model.")) shouldBe true
      Provider.isDeprecatedSamplingParam(rejection("`top_p` IS DEPRECATED FOR THIS MODEL")) shouldBe true
      Provider.isDeprecatedSamplingParam(
        new RuntimeException("wrapper", rejection("temperature is deprecated for this model"))
      ) shouldBe true
      succeed
    }
    "not match unrelated provider errors" in {
      Provider.isDeprecatedSamplingParam(new RuntimeException("rate_limit_error: slow down")) shouldBe false
      Provider.isDeprecatedSamplingParam(rejection("overloaded_error")) shouldBe false
      Provider.isDeprecatedSamplingParam(new RuntimeException(null: String)) shouldBe false
      succeed
    }
  }

  "Provider sampling-param self-heal (sigil #390)" should {
    "strip temperature/topP and retry once when the model rejects them" in {
      val provider = new SamplingRejectsThenSucceeds
      for {
        convId <- runUserTurn(provider)
        evs <- eventsFor(convId)
      } yield {
        val seen = provider.temperatures.asScala.toList
        // First call went out WITH temperature (the agent's setting); the
        // retry went out with it stripped to None.
        seen.headOption.flatten shouldBe Some(0.7)
        seen should contain(None)
        val firstStripped = seen.indexOf(None)
        firstStripped should be > 0
        seen(firstStripped - 1).isDefined shouldBe true
        // The retry's success reached the agent; no Failure bubble.
        val agentReplies = evs.collect {
          case m: Message if m.participantId == TestAgent && m.isSuccess && m.role == MessageRole.Standard => m
        }
        agentReplies should not be empty
        evs.collect { case m: Message if m.isFailure => m } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
