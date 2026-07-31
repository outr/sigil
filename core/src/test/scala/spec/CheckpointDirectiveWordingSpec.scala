package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, MessageVisibility}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{AgentActivity, AgentStateDelta, EventState, Signal}
import sigil.tool.core.{CoreTools, FindCapabilityInput, FindCapabilityTool}
import sigil.tool.consult.ProgressReflectionInput
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import sigil.tool.consult.ProgressReflectionTool
import sigil.tool.core.RespondTool

/**
 * Regression for sigil #412 (re-file) — a checkpoint DIRECTIVE nudge (the
 * non-ask-user "change approach and continue" case) must not be worded as
 * a first-person question. When it was ("How would you like me to
 * proceed?"), an instruction-following model read the internal Tool-role
 * message as the USER challenging it and posted a user-visible "You're
 * right…" acknowledgment — a reply to a message the user never sent.
 *
 * The framework's contribution — the directive message it injects — is
 * what the fix controls, and is deterministically assertable: the directive
 * carries an explicit internal / do-not-acknowledge envelope and no
 * first-person question. The genuine ask-the-user checkpoint keeps its
 * user-facing wording (covered by CheckpointInterventionSourceSpec).
 */
class CheckpointDirectiveWordingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  TestSigil.testModel(modelId)

  // Checkpoint every iteration so a no-progress streak (limit 2) builds
  // quickly instead of waiting for the framework's default interval.
  TestSigil.setProgressCheckpointInterval(1)

  /** Three-shape stub:
    *   - Consult (roster is exactly `report_progress`): report NO
    *     meaningful progress and shouldAskUser = false → the checkpoint
    *     escalates to a DIRECTIVE nudge, not an ask-user one.
    *   - Forced synthesis (roster has `respond` but not `find_capability`):
    *     emit a terminal respond so the turn settles cleanly once the
    *     no-progress streak escalates to terminal.
    *   - Normal iteration: emit a non-terminal `find_capability` with
    *     FRESH keywords each turn (avoids the #159 repeated-query intercept)
    *     so the loop keeps reaching checkpoints. */
  private class StubProvider extends Provider {
    private val counter = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val roster = input.tools.iterator.map(_.name.value).toSet
      if (roster == Set("report_progress")) {
        val callId = CallId(s"consult-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, "report_progress"),
          ProviderEvent.toolCall(callId, ProgressReflectionTool)(ProgressReflectionInput(
            currentStatus      = "still reading files, no edits yet",
            meaningfulProgress = false,
            remainingSteps     = "keep going",
            stuckOn            = Some("the read-edit loop"),
            shouldAskUser      = false
          )),
          ProviderEvent.Done(StopReason.Complete)
        ))
      } else if (!roster.contains(FindCapabilityTool.name.value)) {
        // Forced-synthesis (respond-narrowed) roster — end the turn cleanly.
        val callId = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, "respond"),
          ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
            topicLabel   = TestTopicEntry.label,
            topicSummary = TestTopicEntry.summary,
            content      = "Wrapping up.",
            endsTurn     = true
          )),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      } else {
        val callId = CallId(s"agent-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, FindCapabilityTool.name.value),
          ProviderEvent.toolCall(callId, FindCapabilityTool)(FindCapabilityInput(keywords = s"topic-${counter.incrementAndGet()}")),
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
    val convId = Conversation.id(s"checkpoint-directive-${rapid.Unique()}")
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
             content        = Vector(ResponseContent.Text("Do the bulk sweep")),
             state          = EventState.Complete
           ))
      _ <- waitForSettle(System.currentTimeMillis() + 20_000L)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  private def textOf(m: Message): String =
    m.content.collect { case t: ResponseContent.Text => t.text }.mkString

  "A checkpoint directive nudge (sigil #412 re-file)" should {

    "be framework-voiced and non-conversational — no first-person question, explicit do-not-acknowledge" in {
      runScenario().map { signals =>
        // The directive reaches the agent as a Tool-role, Agents-visibility
        // message hidden from the user.
        val directives = signals.collect {
          case m: Message if m.participantId == TestAgent
                          && m.role == MessageRole.Tool
                          && m.visibility == MessageVisibility.Agents => m
        }.map(textOf).filter(_.contains("progress checkpoint"))
        withClue(s"directive texts=$directives: ") {
          directives should not be empty
          directives.foreach { text =>
            // (a) explicit internal / do-not-acknowledge framing
            text should include("do NOT reply to or acknowledge this in chat")
            text should include("Do not apologize")
            // (b) no first-person question for the model to "answer"
            text should not include "How would you like me to proceed?"
          }
          succeed
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in
      Task(TestSigil.resetProgressCheckpointInterval())
        .flatMap(_ => TestSigil.shutdown.map(_ => succeed))
  }
}
