package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, ToolInvoke}
import sigil.participant.{AgentParticipant, AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spec.EagerActiveLatchTool

/**
 * Coverage for Sigil #317 — `ToolInvoke(Active)` must broadcast to live
 * wire subscribers the moment tool dispatch begins, NOT only when the
 * agent-loop iteration's batched-events drain completes.
 *
 * The agent loop runs each iteration inside `withBatchedEvents`; the
 * iteration's signal stream is `Stream.emits(toolDeltaPrefix) ++ finalStream`
 * where `finalStream` forces `executed.toList` — the whole tool body.
 * rapid's `++` evaluates the right side's outer task right after the left
 * side's outer task resolves, so for a slow tool the prefix (carrying the
 * Active ToolInvoke) would reach the wire only at drain-end. The fix does
 * an eager `hub.emit` of the Active invoke ahead of that force; the same
 * event still rides the batch for durable persist (its hub re-emit is
 * suppressed so it broadcasts exactly once).
 *
 * Tests:
 *   1. The client receives `ToolInvoke(Active)` while the tool is still
 *      blocked on its latch (FAILS on pre-fix SNAPSHOT — Active arrives
 *      only at drain-end, which can't happen until the latch releases).
 *   2. The settling `ToolDelta(Complete)` arrives only AFTER the result.
 *   3. The durable event row is persisted exactly once (batch still owns
 *      the write) and broadcast exactly once (no eager + batch double-emit).
 */
class ToolInvokeEagerActiveSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "eager-active")
  TestSigil.testModel(modelId)

  /** Provider that emits a single blocking-tool call on the first
    * attempt, then an atomic `respond` to end the turn cleanly on any
    * subsequent attempt (the agent loop re-triggers after the tool's
    * Tool-role result settles). */
  private final class LatchThenRespondProvider extends Provider {
    val attemptCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = attemptCount.incrementAndGet()
      if (n == 1) {
        val cid = CallId(s"latch-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, EagerActiveLatchTool.name.value),
          ProviderEvent.toolCall(cid, EagerActiveLatchTool)(EagerActiveLatchInput()),
          ProviderEvent.Done(StopReason.Complete)
        ))
      } else {
        val cid = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
          ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
            topicLabel   = "Latch done",
            topicSummary = "tool released, replying",
            content      = "All done.",
            endsTurn     = true
          )),
          ProviderEvent.Done(StopReason.Complete)
        ))
      }
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = EagerActiveAgent,
      modelId            = modelId,
      toolNames          = List(EagerActiveLatchTool.name) ++ CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def startRecorder(): (ConcurrentLinkedQueue[Signal], atomic.AtomicBoolean) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    (recorded, running)
  }

  private def waitFor(deadline: Long)(pred: => Boolean): Task[Unit] =
    if (pred || System.currentTimeMillis() > deadline) Task.unit
    else Task.sleep(50.millis).flatMap(_ => waitFor(deadline)(pred))

  private def latchInvokes(snap: List[Signal]): List[ToolInvoke] =
    snap.collect {
      case ti: ToolInvoke if ti.toolName.value == EagerActiveLatchTool.name.value => ti
    }

  "Sigil #317 — eager Active broadcast" should {

    "broadcast ToolInvoke(Active) at tool-start, while the tool is still blocked" in {
      TestSigil.reset()
      EagerActiveLatchTool.reset()
      val convId  = Conversation.id(s"eager-active-${rapid.Unique()}")
      val agent   = makeAgent()
      val conv    = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      val provider = new LatchThenRespondProvider
      TestSigil.setProvider(Task.pure(provider))

      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text("run the latch tool")),
          state          = EventState.Complete
        ))
        // Wait until the tool body is genuinely executing (and therefore
        // blocked on its latch) — proving the iteration drain has NOT yet
        // completed.
        toolEntered <- Task(EagerActiveLatchTool.startedLatch.await(20, TimeUnit.SECONDS))
        // Give the eager broadcast a moment to reach the recorder fiber.
        _ <- waitFor(System.currentTimeMillis() + 5_000L)(
          latchInvokes(recorded.iterator().asScala.toList)
            .exists(_.state == EventState.Active)
        )
        // Snapshot WHILE the latch is still held — this is the assertion
        // window that fails pre-fix.
        snapshotWhileBlocked = recorded.iterator().asScala.toList
        latchStillHeld = EagerActiveLatchTool.releaseLatch.getCount
        // Release so the turn can complete and the suite tears down clean.
        _ <- Task(EagerActiveLatchTool.releaseLatch.countDown())
        _ <- waitFor(System.currentTimeMillis() + 20_000L)(
          recorded.iterator().asScala.exists {
            case m: Message if m.participantId == agent.id && !m.isFailure
                            && m.state == EventState.Complete => true
            case _ => false
          }
        )
      } yield (toolEntered, latchStillHeld, snapshotWhileBlocked)

      pipeline.map { case (toolEntered, latchStillHeld, snapshotWhileBlocked) =>
        running.set(false)
        toolEntered shouldBe true
        // The tool was still blocked when we snapshotted.
        latchStillHeld shouldBe 1L
        val active = latchInvokes(snapshotWhileBlocked).filter(_.state == EventState.Active)
        active should not be empty
        active.head.input shouldBe defined
      }
    }

    "deliver the settling ToolDelta(Complete) only after the result is released" in {
      TestSigil.reset()
      EagerActiveLatchTool.reset()
      val convId  = Conversation.id(s"eager-active-settle-${rapid.Unique()}")
      val agent   = makeAgent()
      val conv    = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      val provider = new LatchThenRespondProvider
      TestSigil.setProvider(Task.pure(provider))

      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text("run the latch tool")),
          state          = EventState.Complete
        ))
        _ <- Task(EagerActiveLatchTool.startedLatch.await(20, TimeUnit.SECONDS))
        // Eager Active is on the wire; the settle delta is NOT — the
        // tool hasn't produced its result yet.
        _ <- waitFor(System.currentTimeMillis() + 5_000L)(
          latchInvokes(recorded.iterator().asScala.toList).exists(_.state == EventState.Active)
        )
        settledBeforeRelease = recorded.iterator().asScala.toList.collect {
          case td: ToolDelta if td.state.contains(EventState.Complete) => td
        }
        _ <- Task(EagerActiveLatchTool.releaseLatch.countDown())
        _ <- waitFor(System.currentTimeMillis() + 20_000L)(
          recorded.iterator().asScala.exists {
            case m: Message if m.participantId == agent.id && !m.isFailure
                            && m.state == EventState.Complete => true
            case _ => false
          }
        )
        snapAfter = recorded.iterator().asScala.toList
      } yield (settledBeforeRelease, snapAfter)

      pipeline.map { case (settledBeforeRelease, snapAfter) =>
        running.set(false)
        // The latch tool's invoke id, recovered from the eager Active event.
        val invokeId: Id[Event] = latchInvokes(snapAfter)
          .find(_.state == EventState.Active)
          .map(_._id)
          .getOrElse(fail("no Active ToolInvoke for the latch tool was ever broadcast"))
        // No settle delta for THIS invoke landed before the release.
        settledBeforeRelease.exists(_.target == invokeId) shouldBe false
        // After the release, the settling ToolDelta(Complete) DID land.
        snapAfter.collect {
          case td: ToolDelta if td.target == invokeId && td.state.contains(EventState.Complete) => td
        } should not be empty
      }
    }

    "persist + broadcast the ToolInvoke exactly once (batch owns the durable write)" in {
      TestSigil.reset()
      EagerActiveLatchTool.reset()
      val convId  = Conversation.id(s"eager-active-once-${rapid.Unique()}")
      val agent   = makeAgent()
      val conv    = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      val provider = new LatchThenRespondProvider
      TestSigil.setProvider(Task.pure(provider))

      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = TestTopicEntry.id,
          content        = Vector(ResponseContent.Text("run the latch tool")),
          state          = EventState.Complete
        ))
        _ <- Task(EagerActiveLatchTool.startedLatch.await(20, TimeUnit.SECONDS))
        _ <- Task(EagerActiveLatchTool.releaseLatch.countDown())
        _ <- waitFor(System.currentTimeMillis() + 20_000L)(
          recorded.iterator().asScala.exists {
            case m: Message if m.participantId == agent.id && !m.isFailure
                            && m.state == EventState.Complete => true
            case _ => false
          }
        )
        persisted <- TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.filter(_.conversationId == convId))
      } yield (recorded, persisted)

      pipeline.map { case (rec, persisted) =>
        running.set(false)
        val snap = rec.iterator().asScala.toList

        // Exactly one durable ToolInvoke row for the latch tool.
        val persistedInvokes = persisted.collect {
          case ti: ToolInvoke if ti.toolName.value == EagerActiveLatchTool.name.value => ti
        }
        persistedInvokes should have size 1
        val durable = persistedInvokes.head
        // It settled to Complete (the ToolDelta folded onto it).
        durable.state shouldBe EventState.Complete
        durable.callId shouldBe defined

        // Broadcast exactly once: across the whole stream the latch
        // ToolInvoke event (keyed by its durable id) appears once as an
        // emitted Event — no eager + batch double-emit. (The settle is a
        // ToolDelta, a distinct signal, so it doesn't count here.)
        val broadcastInvokeEvents = snap.collect {
          case ti: ToolInvoke if ti._id == durable._id => ti
        }
        broadcastInvokeEvents should have size 1
        broadcastInvokeEvents.head.state shouldBe EventState.Active
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/** Distinct agent id for #317 coverage so its signal queue doesn't
  * cross-pollinate with other specs sharing the TestSigil JVM. */
case object EagerActiveAgent extends AgentParticipantId {
  override val value: String = "eager-active-agent"
}
