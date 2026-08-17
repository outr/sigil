package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, Topic}
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The framework renders no clock, so a model asked what day it is has
 * nothing to read and answers from recall — confidently, and wrong.
 * This spec pins the absence: a real turn's rendered request carries
 * today's date in no form anywhere in the system prompt.
 */
class CurrentDateFeatureSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "current-date")
  TestSigil.testModel(modelId)

  private final class CapturingProvider extends Provider {
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
          topicLabel   = TestTopicEntry.label,
          topicSummary = TestTopicEntry.summary,
          content      = "ok",
          endsTurn     = true
        )),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private val provider = new CapturingProvider

  private val convId = Conversation.id(s"current-date-${rapid.Unique()}")

  private val topic = Topic(
    conversationId = convId,
    label          = TestTopicEntry.label,
    summary        = TestTopicEntry.summary,
    createdBy      = TestUser,
    _id            = Id[Topic](s"current-date-topic-${rapid.Unique()}")
  )

  private def agent: AgentParticipant = DefaultAgentParticipant(
    id                 = TestAgent,
    modelId            = modelId,
    toolNames          = CoreTools.coreToolNames,
    instructions       = Instructions(),
    generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
  )

  private def waitFor(deadline: Long)(pred: => Boolean): Task[Unit] =
    if (pred || System.currentTimeMillis() > deadline) Task.unit
    else Task.sleep(50.millis).flatMap(_ => waitFor(deadline)(pred))

  private def idleCount(snap: List[Signal]): Int =
    snap.count {
      case d: AgentStateDelta => d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete)
      case _                  => false
    }

  private lazy val runTurn: Task[ProviderCall] = {
    TestSigil.reset()
    TestSigil.setProvider(Task.pure(provider))
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new AtomicBoolean(true)
    TestSigil.signals.takeWhile(_ => running.get()).evalMap(s => Task { recorded.add(s); () }).drain.startUnit()
    val conv = Conversation(
      topics       = List(sigil.conversation.TopicEntry(topic._id, topic.label, topic.summary)),
      participants = List(agent),
      _id          = convId
    )
    for {
      _ <- Task.sleep(120.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = topic._id,
             content        = Vector(ResponseContent.Text("what is today's date?")),
             state          = EventState.Complete
           ))
      _ <- waitFor(System.currentTimeMillis() + 30_000L)(idleCount(recorded.iterator().asScala.toList) > 0)
      _ <- Task { running.set(false) }
    } yield provider.calls.iterator().asScala.toList.headOption.getOrElse(
      throw new IllegalStateException("no provider call captured"))
  }.singleton

  /** Every spelling of today a model could recognise as the date. */
  private def todaySpellings: List[String] = {
    val utc = Instant.now().atZone(ZoneOffset.UTC)
    List(
      utc.toLocalDate.toString,
      DateTimeFormatter.ofPattern("EEEE", Locale.US).format(utc),
      DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US).format(utc),
      DateTimeFormatter.ofPattern("MMMM", Locale.US).format(utc) + " " + utc.getDayOfMonth,
      utc.getYear.toString
    )
  }

  "A rendered request today" should {
    "carry no current date anywhere in the system prompt" in {
      runTurn.map { call =>
        val rendered = call.systemCombined
        withClue(s"rendered system prompt:\n$rendered\n") {
          todaySpellings.foreach(spelling => rendered should not include spelling)
          succeed
        }
      }
    }
  }
}
