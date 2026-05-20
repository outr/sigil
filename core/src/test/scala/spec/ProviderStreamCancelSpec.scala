package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Stop
import sigil.provider.{
  ConversationMode, GenerationSettings, ProviderCall, ProviderStreamRegistry, ToolChoice
}
import spice.http.client.StreamHandle

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*

/**
 * Coverage for true mid-stream HTTP abort on `Stop`. A streaming
 * provider call registers its spice `StreamHandle.cancel` in the
 * framework's [[ProviderStreamRegistry]]; when a `Stop` for that
 * (agent, conversation) lands, the dispatch path runs the handle so the
 * in-flight call aborts immediately instead of draining to natural
 * completion (and billing the discarded output tokens).
 *
 * The synthetic `StreamHandle` here mimics okhttp's behaviour: its
 * `stream` polls a cancelled flag between elements and terminates once
 * the flag flips, exactly as the line stream over a cancelled
 * `Call` does.
 */
class ProviderStreamCancelSpec
  extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {

  TestSigil.initFor(getClass.getSimpleName)

  override protected def afterAll(): Unit = {
    TestSigil.shutdown.sync()
    super.afterAll()
  }

  /** Build a synthetic streaming handle: `total` lines emitted ~`gap`
    * apart, with each emission gated on a cancelled flag that `cancel`
    * flips. Returns the handle plus the flag and an emitted-count
    * counter for assertions. */
  private def syntheticHandle(total: Int,
                              gap: FiniteDuration): (StreamHandle[String], AtomicBoolean, AtomicInteger) = {
    val cancelled = new AtomicBoolean(false)
    val emitted = new AtomicInteger(0)
    val stream: Stream[String] =
      Stream
        .emits((1 to total).toList)
        .evalMap { i =>
          Task.sleep(gap).map { _ =>
            emitted.incrementAndGet()
            s"line-$i"
          }
        }
        .takeWhile(_ => !cancelled.get())
    val handle = StreamHandle(stream, Task(cancelled.set(true)))
    (handle, cancelled, emitted)
  }

  private def callFor(convId: Id[Conversation],
                      agent: Option[sigil.participant.ParticipantId]): ProviderCall =
    ProviderCall(
      modelId            = Model.id("test", "stream-cancel"),
      system             = "",
      messages           = Vector.empty,
      tools              = Vector.empty,
      builtInTools       = Set.empty,
      toolChoice         = ToolChoice.Auto,
      generationSettings = GenerationSettings(),
      currentMode        = ConversationMode,
      conversationId     = Some(convId),
      agentId            = agent
    )

  "ProviderStreamRegistry" should {

    "run the registered cancel handle for a matching (agent, conversation) key" in {
      val reg = new ProviderStreamRegistry
      val convId = Conversation.id("psc-cancel-match")
      val ran = new AtomicBoolean(false)
      reg.register(convId, TestAgent, Task(ran.set(true)))
      reg.size shouldBe 1
      reg.cancelFor(convId, Some(TestAgent)).map { _ =>
        ran.get() shouldBe true
        reg.size shouldBe 0
      }
    }

    "leave a non-matching key untouched" in {
      val reg = new ProviderStreamRegistry
      val convA = Conversation.id("psc-cancel-A")
      val convB = Conversation.id("psc-cancel-B")
      val ran = new AtomicBoolean(false)
      reg.register(convA, TestAgent, Task(ran.set(true)))
      reg.cancelFor(convB, Some(TestAgent)).map { _ =>
        ran.get() shouldBe false
        reg.size shouldBe 1
      }
    }

    "cancel every agent's stream for a conversation-wide Stop (no target)" in {
      val reg = new ProviderStreamRegistry
      val convId = Conversation.id("psc-cancel-all")
      val a = new AtomicBoolean(false)
      val b = new AtomicBoolean(false)
      reg.register(convId, TestAgent, Task(a.set(true)))
      reg.register(convId, TestUser, Task(b.set(true)))
      reg.cancelFor(convId, None).map { _ =>
        a.get() shouldBe true
        b.get() shouldBe true
        reg.size shouldBe 0
      }
    }
  }

  "ProviderStreamRegistry.track" should {

    "abort the stream promptly when cancelFor runs mid-consumption" in {
      val reg = new ProviderStreamRegistry
      val convId = Conversation.id("psc-track-abort")
      // 100 lines, 50ms apart — natural duration ~5s. A cancel after
      // ~200ms must terminate it well inside 2s.
      val (handle, _, emitted) = syntheticHandle(total = 100, gap = 50.millis)
      val tracked = reg.track(callFor(convId, Some(TestAgent)), handle)

      val started = System.currentTimeMillis()
      for {
        fiber   <- tracked.toList.start
        _       <- Task.sleep(200.millis)
        _       <- {
                     reg.size shouldBe 1
                     reg.cancelFor(convId, Some(TestAgent))
                   }
        lines   <- fiber.join
      } yield {
        val elapsed = System.currentTimeMillis() - started
        withClue(s"stream took ${elapsed}ms; should abort well before the ~5s natural duration: ") {
          elapsed should be < 2000L
        }
        // Cancel cut the stream short — not all 100 lines arrived.
        emitted.get() should be < 100
        lines.size should be < 100
        // The handle deregistered via Stream.guarantee on termination.
        reg.size shouldBe 0
      }
    }

    "complete normally and deregister its handle when no Stop arrives" in {
      val reg = new ProviderStreamRegistry
      val convId = Conversation.id("psc-track-complete")
      val (handle, cancelled, emitted) = syntheticHandle(total = 5, gap = 10.millis)
      val tracked = reg.track(callFor(convId, Some(TestAgent)), handle)
      tracked.toList.map { lines =>
        lines shouldBe (1 to 5).map(i => s"line-$i").toList
        emitted.get() shouldBe 5
        cancelled.get() shouldBe false
        // Natural completion deregisters via Stream.guarantee.
        reg.size shouldBe 0
      }
    }

    "not track a call that lacks an agent id (one-shot consult)" in {
      val reg = new ProviderStreamRegistry
      val convId = Conversation.id("psc-track-untracked")
      val (handle, _, _) = syntheticHandle(total = 3, gap = 5.millis)
      val tracked = reg.track(callFor(convId, agent = None), handle)
      tracked.toList.map { lines =>
        lines shouldBe (1 to 3).map(i => s"line-$i").toList
        // Never registered — no agent turn for a Stop to target.
        reg.size shouldBe 0
      }
    }
  }

  "Sigil.publish(Stop)" should {

    "abort an in-flight provider stream registered on Sigil.providerStreams" in {
      val convId = Conversation.id(s"psc-publish-stop-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = Nil, _id = convId)
      val (handle, _, emitted) = syntheticHandle(total = 100, gap = 50.millis)
      val tracked = TestSigil.providerStreams.track(callFor(convId, Some(TestAgent)), handle)

      val started = System.currentTimeMillis()
      for {
        _     <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        fiber <- tracked.toList.start
        _     <- Task.sleep(200.millis)
        _     <- {
                   TestSigil.providerStreams.size shouldBe 1
                   TestSigil.publish(Stop(
                     participantId       = TestUser,
                     conversationId      = convId,
                     topicId             = TestTopicEntry.id,
                     targetParticipantId = Some(TestAgent),
                     force               = true
                   ))
                 }
        lines <- fiber.join
      } yield {
        val elapsed = System.currentTimeMillis() - started
        withClue(s"stream took ${elapsed}ms; a Stop must abort the call well before its ~5s natural duration: ") {
          elapsed should be < 2000L
        }
        emitted.get() should be < 100
        lines.size should be < 100
        TestSigil.providerStreams.size shouldBe 0
      }
    }
  }
}
