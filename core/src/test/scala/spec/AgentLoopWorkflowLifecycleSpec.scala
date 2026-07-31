package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{AgentStateDelta, EventState, FrameworkWorkflowNotice, FrameworkWorkflowPhase, Signal}
import sigil.signal.AgentActivity
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Sigil #313 — the `agent-loop` framework-workflow lifecycle must reach
 * a terminal phase on every exit. Pre-fix the wrap published
 * `Started` but no `Completed`/`Failed`, so consumers tracking active
 * framework workflows by `workflowId` leaked the entry and rendered a
 * forever-ticking activity row after the turn ended.
 */
class AgentLoopWorkflowLifecycleSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "agentloop-lifecycle")
  TestSigil.testModel(modelId)

  private final class RespondProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(s"resp-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
        ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
          topicLabel   = "Reply",
          topicSummary = "agent loop lifecycle reply",
          content      = "Done.",
          endsTurn     = true
        )),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private final class ErrorProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.force(Task.error(new RuntimeException("provider boom — agent loop must still terminate its workflow")))
  }

  private def makeAgent(id: sigil.participant.AgentParticipantId): AgentParticipant =
    DefaultAgentParticipant(
      id                 = id,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def startRecorder(): (ConcurrentLinkedQueue[Signal], atomic.AtomicBoolean) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals.takeWhile(_ => running.get()).evalMap(s => Task { recorded.add(s); () }).drain.startUnit()
    (recorded, running)
  }

  private def waitFor(deadline: Long)(pred: => Boolean): Task[Unit] =
    if (pred || System.currentTimeMillis() > deadline) Task.unit
    else Task.sleep(50.millis).flatMap(_ => waitFor(deadline)(pred))

  private def agentLoopNotices(snap: List[Signal]): List[FrameworkWorkflowNotice] =
    snap.collect { case n: FrameworkWorkflowNotice if n.workflowType == "agent-loop" => n }

  private def isStarted(n: FrameworkWorkflowNotice): Boolean = n.phase match {
    case _: FrameworkWorkflowPhase.Started => true
    case _                                 => false
  }
  private def isTerminal(n: FrameworkWorkflowNotice): Boolean = n.phase match {
    case _: FrameworkWorkflowPhase.Completed => true
    case _: FrameworkWorkflowPhase.Failed    => true
    case _                                   => false
  }

  private def idleSeen(snap: List[Signal], agentId: sigil.participant.AgentParticipantId): Boolean =
    snap.exists {
      case d: AgentStateDelta => d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete)
      case _                  => false
    }

  "Sigil #313 — agent-loop framework-workflow lifecycle" should {

    "publish a terminal Completed notice for a successful agent loop" in {
      TestSigil.reset()
      val convId = Conversation.id(s"agentloop-success-${rapid.Unique()}")
      val agent  = makeAgent(TestAgent)
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      TestSigil.setProvider(Task.pure(new RespondProvider))
      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(120.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser, conversationId = convId, topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("hello")), state = EventState.Complete
        ))
        _ <- waitFor(System.currentTimeMillis() + 20_000L)(idleSeen(recorded.iterator().asScala.toList, agent.id))
        // Settle window: the terminal notice fires on the loop's exit path.
        _ <- Task.sleep(300.millis)
      } yield recorded

      pipeline.map { rec =>
        running.set(false)
        val notices = agentLoopNotices(rec.iterator().asScala.toList)
        val started = notices.filter(isStarted)
        started should not be empty
        val startedId = started.head.workflowId
        withClue(s"agent-loop notices: ${notices.map(n => s"${n.workflowId.take(8)}:${n.phase}").mkString(", ")}") {
          notices.exists(n => n.workflowId == startedId && isTerminal(n)) shouldBe true
        }
      }
    }

    "publish a terminal Failed notice when the agent loop errors" in {
      TestSigil.reset()
      val convId = Conversation.id(s"agentloop-error-${rapid.Unique()}")
      val agent  = makeAgent(AgentLoopErrorAgent)
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      TestSigil.setProvider(Task.pure(new ErrorProvider))
      val (recorded, running) = startRecorder()

      val pipeline = for {
        _ <- Task.sleep(120.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser, conversationId = convId, topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("hello")), state = EventState.Complete
        ))
        _ <- waitFor(System.currentTimeMillis() + 20_000L)(
          agentLoopNotices(recorded.iterator().asScala.toList).exists(isTerminal)
        )
        _ <- Task.sleep(200.millis)
      } yield recorded

      pipeline.map { rec =>
        running.set(false)
        val notices = agentLoopNotices(rec.iterator().asScala.toList)
        val started = notices.filter(isStarted)
        started should not be empty
        withClue(s"agent-loop notices: ${notices.map(n => s"${n.workflowId.take(8)}:${n.phase}").mkString(", ")}") {
          notices.exists(n => n.workflowId == started.head.workflowId && isTerminal(n)) shouldBe true
        }
      }
    }

    "leave no orphaned Started across three sequential turns" in {
      TestSigil.reset()
      val convId = Conversation.id(s"agentloop-multi-${rapid.Unique()}")
      val agent  = makeAgent(AgentLoopMultiAgent)
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      TestSigil.setProvider(Task.pure(new RespondProvider))
      val (recorded, running) = startRecorder()

      def turn(n: Int): Task[Unit] = for {
        _ <- TestSigil.publish(Message(
          participantId = TestUser, conversationId = convId, topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text(s"turn $n")), state = EventState.Complete
        ))
        _ <- waitFor(System.currentTimeMillis() + 20_000L)(
          agentLoopNotices(recorded.iterator().asScala.toList).count(isTerminal) >= n
        )
      } yield ()

      val pipeline = for {
        _ <- Task.sleep(120.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- turn(1)
        _ <- turn(2)
        _ <- turn(3)
        _ <- Task.sleep(300.millis)
      } yield recorded

      pipeline.map { rec =>
        running.set(false)
        val notices  = agentLoopNotices(rec.iterator().asScala.toList)
        val starts   = notices.filter(isStarted)
        starts.size should be >= 3
        val orphans = starts.filterNot(s => notices.exists(n => n.workflowId == s.workflowId && isTerminal(n)))
        withClue(s"orphaned Starteds: ${orphans.map(_.workflowId.take(8)).mkString(", ")}; " +
          s"all: ${notices.map(n => s"${n.workflowId.take(8)}:${n.phase}").mkString(", ")}") {
          orphans shouldBe empty
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

case object AgentLoopErrorAgent extends sigil.participant.AgentParticipantId {
  override val value: String = "agentloop-error-agent"
}
case object AgentLoopMultiAgent extends sigil.participant.AgentParticipantId {
  override val value: String = "agentloop-multi-agent"
}
