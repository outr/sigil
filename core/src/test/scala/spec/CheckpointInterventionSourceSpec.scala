package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, MessageVisibility, ToolInvoke}
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
 * Regression for sigil bug #353 (which supersedes #284's askingUser
 * behaviour). A checkpoint `askingUser` intervention in a MAIN conversation
 * must NOT be published as a user-visible "I need clarification" Message in
 * the agent's voice followed by an idle dead-end. It is routed to the AGENT
 * — a synthetic `_stall_detected` invoke + a Tool-role / Agents-visibility
 * directive + one forced synthesis — so the agent decides whether to
 * continue or ask the user itself via `respond` / `respond_options`.
 */
class CheckpointInterventionSourceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  TestSigil.testModel(modelId)

  // Make the checkpoint fire every iteration so the test doesn't have
  // to drive the agent through 15 (the framework default) before the
  // intervention path is exercised.
  TestSigil.setProgressCheckpointInterval(1)

  /**
   * Two-shape stub provider:
   *   - Consult call (request carries exactly one tool, `report_progress`):
   *     emit a [[ProgressReflectionInput]] declaring shouldAskUser = true
   *     so the checkpoint produces an askingUser-flavoured intervention.
   *   - Agent's iteration (any other roster shape): emit a
   *     non-terminal `find_capability` call so the loop continues to
   *     the post-drain checkpoint trigger.
   */
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
          ProviderEvent.ToolCallComplete(
            callId,
            ProgressReflectionInput(
              currentStatus = "stuck on the bsp_list_targets loop",
              meaningfulProgress = false,
              remainingSteps = "ask user for clarification",
              stuckOn = Some("bsp_list_targets returns no results"),
              shouldAskUser = true
            )
          ),
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
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def runScenario(): Task[List[Signal]] = {
    TestSigil.setProvider(Task.pure(new StubProvider))
    val convId = Conversation.id(s"checkpoint-source-${rapid.Unique()}")
    val agent = makeAgent()
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new atomic.AtomicBoolean(true)
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
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Do the thing")),
        state = EventState.Complete
      ))
      _ <- waitForSettle(System.currentTimeMillis() + 15_000L)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "Sigil bug #353 — askingUser checkpoint routes to the agent, not the user" should {

    "publish NO user-visible orchestrator-intervention Message, but a Tool-role directive under a _stall_detected invoke" in
      runScenario().map { signals =>
        val agentMessages = signals.collect { case m: Message if m.participantId == TestAgent => m }
        // #353 — the framework no longer impersonates the agent toward the
        // user with a synthesized "I need clarification" Standard message.
        val userVisibleIntervention = agentMessages.filter(_.source.contains("orchestrator-intervention"))
        // Instead it routes to the agent: a synthetic _stall_detected invoke
        // plus a Tool-role, Agents-visibility directive (origin = that invoke).
        val stallInvokes = signals.collect {
          case ti: ToolInvoke if ti.toolName.value == "_stall_detected" => ti
        }
        val directives = agentMessages.filter(m => m.role == MessageRole.Tool && m.visibility == MessageVisibility.Agents)
        withClue(
          s"userVisibleIntervention=${userVisibleIntervention.size}, stallInvokes=${stallInvokes.size}, " +
            s"directives=${directives.size}, msgs=${agentMessages.map(m => (m.source, m.role))}: "
        ) {
          userVisibleIntervention shouldBe empty
          stallInvokes should not be empty
          directives should not be empty
        }
      }
  }

  "tear down" should {
    "dispose TestSigil" in
      Task(TestSigil.resetProgressCheckpointInterval())
        .flatMap(_ => TestSigil.shutdown.map(_ => succeed))
  }
}
