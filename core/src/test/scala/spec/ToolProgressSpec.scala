package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.signal.{Signal, ToolProgress}

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for long-running tools surfacing mid-execution progress via
 * [[sigil.tool.ToolContext.reportProgress]], which the framework routes
 * as [[sigil.signal.ToolProgress]] Notices on `Sigil.signals`. The
 * framework constructs the per-dispatch [[sigil.tool.ToolContext]] with
 * the originating `invokeId` and tool name baked in, so tool bodies
 * never thread correlation ids manually.
 *
 * Drives [[ProgressEmittingTool]] directly via `tool.execute`, then
 * asserts the three published pulses landed on the signal stream with
 * the expected `invokeId`, `attribution`, and `percent` fields.
 */
class ToolProgressSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"tool-progress-${rapid.Unique()}")

  private def runScenario(invokeId: Id[Event]): Task[List[Signal]] = {
    val conv = Conversation(
      topics       = List(TopicEntry(TestTopicId, "test", "test")),
      participants = Nil,
      _id          = convId
    )

    val ctx = TurnContext(
      sigil               = TestSigil,
      chain               = List(TestUser),
      conversation        = conv,
      turnInput           = TurnInput(conversationId = convId),
      model = TestSigil.defaultTestModel
    )

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    for {
      _ <- Task.sleep(100.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- ProgressEmittingTool.execute(ToolProgressInput(), ctx, invokeId).toList
      _ <- Task.sleep(300.millis) // let pulses fan out before snapshotting
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "ToolContext.reportProgress" should {
    "publish ToolProgress pulses with the stamped invoke id and tool attribution" in {
      val invokeId = Id[Event](s"invoke-${rapid.Unique()}")
      runScenario(invokeId).map { signals =>
        val pulses = signals.collect { case p: ToolProgress => p }
        pulses.size shouldBe 3
        pulses.foreach { p =>
          p.invokeId shouldBe invokeId
          p.conversationId shouldBe convId
          p.attribution shouldBe Some(ProgressEmittingTool.name)
        }
        pulses.map(_.message) shouldBe List("preparing", "halfway", "almost done")
        pulses.map(_.percent) shouldBe List(None, Some(0.5), Some(0.9))
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
