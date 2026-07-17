package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseContent, RespondInput}
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Sigil #394 (issue 2) — a progress checkpoint whose model can't produce a
 * report (a forced-tool_choice-incompatible model like Fable returns naked
 * text → `ConsultTool.invoke` = None) must NOT silently disable stall
 * detection. The `None` branch falls back to the objective `StallDetector`
 * verdict and fires the intervention anyway.
 *
 * The model-independent hard-stall is disabled here so the ONLY path to a
 * `_stall_detected` intervention is the checkpoint's None-fallback — i.e.
 * this fails pre-fix (None → silent continue) and passes post-fix.
 */
class CheckpointNoneFailsafeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProgressCheckpointInterval(2)
  TestSigil.setHardStallIdenticalCallLimit(0) // isolate the checkpoint path

  override protected def afterAll(): Unit = {
    TestSigil.resetProgressCheckpointInterval()
    TestSigil.resetHardStallIdenticalCallLimit()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "checkpoint-none")
  TestSigil.testModel(modelId)

  /**
   * reflector (report_progress roster) → NAKED TEXT (no tool → None);
   * forced-synthesis (Specific(respond)) → respond (terminal); otherwise →
   * identical `change_mode` so the loop iterates and stalls.
   */
  final private class NoneReflectorProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"call-${rapid.Unique()}")
      val emits: List[ProviderEvent] =
        if (input.tools.exists(_.name.value == "report_progress"))
          List(
            ProviderEvent.ContentBlockDelta(callId, "Reflecting — looks like progress to me."),
            ProviderEvent.Done(StopReason.Complete)
          )
        else if (input.toolChoice == ToolChoice.Specific(RespondTool.schema.name))
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(
              callId,
              RespondInput(topicLabel = "Done", topicSummary = "stopping", content = "Stopping — I was looping.", endsTurn = true)),
            ProviderEvent.Done(StopReason.Complete)
          )
        else
          List(
            ProviderEvent.ToolCallStart(callId, ChangeModeTool.schema.name.value),
            ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "coding")),
            ProviderEvent.Done(StopReason.ToolCall)
          )
      Stream.emits(emits)
    }
  }

  private def agent: AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  private def awaitStallOrDeadline(convId: Id[Conversation], deadline: Long): Task[List[sigil.event.Event]] =
    eventsFor(convId).flatMap { evs =>
      val sawStall = evs.exists { case ti: ToolInvoke => ti.toolName.value == "_stall_detected"; case _ => false }
      if (sawStall || System.currentTimeMillis() > deadline) Task.pure(evs)
      else Task.sleep(100.millis).flatMap(_ => awaitStallOrDeadline(convId, deadline))
    }

  "the progress checkpoint None-fallback (sigil #394)" should {
    "fire the stall intervention even when the reflector returned no report" in {
      TestSigil.setProvider(Task.pure(new NoneReflectorProvider))
      val convId = Conversation.id(s"checkpoint-none-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("Rename the theme.")),
          state =
            EventState.Complete
        ))
        evs <- awaitStallOrDeadline(convId, System.currentTimeMillis() + 30_000L)
      } yield {
        // The objective StallDetector caught the change_mode loop and the
        // None-fallback fired the intervention — despite every reflector call
        // returning None (pre-fix this never fired; the guard was off).
        val stallInvokes = evs.collect { case ti: ToolInvoke if ti.toolName.value == "_stall_detected" => ti }
        withClue(s"events: ${evs.map(_.getClass.getSimpleName).mkString(", ")}: ") {
          stallInvokes should not be empty
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
