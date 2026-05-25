package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.event.ToolInvoke
import sigil.participant.ParticipantId
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName}
import sigil.tool.ToolContext
import sigil.event.Event

/**
 * Coverage for `ToolContext.setSummary(value)` — lets a tool drive the
 * inline chip tagline across its execution arc. Each call emits a
 * [[sigil.signal.ToolDelta]] that folds the new value onto the
 * persisted [[sigil.event.ToolInvoke.summary]], so the DB row always
 * carries the most recent string and clients subscribing to the wire
 * see updates in real time.
 */
class TurnContextSummarySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class SummaryProbeInput() extends ToolInput derives RW
  ToolInput.register(RW.static(SummaryProbeInput()))

  /** Tool that calls `ctx.setSummary` three times across its execution
    * (start / mid / end). Its result is a trivial confirmation — the
    * work is the summary side effects. Surfaces the full delta
    * sequence + the persisted invoke state for assertions. */
  private case object SummaryProbeTool extends Tool {
    type Input  = SummaryProbeInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[SummaryProbeInput]]
    val outputRW = summon[RW[TextToolOutput]]

    val name        = ToolName("summary_probe")
    val description = "Drives ctx.setSummary three times across execution."

    override def executeOutput(input: SummaryProbeInput, ctx: ToolContext): Task[TextToolOutput] =
      ctx.setSummary("Searching ...").flatMap { _ =>
        ctx.setSummary("Searched 12 files, 7 matches so far").flatMap { _ =>
          ctx.setSummary("12 matches across 31 files")
        }
      }.map(_ => TextToolOutput("done"))
  }

  private val convId = Conversation.id(s"summary-probe-${rapid.Unique()}")
  private val testCaller: ParticipantId = TestUser

  private def runProbe: Task[(List[ToolDelta], List[ToolInvoke])] = {
    // Seed conversation so publish() can resolve the conv row.
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val recorded = new java.util.concurrent.ConcurrentLinkedQueue[ToolDelta]()
    @volatile var running = true
    TestSigil.signals
      .takeWhile(_ => running)
      .evalMap {
        case td: ToolDelta if td.conversationId == convId =>
          Task { recorded.add(td); () }
        case _ => Task.unit
      }
      .drain
      .startUnit()

    // Persist an Active ToolInvoke for the probe to target; the
    // framework's normal orchestrator path would do this, but the
    // spec drives setSummary directly without an orchestrator turn.
    val invoke = ToolInvoke(
      toolName      = SummaryProbeTool.name,
      participantId = testCaller,
      conversationId = convId,
      topicId        = TestTopicId,
      state          = EventState.Active,
      callId         = Some("summary-probe-1")
    )

    val ctx = TurnContext(
      sigil               = TestSigil,
      chain               = List(testCaller),
      conversation        = conv,
      turnInput           = TurnInput(conversationId = convId),
      model = TestSigil.defaultTestModel
    )

    for {
      _      <- Task.sleep(scala.concurrent.duration.DurationInt(50).millis)
      _      <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _      <- TestSigil.publish(invoke)
      _      <- SummaryProbeTool.execute(SummaryProbeInput(), ctx, invoke._id).toList
      _      <- Task.sleep(scala.concurrent.duration.DurationInt(150).millis)
      events <- TestSigil.withDB(_.events.transaction(_.list))
    } yield {
      running = false
      import scala.jdk.CollectionConverters.*
      val deltas = recorded.iterator().asScala.toList
      val invokes = events.collect {
        case t: ToolInvoke if t.conversationId == convId && t._id == invoke._id => t
      }
      (deltas, invokes)
    }
  }

  "ToolContext.setSummary" should {

    "emit one ToolDelta per call, each carrying the corresponding summary value" in {
      runProbe.map { case (deltas, _) =>
        val summaryDeltas = deltas.filter(_.summary.isDefined)
        val values = summaryDeltas.map(_.summary.get)
        values shouldBe List(
          "Searching ...",
          "Searched 12 files, 7 matches so far",
          "12 matches across 31 files"
        )
      }
    }

    "land the LAST value on the persisted ToolInvoke.summary field" in {
      runProbe.map { case (_, invokes) =>
        invokes should have size 1
        invokes.head.summary shouldBe "12 matches across 31 files"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
