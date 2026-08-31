package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.SpaceId
import sigil.conversation.{ContextMemory, Conversation, MemorySource, Topic, TurnInput}
import sigil.db.Model
import sigil.event.{Message, TopicChange, TopicChangeKind}
import sigil.information.{Information, InformationSummary, StoredInformation}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant, ParticipantId}
import sigil.provider.{
  CallId,
  GenerationSettings,
  Instructions,
  Provider,
  ProviderCall,
  ProviderEvent,
  ProviderType,
  StopReason
}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Cross-turn prompt caching keys on the longest common BYTE prefix of two
 * consecutive requests. Sigil's wire ordering already places the volatile
 * tail behind the message history, but the leading stable system prompt is
 * only worth caching if it is byte-identical from one ordinary turn to the
 * next.
 *
 * These specs drive real turns through a capturing stub provider and pin
 * the property: between turn N and turn N+1 the conversation's topic is
 * renamed, its Information catalog grows, and a new user message lands —
 * none of which may alter the stable half. A pin/unpin is the deliberate
 * exception and MUST alter it, which is what makes the first assertion a
 * real constraint rather than a frozen constant.
 */
class CrossTurnPrefixStabilitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "cross-turn-prefix")
  TestSigil.testModel(modelId)

  /**
   * The label the stub proposes on every respond — kept equal to the
   * conversation's live topic label so `resolveTopicShift` short-circuits
   * and the agent's own reply never drives topic churn. Topic churn in
   * this spec comes from explicit framework `TopicChange` publishes.
   */
  private val proposedLabel = new AtomicReference[String](TestTopicEntry.label)

  /**
   * Captures every [[ProviderCall]] the framework's translation pass
   * produces, then answers with a turn-ending `respond`.
   */
  final private class CapturingProvider extends Provider {
    val calls = new ConcurrentLinkedQueue[ProviderCall]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.add(input)
      val cid = CallId(s"xturn-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
        ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
          topicLabel = proposedLabel.get(),
          topicSummary = "unchanged",
          content = "ok",
          endsTurn = true
        )),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private val provider = new CapturingProvider

  /**
   * Information entries the curate hook injects — grows across turns.
   */
  private val informationCount = new AtomicInteger(0)

  /**
   * Ids of Critical memories the curate hook pins — the positive control.
   */
  private val pinned = new AtomicReference[Vector[Id[ContextMemory]]](Vector.empty)

  private def installCurate(): Unit =
    TestSigil.setCurate { (convId, mid, chain) =>
      sigil.conversation.compression.StandardContextCurator(TestSigil).curate(convId, mid, chain).map { t =>
        t.copy(
          information = (1 to informationCount.get()).toVector.map { i =>
            InformationSummary(
              id = Id[Information](s"xturn-info-$i"),
              informationType = Information.name.of[StoredInformation],
              summary = s"Reference document number $i"
            )
          },
          criticalMemories = t.criticalMemories ++ pinned.get()
        )
      }
    }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def startRecorder(): (ConcurrentLinkedQueue[Signal], java.util.concurrent.atomic.AtomicBoolean) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new java.util.concurrent.atomic.AtomicBoolean(true)
    TestSigil.signals.takeWhile(_ => running.get()).evalMap(s => Task { recorded.add(s); () }).drain.startUnit()
    (recorded, running)
  }

  private def waitFor(deadline: Long)(pred: => Boolean): Task[Unit] =
    if (pred || System.currentTimeMillis() > deadline) Task.unit
    else Task.sleep(50.millis).flatMap(_ => waitFor(deadline)(pred))

  private def idleCount(snap: List[Signal]): Int =
    snap.count {
      case d: AgentStateDelta => d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete)
      case _ => false
    }

  private val convId = Conversation.id(s"xturn-prefix-${rapid.Unique()}")

  private val seedTopic = Topic(
    conversationId = convId,
    label = TestTopicEntry.label,
    summary = TestTopicEntry.summary,
    createdBy = TestUser,
    _id = Id[Topic](s"xturn-topic-${rapid.Unique()}")
  )

  private val secondTopic = Topic(
    conversationId = convId,
    label = "Second subject under discussion",
    summary = "The conversation moved on to an unrelated subject.",
    createdBy = TestUser,
    _id = Id[Topic](s"xturn-topic2-${rapid.Unique()}")
  )

  private val liveTopicId = new AtomicReference[Id[Topic]](seedTopic._id)

  private val toolsConfig = OpenAIChatCompletions.Config(providerNamespace = "test", providerName = "Test")

  private def rosterBytes(call: ProviderCall): String =
    fabric.io.JsonFormatter.Compact(fabric.arr(OpenAIChatCompletions.renderTools(call, TestSigil, toolsConfig)*))

  /**
   * Publish a user Message and wait for the agent turn to settle.
   */
  private def turn(text: String,
                   recorded: ConcurrentLinkedQueue[Signal],
                   priorIdles: Int): Task[Unit] =
    TestSigil.publish(Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = liveTopicId.get(),
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete
    )).flatMap { _ =>
      waitFor(System.currentTimeMillis() + 30_000L)(idleCount(recorded.iterator().asScala.toList) > priorIdles)
    }.flatMap(_ => Task.sleep(200.millis))

  /**
   * Rename the live topic the way the framework does: rewrite the Topic
   * record, then publish the settled `TopicChange` that reprojects the
   * conversation's topic stack.
   */
  private def renameTopic(label: String, summary: String): Task[Unit] =
    TestSigil.withDB(_.topics.transaction(_.upsert(seedTopic.copy(label = label, summary = summary)))).flatMap { _ =>
      TestSigil.publish(TopicChange(
        kind = TopicChangeKind.Rename(previousLabel = proposedLabel.get()),
        newLabel = label,
        newSummary = summary,
        participantId = TestAgent,
        conversationId = convId,
        topicId = seedTopic._id,
        state = EventState.Complete
      ))
    }.flatMap(_ => Task(proposedLabel.set(label)))
      .flatMap(_ => Task.sleep(150.millis))

  /**
   * Open a second subject — pushes the renamed topic onto the stack's
   * prior entries, which is what grows the `Previous topics` section.
   */
  private def switchTopic(): Task[Unit] =
    TestSigil.withDB(_.topics.transaction(_.upsert(secondTopic))).flatMap { _ =>
      TestSigil.publish(TopicChange(
        kind = TopicChangeKind.Switch(previousTopicId = seedTopic._id),
        newLabel = secondTopic.label,
        newSummary = secondTopic.summary,
        participantId = TestAgent,
        conversationId = convId,
        topicId = secondTopic._id,
        state = EventState.Complete
      ))
    }.flatMap(_ =>
      Task {
        proposedLabel.set(secondTopic.label)
        liveTopicId.set(secondTopic._id)
      }).flatMap(_ => Task.sleep(150.millis))

  private lazy val runTurns: Task[List[ProviderCall]] = {
    TestSigil.reset()
    TestSigil.setProvider(Task.pure(provider))
    installCurate()
    val (recorded, running) = startRecorder()
    val agent = makeAgent()
    val conv = Conversation(
      topics = List(sigil.conversation.TopicEntry(seedTopic._id, seedTopic.label, seedTopic.summary)),
      participants = List(agent),
      _id = convId
    )
    for {
      _ <- Task.sleep(120.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(seedTopic)))
      _ <- Task(informationCount.set(1))
      _ <- turn("first user message", recorded, priorIdles = 0)
      // --- cache-irrelevant churn between turn 1 and turn 2 ---
      _ <- renameTopic("Prefix stability under churn", "The topic label and summary moved on.")
      _ <- switchTopic()
      _ <- Task(informationCount.set(3))
      _ <- turn("second user message", recorded, priorIdles = 1)
      // --- deliberate pin between turn 2 and turn 3 (positive control) ---
      m <- TestSigil.persistMemory(ContextMemory(
        fact = "The operator prefers metric units in every reply.",
        label = "units",
        summary = "Prefers metric units.",
        source = MemorySource.Explicit,
        pinned = true,
        spaceId = TestSpace
      ))
      _ <- Task(pinned.set(Vector(m._id)))
      _ <- turn("third user message", recorded, priorIdles = 2)
      _ <- Task(running.set(false))
    } yield provider.calls.iterator().asScala.toList
  }.singleton

  "Cross-turn stable-prefix stability" should {

    "keep the stable system prompt byte-identical across consecutive ordinary turns" in
      runTurns.map { calls =>
        calls.size should be >= 2
        val first = calls.head
        val second = calls(1)
        withClue(
          s"""stable system diverged between turn 1 and turn 2.
             |--- turn 1 ---
             |${first.system}
             |--- turn 2 ---
             |${second.system}
             |""".stripMargin) {
          second.system shouldBe first.system
        }
      }

    "keep the rendered tool roster byte-identical across consecutive ordinary turns" in
      runTurns.map { calls =>
        rosterBytes(calls(1)) shouldBe rosterBytes(calls.head)
      }

    "carry the current topic in the volatile tail, never in the stable prefix" in
      runTurns.map { calls =>
        val second = calls(1)
        second.system should not include secondTopic.label
        second.systemVolatile should include(secondTopic.label)
      }

    "carry the accumulated prior topics in the volatile tail, never in the stable prefix" in
      runTurns.map { calls =>
        val second = calls(1)
        second.system should not include "Prefix stability under churn"
        second.systemVolatile should include("Prefix stability under churn")
      }

    "carry the accrued Information catalog in the volatile tail, never in the stable prefix" in
      runTurns.map { calls =>
        val second = calls(1)
        second.system should not include "Reference document number 3"
        second.systemVolatile should include("Reference document number 3")
      }

    "let a deliberate pin change the stable prefix" in
      runTurns.map { calls =>
        calls.size should be >= 3
        val third = calls(2)
        third.system should include("Prefers metric units.")
        third.system should not be calls(1).system
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
