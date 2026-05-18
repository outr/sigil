package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ContextFrame, Topic, TopicEntry, TurnInput}
import sigil.event.{Event, LogLevel, ToolInvoke, ToolLog}
import sigil.signal.{EventState, Signal}
import sigil.tool.ToolName

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil bug #69 — `ToolLog` event subtype carrying
 * one streaming-progress line emitted by a tool. Verifies:
 *
 *   1. `TurnContext.toolLog(content)` publishes a `ToolLog` Event
 *      paired to `currentToolInvokeId` via `origin`.
 *   2. The published ToolLog persists in `db.events` (durable, not
 *      transient like a Notice).
 *   3. `FrameBuilder` produces no [[ContextFrame]] for ToolLog —
 *      logs stay out of the agent's prompt context (the durable
 *      `Event.contextFrame` field is `None` after settle).
 *   4. `TurnContext.toolLog` is a no-op when no tool is dispatching
 *      (`currentToolInvokeId == None`).
 */
class ToolLogSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def upsertConv(): Task[Conversation] = {
    val convId = Conversation.id(s"toollog-${rapid.Unique()}")
    val topic = TopicEntry(id = Topic.id(s"topic-$convId"), label = "test", summary = "test")
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  /**
   * Capture every signal emitted while `body` runs.
   */
  private def captureSignals[A](body: Task[A]): Task[(A, List[Signal])] = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    for {
      _ <- Task.sleep(50.millis)
      result <- body
      _ <- Task.sleep(150.millis)
    } yield {
      running.set(false)
      (result, recorded.iterator().asScala.toList)
    }
  }

  private def freshContext(conv: Conversation, invokeId: Option[Id[Event]]): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = TurnInput(conversationId = conv._id),
      currentToolInvokeId = invokeId,
      currentToolName = invokeId.map(_ => ToolName("test_tool"))
    )

  "TurnContext.toolLog" should {

    "publish a ToolLog Event paired to currentToolInvokeId via origin" in {
      for {
        conv <- upsertConv()
        invoke = ToolInvoke(
          toolName = ToolName("test_tool"),
          participantId = TestUser,
          conversationId = conv._id,
          topicId = conv.currentTopicId,
          state = EventState.Complete
        )
        _ <- TestSigil.publish(invoke)
        ctx = freshContext(conv, Some(invoke._id))
        captured <- captureSignals(ctx.toolLog("hello from tool", LogLevel.Info))
        (_, signals) = captured
        events <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val toolLogs = signals.collect { case t: ToolLog => t }
        toolLogs should have size 1
        val tl = toolLogs.head
        tl.content shouldBe "hello from tool"
        tl.level shouldBe LogLevel.Info
        tl.origin shouldBe Some(invoke._id)
        tl.conversationId shouldBe conv._id

        val persisted = events.collect { case t: ToolLog if t._id == tl._id => t }
        persisted should have size 1

        // FrameBuilder didn't emit a frame — ToolLog is a
        // ControlPlaneEvent and stays out of the agent's prompt.
        persisted.head.contextFrame shouldBe None
      }
    }

    "be a no-op when no tool is dispatching (currentToolInvokeId == None)" in {
      for {
        conv <- upsertConv()
        ctx = freshContext(conv, None)
        captured <- captureSignals(ctx.toolLog("nobody listens"))
        (_, signals) = captured
      } yield signals.collect { case _: ToolLog => 1 } shouldBe empty
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
