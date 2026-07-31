package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, Stop}
import sigil.orchestrator.SyntheticDiagnostic
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderStreamException, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import sigil.tool.core.RespondTool

/**
 * Regression for sigil #415 — the user's Stop must halt the agent, including
 * across claim/release churn and mid-retry. Field evidence: a
 * `Stop(force = true)` landed on an erroring conversation and six more
 * full-history provider calls followed over the next 90 seconds, because
 * [[sigil.dispatcher.StopFlag]]s only reach a claim that is live at the
 * instant the Stop arrives, and the provider retry loop never consulted
 * stop state before re-issuing.
 *
 * Pins:
 *   1. The conversation-level stop LATCH outlives claims: a Stop with no
 *      live claim still suppresses subsequent agent wake-ups (framework
 *      diagnostics, challenge machinery, queued re-triggers)...
 *   2. ...until a user-authored Message NEWER than the Stop re-arms the
 *      conversation.
 *   3. A Stop landing mid-turn suppresses the provider retry loop — no
 *      fresh wire call after the Stop — and the turn ends quietly (no
 *      Failure bubble for the stop-induced error).
 */
class StopContractSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "stop-contract-model")
  TestSigil.testModel(modelId)

  /** Answers every call with a terminal respond (topic fast-path). */
  private final class RespondProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.incrementAndGet()
      val cid = CallId(s"respond-${rapid.Unique()}")
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ToolCallStart(cid, "respond"),
        ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
          topicLabel   = TestTopicEntry.label,
          topicSummary = TestTopicEntry.summary,
          content      = "Resumed after the stop.",
          endsTurn     = true
        )),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  /** Publishes a Stop for its own conversation from INSIDE the first call,
    * then throws a transient (retry-classified) provider error. Without the
    * retry-loop stop guard, `callWithTransientRetry` re-issues the call
    * after its backoff — a fresh wire request the already-fired stream
    * cancel can never reach. */
  private final class StopMidCallProvider(convId: Id[Conversation]) extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.incrementAndGet()
      Stream.emit(()).evalMap[ProviderEvent] { _ =>
        TestSigil.publish(Stop(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          force          = false,
          reason         = Some("user pressed stop")
        )).flatMap(_ => Task.error[ProviderEvent](new ProviderStreamException(
          providerKey = "test",
          code        = 503,
          typ         = "provider_unavailable",
          message_    = "upstream briefly saturated"
        )))
      }
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def freshConv(prefix: String): Task[Id[Conversation]] = {
    val convId = Conversation.id(s"$prefix-${rapid.Unique()}")
    val conv   = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_ => convId)
  }

  private def userMessage(convId: Id[Conversation], text: String): Message =
    Message(
      participantId  = TestUser,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete
    )

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "The conversation stop latch (sigil #415)" should {

    "suppress agent wake-ups after a Stop with no live claim, until a fresh user message re-arms" in {
      val provider = new RespondProvider
      TestSigil.setProvider(Task.pure(provider))
      for {
        convId <- freshConv("stop-latch")
        // Stop lands with NO claim live — pre-fix this was a total no-op.
        _ <- TestSigil.publish(Stop(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               force          = true
             ))
        _ <- Task.sleep(150.millis)
        // A framework-style Tool-role diagnostic — the exact shape the
        // challenge / auto-continue machinery uses to re-trigger an agent.
        _ <- Task.sequence(SyntheticDiagnostic(
               "_stall_detected", TestAgent, convId, TestTopicEntry.id,
               reason = "queued re-trigger from before the stop"
             ).collect { case e: sigil.event.Event => TestSigil.publish(e) })
        _ <- Task.sleep(300.millis)
        suppressedCalls = provider.calls.get()
        // A fresh user message re-arms the conversation.
        _ <- TestSigil.publish(userMessage(convId, "keep going"))
        _ <- TestSigil.awaitSettled(convId)
      } yield {
        suppressedCalls shouldBe 0
        provider.calls.get() shouldBe 1
      }
    }
  }

  "A Stop landing mid-turn (sigil #415)" should {

    "suppress the provider retry loop and end the turn quietly" in {
      for {
        convId <- freshConv("stop-midturn")
        provider = new StopMidCallProvider(convId)
        _ <- Task(TestSigil.setProvider(Task.pure(provider)))
        _ <- TestSigil.publish(userMessage(convId, "Build the page."))
        _ <- TestSigil.awaitSettled(convId)
        _ <- Task.sleep(300.millis) // would-be retry backoff window
        evs <- eventsFor(convId)
      } yield {
        // The transient error is retry-classified and the provider allows
        // retries — but the Stop published mid-call must prevent any fresh
        // wire request.
        provider.calls.get() shouldBe 1
        // The stop-induced error ends the turn QUIETLY — no Failure bubble
        // for an error the user's own Stop produced.
        evs.collect { case m: Message if m.isFailure => m } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
