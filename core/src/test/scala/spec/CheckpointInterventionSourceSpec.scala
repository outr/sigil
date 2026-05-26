package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.core.{CoreTools, FindCapabilityInput, FindCapabilityTool}
import sigil.tool.consult.ProgressReflectionInput
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Regression for sigil bug #284 — framework-synthesised intervention
 * messages MUST carry a `source` stamp so consumer UIs render them
 * distinctly from real agent replies (different chrome, framework
 * attribution).
 */
class CheckpointInterventionSourceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  TestSigil.testModel(modelId)

  // Make the checkpoint fire every iteration so the test doesn't have
  // to drive the agent through 15 (the framework default) before the
  // intervention path is exercised.
  TestSigil.setProgressCheckpointInterval(1)

  /** Two-shape stub provider:
    *   - Consult call (request carries exactly one tool, `report_progress`):
    *     emit a [[ProgressReflectionInput]] declaring shouldAskUser = true
    *     so the checkpoint produces an askingUser-flavoured intervention.
    *   - Agent's iteration (any other roster shape): emit a
    *     non-terminal `find_capability` call so the loop continues to
    *     the post-drain checkpoint trigger. */
  private class StubProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val toolNames = input.tools.iterator.map(_.name.value).toSet
      if (toolNames == Set("report_progress")) {
        val callId = CallId("consult-call")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, "report_progress"),
          ProviderEvent.ToolCallComplete(callId, ProgressReflectionInput(
            currentStatus      = "stuck on the bsp_list_targets loop",
            meaningfulProgress = false,
            remainingSteps     = "ask user for clarification",
            stuckOn            = Some("bsp_list_targets returns no results"),
            shouldAskUser      = true
          )),
          ProviderEvent.Done(StopReason.Complete)
        ))
      } else {
        val callId = CallId(s"agent-call-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, FindCapabilityTool.name.value),
          ProviderEvent.ToolCallComplete(callId, FindCapabilityInput(keywords = "sleep wait delay")),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      }
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runScenario(): Task[List[Signal]] = {
    TestSigil.setProvider(Task.pure(new StubProvider))
    val convId = Conversation.id(s"checkpoint-source-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    def hasIdle: Boolean =
      recorded.iterator().asScala.exists {
        case d: AgentStateDelta
          if d.activity.contains(AgentActivity.Idle) && d.state.contains(EventState.Complete) => true
        case _ => false
      }
    def waitForSettle(deadline: Long): Task[Unit] =
      if (hasIdle || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(50.millis).flatMap(_ => waitForSettle(deadline))

    for {
      _ <- Task.sleep(100.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Do the thing")),
             state          = EventState.Complete
           ))
      _ <- waitForSettle(System.currentTimeMillis() + 15_000L)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "Sigil bug #284 — framework intervention source stamping" should {

    "stamp source = 'orchestrator-intervention' on the askingUser checkpoint Message" in {
      runScenario().map { signals =>
        val agentMessages = signals.collect {
          case m: Message if m.participantId == TestAgent => m
        }
        val interventionMessages = agentMessages.filter(_.source.contains("orchestrator-intervention"))
        withClue(
          s"agent messages observed: ${agentMessages.map(m => (m.source, m.role, m.content.headOption))}"
        ) {
          interventionMessages should not be empty
        }
        val body = interventionMessages.head.content.collect {
          case t: ResponseContent.Text => t.text
        }.mkString
        body should include ("clarification")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in
      Task(TestSigil.resetProgressCheckpointInterval())
        .flatMap(_ => TestSigil.shutdown.map(_ => succeed))
  }
}
