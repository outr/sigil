package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Autonomous agent generation is serialized per (agent, conversation)
 * — publishing two completed participant Messages in quick succession
 * must NEVER run two provider generations concurrently. Field shape:
 * a voice caller's two transcript fragments landed ~instantly apart,
 * both fan-outs won the claim inside the commit-visibility window of
 * the events store (the claim upsert is buffered until the
 * transaction's Lucene commit; a concurrent claim read seeds a fresh
 * lock entry from the committed state), and the agent generated — and
 * spoke — the same reply twice.
 *
 * Verifies:
 *   1. A burst of rapid user messages never overlaps generations
 *      (max concurrent provider calls == 1).
 *   2. No message is stranded: the conversation reaches quiescence
 *      with an agent reply NEWER than the last user message — a
 *      trigger landing in the check-then-release gap must re-fire the
 *      loop, not evaporate.
 */
class ConcurrentTurnSerializationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "concurrent-turns")
  TestSigil.testModel(modelId)

  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[_root_.sigil.participant.ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }

  /** Every call: bump the in-flight gauge, hold the "generation" open
    * for `holdMs`, respond, release. `peakOverlap` records the peak
    * overlap — the spec's core assertion. */
  private final class SlowRespondProvider(holdMs: Long) extends Provider {
    val inFlight = new AtomicInteger(0)
    val peakOverlap = new AtomicInteger(0)
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = calls.incrementAndGet()
      val callId = CallId(s"call-$n")
      Stream.force {
        Task {
          val cur = inFlight.incrementAndGet()
          peakOverlap.updateAndGet(m => math.max(m, cur))
          ()
        }.flatMap(_ => Task.sleep(holdMs.millis)).map { _ =>
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(callId, RespondInput(
              topicLabel   = TestTopicEntry.label,
              topicSummary = TestTopicEntry.summary,
              content      = s"Reply $n",
              endsTurn     = true
            )),
            ProviderEvent.Done(StopReason.ToolCall)
          )).onFinalize(Task { inFlight.decrementAndGet(); () })
        }
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

  private def userMessage(convId: Id[Conversation], text: String): Message =
    Message(
      participantId  = TestUser,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete
    )

  private def waitFor(timeout: FiniteDuration)(cond: => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (cond || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    loop
  }

  private def agentReplies(convId: Id[Conversation]): Task[List[Message]] =
    TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.collect {
      case m: Message
        if m.conversationId == convId && m.participantId == TestAgent && m.role == MessageRole.Standard => m
    })

  "rapid successive user messages" should {

    "never run two agent generations concurrently" in {
      val provider = new SlowRespondProvider(holdMs = 800L)
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setMemoryExtractor(NoExtraction)
      val convId = Conversation.id(s"concurrent-${rapid.Unique()}")
      val conv   = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        // Two publishes on INDEPENDENT fibers — the voice-consumer
        // shape (two webhook callbacks for two final transcript
        // fragments). Sequential awaited publishes serialize the
        // fan-out naturally; the race needs genuine concurrency.
        f1 = TestSigil.publish(userMessage(convId, "What are your hours?")).start()
        f2 = TestSigil.publish(userMessage(convId, "Also do you take walk-ins?")).start()
        _ <- f1
        _ <- f2
        _ <- waitFor(30.seconds)(provider.calls.get() >= 1 && provider.inFlight.get() == 0)
        // Let any straggler generation begin if one is going to.
        _ <- Task.sleep(1.second)
        _ <- waitFor(10.seconds)(provider.inFlight.get() == 0)
        replies <- agentReplies(convId)
      } yield {
        withClue(s"calls=${provider.calls.get()} peakOverlap=${provider.peakOverlap.get()} " +
          s"replies=${replies.map(m => m.content.collectFirst { case t: ResponseContent.Text => t.text })}: ") {
          provider.peakOverlap.get() shouldBe 1
          replies should not be empty
        }
      }
    }

    "answer a burst without stranding the last message" in {
      val provider = new SlowRespondProvider(holdMs = 300L)
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setMemoryExtractor(NoExtraction)
      val convId = Conversation.id(s"burst-${rapid.Unique()}")
      val conv   = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
      def burst(n: Int): Task[Long] =
        if (n <= 0) Task.pure(0L)
        else {
          val msg = userMessage(convId, s"burst message $n")
          TestSigil.publish(msg).flatMap(_ => Task.sleep(150.millis)).flatMap(_ => burst(n - 1)).map { last =>
            math.max(last, msg.timestamp.value)
          }
        }
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        lastUserTs <- burst(5)
        _ <- waitFor(45.seconds)(provider.inFlight.get() == 0 && provider.calls.get() >= 1)
        // Quiesce: no in-flight generation AND the newest agent reply
        // postdates the last user message (nothing stranded).
        _ <- waitFor(30.seconds) {
          provider.inFlight.get() == 0 &&
            agentReplies(convId).sync().exists(_.timestamp.value > lastUserTs)
        }
        replies <- agentReplies(convId)
      } yield {
        withClue(s"calls=${provider.calls.get()} peakOverlap=${provider.peakOverlap.get()} " +
          s"lastUser=$lastUserTs replyTs=${replies.map(_.timestamp.value)}: ") {
          provider.peakOverlap.get() shouldBe 1
          replies.exists(_.timestamp.value > lastUserTs) shouldBe true
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
