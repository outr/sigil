package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, ProgressCheckpoint}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Regression for sigil bug #282 — when the agent loop ends without a
 * user-visible `respond`, the failure surface must carry the agent's
 * most-recently-reported [[ProgressCheckpoint]] status so the UI shows
 * something actionable instead of a still-spinning indicator that the
 * user has to give up on.
 */
class MeaningfulProgressTerminationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  TestSigil.testModel(modelId)

  /**
   * Provider that always emits Done with NO tool call — the
   * orchestrator treats this as an empty-response turn. After the
   * no-tool-call retry budget exhausts and the forced-synthesis
   * recovery still doesn't produce a respond, the loop raises
   * AgentRunawayException and handleError publishes a Failure
   * Message.
   */
  private class EmptyResponseProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emit(ProviderEvent.Done(StopReason.Complete))
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  /**
   * Drive a single user turn against [[EmptyResponseProvider]] and
   * — once iter=1's drain done lands — publish a ProgressCheckpoint
   * carrying the test status text into the agent's conversation
   * history. The publishFailureMessage path then picks it up.
   */
  private def runScenarioWithCheckpoint(statusText: String,
                                        stuckOn: Option[String]): Task[List[Signal]] = {
    TestSigil.setProvider(Task.pure(new EmptyResponseProvider))
    val convId = Conversation.id(s"meaningful-progress-${rapid.Unique()}")
    val agent = makeAgent()
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    def hasFailure: Boolean =
      recorded.iterator().asScala.exists {
        case m: Message if m.participantId == TestAgent && m.isFailure => true
        case _ => false
      }
    def hasIdle: Boolean =
      recorded.iterator().asScala.exists {
        case d: AgentStateDelta
            if d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete) => true
        case _ => false
      }
    def settled: Boolean = hasFailure && hasIdle

    def waitForSettle(deadline: Long): Task[Unit] =
      if (settled || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(50.millis).flatMap(_ => waitForSettle(deadline))

    for {
      _ <- Task.sleep(100.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Do the thing")),
        state = EventState.Complete
      ))
      // Pre-publish a ProgressCheckpoint into the conversation — mirrors what
      // [[Sigil.runProgressCheckpoint]] writes at iteration boundaries.
      _ <- TestSigil.publish(ProgressCheckpoint(
        participantId = TestAgent,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        iterationCount = 1,
        prevCheckpointStatus = None,
        currentStatus = statusText,
        meaningfulProgress = false,
        remainingSteps = "",
        stuckOn = stuckOn,
        shouldAskUser = false
      ))
      _ <- waitForSettle(System.currentTimeMillis() + 15_000L)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "Sigil bug #282 — agent loop silent-termination guard" should {
    "include the most recent ProgressCheckpoint's status text in the failure body" in
      runScenarioWithCheckpoint(
        statusText = "Stuck on a placeholder task with no progress",
        stuckOn = Some("bsp_list_targets returns no results")
      ).map { signals =>
        val failures = signals.collect {
          case m: Message if m.participantId == TestAgent && m.isFailure => m
        }
        failures should not be empty
        val combinedBody = failures.flatMap(_.content.collect {
          case t: ResponseContent.Text => t.text
        }).mkString("\n")
        // The agent's reported status anchors the failure for the user.
        combinedBody should include("Stuck on a placeholder task")
        // And the stuck-on rationale is surfaced too — pre-fix the user
        // only saw the technical exception class.
        combinedBody should include("bsp_list_targets returns no results")
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
