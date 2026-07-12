package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Fiber, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Stop
import sigil.provider.{
  GenerationSettings, Provider, ProviderCall, ProviderEvent, ProviderType,
  StopReason, StreamSlotWaitAbortedException, ToolChoice
}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The live-stream slot gate (`Provider.gateStreamingCalls`). A backend
 * that queues excess requests server-side while holding connections
 * open (llama.cpp with N slots) turns a batch fan-out into hundreds of
 * idle sockets; the gate keeps excess calls in-process instead:
 *
 *   1. never more than `maxConcurrent` streams in flight;
 *   2. queued calls are serviced in FIFO order;
 *   3. a queued waiter abandons the wait when a Stop lands for its
 *      conversation — before any wire request is issued;
 *   4. providers that don't opt in are completely ungated.
 */
class StreamSlotGateSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "slot-gate-model")

  /** Test provider exposing the (protected) gated dispatch. Each call
    * registers itself, parks on `holdLatch`, and tears its
    * registration down when its stream terminates. */
  private final class GatedStubProvider(maxSlots: Int, gated: Boolean) extends Provider {
    val active = new AtomicInteger(0)
    val peak = new AtomicInteger(0)
    val serviceOrder = new ConcurrentLinkedQueue[String]()
    @volatile var holdLatch: CountDownLatch = new CountDownLatch(0)

    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def maxConcurrent: Int = maxSlots
    override def gateStreamingCalls: Boolean = gated
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("stub provider — no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.force(Task {
      val n = active.incrementAndGet()
      peak.updateAndGet(p => math.max(p, n))
      serviceOrder.add(input.system)
      holdLatch.await(10, TimeUnit.SECONDS)
      Stream.emit[ProviderEvent](ProviderEvent.Done(StopReason.Complete))
        .guarantee(Task { active.decrementAndGet(); () })
    })

    def runGated(c: ProviderCall): Task[List[ProviderEvent]] = gatedCall(c).toList
  }

  private def makeCall(tag: String,
                       convId: Option[Id[Conversation]] = None): ProviderCall = ProviderCall(
    model = TestSigil.testModel(modelId),
    system = tag,
    messages = Vector.empty,
    tools = Vector.empty,
    builtInTools = Set.empty,
    toolChoice = ToolChoice.Auto,
    generationSettings = GenerationSettings(),
    conversationId = convId,
    agentId = Some(TestAgent)
  )

  private def waitFor(deadlineMs: Long)(pred: => Boolean): Task[Unit] =
    if (pred || System.currentTimeMillis() > deadlineMs) Task.unit
    else Task.sleep(25.millis).flatMap(_ => waitFor(deadlineMs)(pred))

  private def startAll(tasks: List[Task[List[ProviderEvent]]]): Task[List[Fiber[List[ProviderEvent]]]] =
    tasks.foldLeft(Task.pure(List.empty[Fiber[List[ProviderEvent]]])) { (acc, t) =>
      acc.flatMap(list => t.start.map(f => list :+ f))
    }

  private def joinAll(fibers: List[Fiber[List[ProviderEvent]]]): Task[List[List[ProviderEvent]]] =
    fibers.foldLeft(Task.pure(List.empty[List[ProviderEvent]])) { (acc, f) =>
      acc.flatMap(list => f.join.map(r => list :+ r))
    }

  "the stream-slot gate" should {

    "never let in-flight streams exceed maxConcurrent" in {
      val provider = new GatedStubProvider(maxSlots = 2, gated = true)
      provider.holdLatch = new CountDownLatch(1)
      for {
        fibers <- startAll((1 to 6).map(i => provider.runGated(makeCall(s"call-$i"))).toList)
        // Two slots fill; the other four queue at the gate.
        _ <- waitFor(System.currentTimeMillis() + 5000L)(provider.active.get() == 2)
        _ <- Task.sleep(200.millis) // give a would-be third stream time to (wrongly) start
        activeWhileHeld = provider.active.get()
        _ <- Task(provider.holdLatch.countDown())
        results <- joinAll(fibers)
      } yield {
        activeWhileHeld shouldBe 2
        provider.peak.get() shouldBe 2
        results should have size 6
        all(results.map(_.size)) shouldBe 1
      }
    }

    "service queued calls in FIFO order" in {
      val provider = new GatedStubProvider(maxSlots = 1, gated = true)
      // The first call parks on the latch holding the only slot while
      // the rest arrive at the gate staggered wider than scheduling
      // noise — so semaphore arrival order IS submission order.
      provider.holdLatch = new CountDownLatch(1)
      val tags = (1 to 4).map(i => s"fifo-$i").toList
      def submitStaggered(remaining: List[String],
                          fibers: List[Fiber[List[ProviderEvent]]]): Task[List[Fiber[List[ProviderEvent]]]] =
        remaining match {
          case Nil => Task.pure(fibers.reverse)
          case tag :: rest =>
            provider.runGated(makeCall(tag)).start.flatMap { f =>
              Task.sleep(150.millis).flatMap(_ => submitStaggered(rest, f :: fibers))
            }
        }
      for {
        fibers <- submitStaggered(tags, Nil)
        _ <- Task(provider.holdLatch.countDown())
        _ <- joinAll(fibers)
      } yield {
        provider.serviceOrder.iterator().asScala.toList shouldBe tags
        provider.peak.get() shouldBe 1
      }
    }

    "abandon a queued wait when a Stop lands for the call's conversation" in {
      val provider = new GatedStubProvider(maxSlots = 1, gated = true)
      provider.holdLatch = new CountDownLatch(1)
      val convId = Conversation.id(s"slot-gate-stop-${rapid.Unique()}")
      for {
        // A takes the only slot and parks.
        fiberA <- provider.runGated(makeCall("holder")).start
        _ <- waitFor(System.currentTimeMillis() + 5000L)(provider.active.get() == 1)
        // B queues behind it, carrying its conversation identity.
        fiberB <- provider.runGated(makeCall("queued", convId = Some(convId))).attempt.start
        _ <- Task.sleep(300.millis)
        // The user stops B's conversation while B is still queued.
        _ <- TestSigil.publish(Stop(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          force = true
        ))
        outcomeB <- fiberB.join
        _ <- Task(provider.holdLatch.countDown())
        resultA <- fiberA.join
      } yield {
        outcomeB.isFailure shouldBe true
        val ex = outcomeB.failed.get
        ex shouldBe a[StreamSlotWaitAbortedException]
        ex.asInstanceOf[StreamSlotWaitAbortedException].timedOut shouldBe false
        // B never reached the wire.
        provider.serviceOrder.iterator().asScala.toList shouldBe List("holder")
        // A was untouched and completes normally once released.
        resultA should have size 1
      }
    }

    "leave providers that don't opt in completely ungated" in {
      val provider = new GatedStubProvider(maxSlots = 2, gated = false)
      provider.holdLatch = new CountDownLatch(1)
      for {
        fibers <- startAll((1 to 4).map(i => provider.runGated(makeCall(s"ungated-$i"))).toList)
        // All four run concurrently despite maxConcurrent = 2.
        _ <- waitFor(System.currentTimeMillis() + 5000L)(provider.active.get() == 4)
        activeWhileHeld = provider.active.get()
        _ <- Task(provider.holdLatch.countDown())
        _ <- joinAll(fibers)
      } yield {
        activeWhileHeld shouldBe 4
        provider.peak.get() shouldBe 4
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
