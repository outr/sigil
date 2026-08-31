package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, Topic, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.*
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.time.{Clock, Instant, ZoneId, ZoneOffset, ZonedDateTime}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A model with no clock does not decline to answer date questions — it
 * states a date from recall and computes deadlines from it. The feature
 * puts the real date in every request, and says the value is the only
 * one to reason from.
 *
 * The clock is pinned here (as it is for every spec, via
 * [[TestSigil.PinnedClock]]) so the assertions can be the exact rendered
 * text rather than a shape.
 */
class CurrentDateFeatureSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "current-date")
  TestSigil.testModel(modelId)

  /**
   * The text the pinned clock renders — the whole section, exactly.
   */
  private val expectedBlock: String =
    "\n== Current date and time ==\n" +
      "Today is Saturday, March 14, 2026, 15:09 UTC.\n" +
      CurrentDateFeature.Directive

  final private class CapturingProvider extends Provider {
    val calls = new ConcurrentLinkedQueue[ProviderCall]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.add(input)
      val cid = CallId(s"date-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
        ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
          topicLabel = TestTopicEntry.label,
          topicSummary = TestTopicEntry.summary,
          content = "ok",
          endsTurn = true
        )),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private val provider = new CapturingProvider

  private val convId = Conversation.id(s"current-date-${rapid.Unique()}")

  private val topic = Topic(
    conversationId = convId,
    label = TestTopicEntry.label,
    summary = TestTopicEntry.summary,
    createdBy = TestUser,
    _id = Id[Topic](s"current-date-topic-${rapid.Unique()}")
  )

  private def agent: AgentParticipant = DefaultAgentParticipant(
    id = TestAgent,
    modelId = modelId,
    toolNames = CoreTools.coreToolNames,
    instructions = Instructions(),
    generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
  )

  private def waitFor(deadline: Long)(pred: => Boolean): Task[Unit] =
    if (pred || System.currentTimeMillis() > deadline) Task.unit
    else Task.sleep(50.millis).flatMap(_ => waitFor(deadline)(pred))

  private def idleCount(snap: List[Signal]): Int =
    snap.count {
      case d: AgentStateDelta => d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete)
      case _ => false
    }

  private lazy val runTurn: Task[ProviderCall] = {
    TestSigil.reset()
    TestSigil.setProvider(Task.pure(provider))
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new AtomicBoolean(true)
    TestSigil.signals.takeWhile(_ => running.get()).evalMap(s => Task { recorded.add(s); () }).drain.startUnit()
    val conv = Conversation(
      topics = List(sigil.conversation.TopicEntry(topic._id, topic.label, topic.summary)),
      participants = List(agent),
      _id = convId
    )
    for {
      _ <- Task.sleep(120.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = topic._id,
        content = Vector(ResponseContent.Text("what is today's date?")),
        state = EventState.Complete
      ))
      _ <- waitFor(System.currentTimeMillis() + 30_000L)(idleCount(recorded.iterator().asScala.toList) > 0)
      _ <- Task(running.set(false))
    } yield provider.calls.iterator().asScala.toList.headOption.getOrElse(
      throw new IllegalStateException("no provider call captured"))
  }.singleton

  "A rendered request" should {
    "carry the current date and the directive to reason only from it" in
      runTurn.map { call =>
        withClue(s"rendered system prompt:\n${call.systemCombined}\n") {
          call.systemCombined should include(expectedBlock)
        }
      }

    "carry the clock in the volatile tail, never in the cacheable prefix" in
      runTurn.map { call =>
        call.systemVolatile should include(expectedBlock)
        call.system should not include "Today is"
      }
  }

  "The rendered section" should {
    "read the instant in UTC whatever zone the clock carries" in {
      val chicagoLateNight = ZonedDateTime.of(2026, 3, 14, 23, 30, 0, 0, ZoneId.of("America/Chicago")).toInstant
      val feature = CurrentDateFeature(Clock.fixed(chicagoLateNight, ZoneId.of("America/Chicago")))
      val ctx = ContextFeatures.evaluate(List(feature), sectionContext).sync()
      val rendered = ContextSections.render(ContextFeatures.sections(List(feature)), Placement.VolatileTail, ctx)
      rendered should include("Today is Sunday, March 15, 2026, 04:30 UTC.")
      rendered should not include "March 14"
    }

    "state the day, the date, and the time to the minute" in {
      CurrentDateFeature.render(Instant.parse("2026-03-14T15:09:00Z")) shouldBe expectedBlock
    }
  }

  "A moving clock" should {
    "change the volatile tail and leave the cacheable prefix byte-identical" in {
      def rendered(instant: String): (String, String) = {
        val feature = CurrentDateFeature(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
        val sections = ContextSections.all ++ ContextFeatures.sections(List(feature))
        val ctx = ContextFeatures.evaluate(List(feature), sectionContext).sync()
        (
          ContextSections.render(sections, Placement.StablePrefix, ctx),
          ContextSections.render(sections, Placement.VolatileTail, ctx))
      }
      val (prefixA, tailA) = rendered("2026-03-14T15:09:00Z")
      val (prefixB, tailB) = rendered("2026-03-15T09:41:00Z")
      prefixB shouldBe prefixA
      tailB should not be tailA
      tailB should include("Today is Sunday, March 15, 2026, 09:41 UTC.")
    }
  }

  private def sectionContext: SectionContext = {
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(),
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )
    SectionContext(
      request,
      ResolvedReferences(Vector.empty, Vector.empty, Vector.empty),
      discoveredCapabilitiesPromptCap = 25,
      now = Timestamp().value)
  }
}
