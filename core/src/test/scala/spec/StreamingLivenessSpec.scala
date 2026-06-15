package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.signal.{EventState, MessageDelta, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * Sigil #399 — content `MessageDelta`s must reach `signalsFor` subscribers
 * LIVE as tokens arrive off the provider wire, not in a burst at turn-end. The
 * old `callWithTransientRetry` drained the whole provider stream into a buffer
 * (for retry-safety) before re-emitting, so every content delta was withheld
 * until the stream completed.
 *
 * This pins it: the provider emits one delta, then BLOCKS mid-stream. If deltas
 * stream live, the first `MessageDelta` reaches a subscriber while the provider
 * is still blocked. Under the old buffer-and-drain shape, nothing surfaced
 * until the block cleared (the whole stream had to drain first).
 */
class StreamingLivenessSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "streaming-liveness")
  TestSigil.testModel(modelId)

  /** Emits a first text delta, then blocks on `latch` before emitting the rest
    * + Done. The first delta's `MessageDelta` must surface while still blocked. */
  private final class BlockingMidStreamProvider(latch: CountDownLatch) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("live-0")
      Stream.emit[ProviderEvent](ProviderEvent.ContentBlockDelta(cid, "first ")) ++
        Stream.force(Task {
          latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ContentBlockDelta(cid, "second"),
            ProviderEvent.Done(StopReason.Complete)
          ))
        })
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings())

  "Content MessageDeltas (sigil #399)" should {

    "reach subscribers LIVE — the first delta surfaces while the provider is still mid-stream" in {
      val latch = new CountDownLatch(1)
      TestSigil.setProvider(Task.pure(new BlockingMidStreamProvider(latch)))
      val recorded = new AtomicReference[List[Signal]](Nil)
      @volatile var running = true
      TestSigil.signals
        .evalMap(s => Task { recorded.updateAndGet(s :: _); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      val convId = Conversation.id(s"live-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)

      def firstMessageDeltaSeen: Boolean =
        recorded.get().exists { case m: MessageDelta => m.conversationId == convId; case _ => false }

      def awaitFirstDelta(deadline: Long): Task[Boolean] =
        if (firstMessageDeltaSeen) Task.pure(true)
        else if (System.currentTimeMillis() > deadline) Task.pure(false)
        else Task.sleep(50.millis).flatMap(_ => awaitFirstDelta(deadline))

      for {
        _    <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _    <- TestSigil.publish(Message(
                  participantId  = TestUser,
                  conversationId = convId,
                  topicId        = TestTopicEntry.id,
                  content        = Vector(ResponseContent.Text("Stream me an answer.")),
                  state          = EventState.Complete))
        // The provider is blocked AFTER the first delta. A live stream surfaces
        // the first MessageDelta now; the old buffered shape would not until the
        // block clears (whole stream drained).
        live <- awaitFirstDelta(System.currentTimeMillis() + 10_000L)
        _    =  latch.countDown()                 // unblock so the turn can settle
        _    <- TestSigil.awaitSettled(convId)
      } yield {
        running = false
        withClue("a content MessageDelta must surface while the provider is still blocked mid-stream: ") {
          live shouldBe true
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
