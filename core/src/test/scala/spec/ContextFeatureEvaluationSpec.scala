package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, Topic}
import sigil.db.Model
import sigil.diagnostics.ProfileSection
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.*
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal, WireRequestProfile}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A feature may consult a live source, and several consumers render the
 * same request — the renderer for the wire bytes, the profiler for the
 * breakdown notice. The pipeline therefore computes each feature once
 * per request and hands both the same result; a feature that ran per
 * reader would triple a live lookup's cost every turn.
 */
class ContextFeatureEvaluationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "feature-evaluation")
  TestSigil.testModel(modelId)

  private val featureId = FeatureId("liveStatus")

  final private class CountingFeature extends ContextFeature {
    val calls = new AtomicInteger(0)
    val id: FeatureId = featureId
    def placement: Placement = Placement.VolatileTail
    def compute(ctx: SectionContext): Task[List[FeatureBody]] = Task {
      calls.incrementAndGet()
      List(FeatureBody.prose("\n== Live status ==\nThe upstream service is reachable.\n"))
    }
  }

  private val feature = new CountingFeature

  final private class CapturingProvider extends Provider {
    val calls = new ConcurrentLinkedQueue[ProviderCall]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.add(input)
      val cid = CallId(s"feature-${rapid.Unique()}")
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

  private val convId = Conversation.id(s"feature-eval-${rapid.Unique()}")

  private val topic = Topic(
    conversationId = convId,
    label = TestTopicEntry.label,
    summary = TestTopicEntry.summary,
    createdBy = TestUser,
    _id = Id[Topic](s"feature-eval-topic-${rapid.Unique()}")
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

  private lazy val runTurn: Task[(ProviderCall, List[Signal])] = {
    TestSigil.reset()
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setContextFeatures(List(feature))
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
        content = Vector(ResponseContent.Text("hello")),
        state = EventState.Complete
      ))
      _ <- waitFor(System.currentTimeMillis() + 30_000L)(idleCount(recorded.iterator().asScala.toList) > 0)
      _ <- Task.sleep(200.millis)
      _ <- Task(running.set(false))
    } yield (
      provider.calls.iterator().asScala.toList.headOption.getOrElse(
        throw new IllegalStateException("no provider call captured")),
      recorded.iterator().asScala.toList
    )
  }.singleton

  "A Task-effectful feature on a live turn" should {
    "reach the wire bytes" in
      runTurn.map { case (call, _) =>
        call.systemVolatile should include("The upstream service is reachable.")
        call.system should not include "The upstream service is reachable."
      }

    "compute once per request, not once per consumer" in
      runTurn.map { case (_, _) =>
        feature.calls.get() shouldBe provider.calls.size()
      }

    "appear in the wire profile under its own feature id" in
      runTurn.map { case (_, signals) =>
        val profiles = signals.collect { case p: WireRequestProfile => p }
        profiles should not be empty
        profiles.head.profile.sections.getOrElse(ProfileSection.Feature(featureId), 0) should be > 0
      }
  }
}
