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
 * The sampling-param self-heal is stateless per call, so a model that
 * rejects `temperature` re-pays the 400-then-strip round-trip on EVERY
 * call — each agent-loop iteration and each framework consult. A
 * process-wide memo records the first observed rejection and omits the
 * parameter up front on every later call.
 *
 * Pinned here:
 *   - The memo helpers, and that a recorded rejection overrides a
 *     catalog that still advertises the parameter.
 *   - End-to-end: the first turn pays two wire calls (rejected, then
 *     stripped); every later turn pays one.
 */
class SamplingParamMemoSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "sampling-param-memo")
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
   * Fake provider recording every call's (modelId, temperature). It 400s
   * whenever a sampling param is set and emits a clean `respond` otherwise.
   */
  final private class SamplingRejecter extends Provider {
    val calls: java.util.concurrent.ConcurrentLinkedQueue[(String, Option[Double])] =
      new java.util.concurrent.ConcurrentLinkedQueue[(String, Option[Double])]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.add(input.model._id.value -> input.generationSettings.temperature)
      if (input.generationSettings.temperature.isDefined || input.generationSettings.topP.isDefined)
        Stream.force(Task.error(rejection("`temperature` is deprecated for this model.")))
      else {
        val cid = CallId(s"respond-${calls.size()}")
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
      generationSettings = GenerationSettings(temperature = Some(0.7))
    )

  private def turn(convId: Id[Conversation], text: String): Task[Unit] =
    for {
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text(text)),
        state = EventState.Complete))
      _ <- TestSigil.awaitSettled(convId)
    } yield ()

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "Provider sampling-param memo" should {

    "record and query a rejection process-wide" in Task {
      val memoOnly = Model.id("test", "memo-only-sampling")
      Provider.rejectsSamplingParam(memoOnly, "temperature") shouldBe false
      Provider.recordRejectedSamplingParam(memoOnly, "temperature")
      Provider.recordRejectedSamplingParam(memoOnly, "temperature") // idempotent
      Provider.rejectsSamplingParam(memoOnly, "temperature") shouldBe true
      // Per-parameter, and per-model — neither leaks.
      Provider.rejectsSamplingParam(memoOnly, "top_p") shouldBe false
      Provider.rejectsSamplingParam(Model.id("test", "never-rejected-sampling"), "temperature") shouldBe false
      succeed
    }

    "override a catalog that still advertises the rejected parameter" in Task {
      val cataloged = Model.id("test", "catalog-says-supported")
      // The synthetic fixture lists `temperature` in supportedParameters.
      TestSigil.testModel(cataloged)
      TestSigil.supportsParameter(cataloged, "temperature") shouldBe true
      Provider.recordRejectedSamplingParam(cataloged, "temperature")
      TestSigil.supportsParameter(cataloged, "temperature") shouldBe false
      TestSigil.supportsParameter(cataloged, "max_tokens") shouldBe true
      succeed
    }

    "stop sending the rejected parameter after the first rejection trips the memo" in {
      val provider = new SamplingRejecter
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"sampling-memo-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)

      // The model must NOT be pre-memoed for this to be a real test.
      Provider.rejectsSamplingParam(modelId, "temperature") shouldBe false

      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- turn(convId, "First request.")
        boundary = provider.calls.size()
        _ <- turn(convId, "Second request.")
        _ <- turn(convId, "Third request.")
        evs <- eventsFor(convId)
      } yield {
        val all = provider.calls.asScala.toList
        val turn1 = all.take(boundary).collect { case (m, t) if m == modelId.value => t }
        val later = all.drop(boundary).collect { case (m, t) if m == modelId.value => t }

        // Turn 1 discovered the rejection: the agent's first call carried
        // temperature and was 400ed, the stripped retry succeeded. Exactly
        // one wasted round-trip.
        turn1.headOption.flatten shouldBe Some(0.7)
        turn1.count(_.isDefined) shouldBe 1
        turn1.drop(1) should contain(None)
        Provider.rejectsSamplingParam(modelId, "temperature") shouldBe true

        // The payoff: across both later turns NOT ONE call carried the
        // rejected parameter, so none was 400ed and none was retried.
        // Pre-fix every turn re-paid the rejected call.
        later should not be empty
        later.count(_.isDefined) shouldBe 0

        // Every turn produced a real reply; no Failure bubbles.
        evs.collect { case m: Message if m.participantId == TestAgent && m.isSuccess && m.role == MessageRole.Standard => m } should not be
          empty
        evs.collect { case m: Message if m.isFailure => m } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
