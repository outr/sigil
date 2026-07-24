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
  ProviderType, RequestOverBudgetException, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for sigil #413 — a provider 400 for a request over the model's
 * context window ("prompt is too long") must not kill the agent loop. The
 * wire's rejection is ground truth that the pre-flight estimate
 * under-counted, so the loop re-runs the failed iteration with an emergency
 * refit (`emergencyContextFactor = 0.5^attempt`) instead of surfacing a raw
 * `StreamingHttpFailedException` and bricking the conversation. Bounded by
 * `maxOverflowCompactions`; exhaustion fails CLEAN — a user-readable
 * explanation, not the wire blob.
 */
class ContextOverflowRecoverySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "overflow-model")
  TestSigil.testModel(modelId)

  private val overflowError =
    new RuntimeException(
      """HTTP 400: {"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 200277 tokens > 200000 maximum"}}""")

  /**
   * Throws the vendor overflow 400 for the first `failures` calls, then
   * answers with a terminal respond (topic fast-path — no classifier
   * consult).
   */
  final private class OverflowThenRespondProvider(failures: Int) extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (calls.incrementAndGet() <= failures)
        Stream.emit(()).evalMap[ProviderEvent](_ => Task.error[ProviderEvent](overflowError))
      else {
        val cid = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, "respond"),
          ProviderEvent.ToolCallComplete(
            cid,
            RespondInput(
              topicLabel = TestTopicEntry.label,
              topicSummary = TestTopicEntry.summary,
              content = "Recovered and finished the work.",
              endsTurn = true
            )),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runUserTurn(provider: Provider): Task[Id[Conversation]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"overflow-${rapid.Unique()}")
    val agent = makeAgent()
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Build the page.")),
        state = EventState.Complete
      ))
      _ <- TestSigil.awaitSettled(convId)
    } yield convId
  }

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "Provider.isContextOverflow" should {

    "match the vendor overflow messages and the pre-flight throw" in Task {
      Provider.isContextOverflow(overflowError) shouldBe true
      Provider.isContextOverflow(new RuntimeException(
        "This model's maximum context length is 128000 tokens (context_length_exceeded)")) shouldBe true
      Provider.isContextOverflow(new RuntimeException(
        "The input token count (1048577) exceeds the maximum number of tokens allowed (1048576).")) shouldBe true
      Provider.isContextOverflow(new RequestOverBudgetException(250000, 200000, modelId)) shouldBe true
      // Wrapped one level down the cause chain.
      Provider.isContextOverflow(new RuntimeException("turn failed", overflowError)) shouldBe true
      // Non-overflow errors don't match.
      Provider.isContextOverflow(new RuntimeException("HTTP 429: rate limited")) shouldBe false
      Provider.isContextOverflow(new RuntimeException("connection reset by peer")) shouldBe false
    }

    "recover from a single overflow — the turn completes instead of failing" in {
      val provider = new OverflowThenRespondProvider(failures = 1)
      for {
        convId <- runUserTurn(provider)
        evs <- eventsFor(convId)
      } yield {
        // Call 1 overflowed; the loop re-ran the iteration with the
        // emergency refit and call 2 answered.
        provider.calls.get() shouldBe 2
        val replies = evs.collect {
          case m: Message
              if m.participantId == TestAgent && m.role == MessageRole.Standard
                && m.state == EventState.Complete && m.isSuccess => m
        }
        replies.map(_.content.mkString).exists(_.contains("Recovered and finished")) shouldBe true
        // No Failure bubble — the overflow was contained.
        evs.collect { case m: Message if m.isFailure => m } shouldBe empty
      }
    }

    "fail clean after exhausting emergency compaction — user-readable message, no raw wire blob" in {
      val provider = new OverflowThenRespondProvider(failures = Int.MaxValue)
      for {
        convId <- runUserTurn(provider)
        evs <- eventsFor(convId)
      } yield {
        // Initial attempt + maxOverflowCompactions (2) recoveries, then the
        // clean terminal failure.
        provider.calls.get() shouldBe 3
        val failures = evs.collect { case m: Message if m.isFailure => m }
        failures should have size 1
        val text = failures.head.content.mkString
        text should include("context window")
        text should not include "invalid_request_error"
        text should not include "RuntimeException"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
