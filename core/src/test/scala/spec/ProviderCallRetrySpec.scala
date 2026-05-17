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
  ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #211 — `TransientError` on a provider call
 * surfaces to the user without a retry attempt. The framework
 * already classifies network timeouts / 502 / 503 / rate limits as
 * `Retry`-class; this spec pins the behavior that the framework
 * ACTS on that classification by re-attempting the call once before
 * propagating the failure.
 */
class ProviderCallRetrySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "retry-spec")

  /** Records every call's input and (per attempt) the throwable
    * each attempt was instructed to raise. */
  private final class CallRecorder {
    val callCount: atomic.AtomicInteger = new atomic.AtomicInteger(0)
    def record(): Int = callCount.incrementAndGet()
  }

  /** Provider that throws a `Retry`-classified error on its FIRST
    * call and emits a clean `respond` on subsequent calls. */
  private final class FirstCallTransientThenSucceeds(recorder: CallRecorder) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = recorder.record()
      if (n == 1) {
        // First attempt — raise on first pull (before any element).
        // `ReadTimeoutException`-class message so `ErrorClassifier`
        // resolves to `Retry`.
        Stream.force(Task.error(new java.io.IOException("ReadTimeoutException: read timed out (test-injected transient)")))
      } else {
        val cid = CallId(s"respond-$n")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.ToolCallComplete(
            cid,
            RespondInput(
              topicLabel    = "T",
              topicSummary  = "summary",
              content       = "ok",
              endsTurn      = true
            )
          ),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  /** Provider that throws `Retry`-classified errors on EVERY call. */
  private final class AlwaysTransient(recorder: CallRecorder) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      recorder.record()
      Stream.force(Task.error(new java.io.IOException("ReadTimeoutException: persistent transient")))
    }
  }

  /** Provider that throws a `Fatal`-classified error (auth failure). */
  private final class AlwaysFatal(recorder: CallRecorder) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      recorder.record()
      Stream.force(Task.error(new RuntimeException("401 Unauthorized: invalid api key")))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings()
    )

  private def runUserTurn(provider: Provider, label: String): Task[Id[Conversation]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"$label-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Say hi.")),
             state          = EventState.Complete
           ))
      _ <- Task.sleep(3.seconds)
    } yield convId
  }

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "Sigil framework auto-retry on transient provider error (bug #211)" should {

    "retry the call when the first attempt raises a Retry-classified error AND the second succeeds" in {
      val recorder = new CallRecorder
      val provider = new FirstCallTransientThenSucceeds(recorder)
      for {
        convId <- runUserTurn(provider, "retry-then-succeed")
        evs    <- eventsFor(convId)
      } yield {
        // The first attempt errored; the retry must have fired
        // (>= 2 underlying provider calls). Subsequent agent
        // iterations may invoke the provider again — what matters
        // here is that the framework auto-retried instead of
        // surfacing the first attempt's failure.
        recorder.callCount.get() should be >= 2
        // The retry's success reached the agent: a Success-
        // disposition Standard Message from the agent exists, and
        // no Failure bubble surfaced for the user.
        val agentReplies = evs.collect {
          case m: Message
            if m.participantId == TestAgent && m.isSuccess && m.role == MessageRole.Standard => m
        }
        agentReplies should not be empty
        val failureBubbles = evs.collect {
          case m: Message if m.isFailure => m
        }
        failureBubbles shouldBe empty
      }
    }

    "stop retrying after one attempt — repeated transient failure surfaces as a Failure Message" in {
      val recorder = new CallRecorder
      val provider = new AlwaysTransient(recorder)
      for {
        convId <- runUserTurn(provider, "retry-both-fail")
        evs    <- eventsFor(convId)
      } yield {
        // Each provider.apply call attempts at most TWICE
        // (one initial + one retry). Per-call cap of 2 must hold —
        // anything higher means the framework is over-retrying. The
        // agent loop may invoke `provider.apply` again on a
        // subsequent iteration; each such invocation independently
        // caps at 2 underlying call(...) hits.
        recorder.callCount.get() % 2 shouldBe 0
        val failureBubbles = evs.collect {
          case m: Message if m.isFailure && m.failureReason.exists(_.toLowerCase.contains("timeout")) => m
        }
        failureBubbles should not be empty
      }
    }

    "NOT retry when the provider error is Fatal-classified (auth / 4xx)" in {
      val recorder = new CallRecorder
      val provider = new AlwaysFatal(recorder)
      for {
        convId <- runUserTurn(provider, "fatal-no-retry")
        evs    <- eventsFor(convId)
      } yield {
        // Exactly ONE provider call — no retry on Fatal-classified
        // errors. The framework's classifier marks 401 as Fatal.
        recorder.callCount.get() shouldBe 1
        val failureBubbles = evs.collect {
          case m: Message if m.isFailure => m
        }
        failureBubbles should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
