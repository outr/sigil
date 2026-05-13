package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.AgentRunawayException
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*

/**
 * Coverage for [[sigil.AgentRunawayException]]'s cap-hit
 * soft-stop (bug #125). When the iteration cap fires, the
 * framework first injects a "respond NOW" diagnostic and runs ONE
 * more iteration with `tool_choice: respond` so the model
 * synthesises a reply from gathered context. Only if THAT
 * iteration also fails does AgentRunawayException propagate.
 */
class IterationCapForcesSynthesisSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with org.scalatest.BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  // Tight cap so the soft-stop fires quickly in test.
  TestSigil.setMaxAgentIterations(3)

  override protected def afterAll(): Unit = {
    TestSigil.resetMaxAgentIterations()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "cap-soft-stop")

  /** Records every call's tool_choice value (for the spec's
    * "forced turn pins respond" assertion) and the running call
    * count (for the "always emits change_mode" provider below).
    */
  private final class CallRecorder {
    val toolChoices: atomic.AtomicReference[Vector[ToolChoice]] =
      new atomic.AtomicReference(Vector.empty)
    val callCount: atomic.AtomicInteger = new atomic.AtomicInteger(0)
    def record(input: ProviderCall): Unit = {
      callCount.incrementAndGet()
      toolChoices.updateAndGet(prev => prev :+ input.toolChoice)
      ()
    }
  }

  /** Always emits a non-terminal `change_mode` tool call —
    * forces the loop to iterate forever until the cap fires.
    * On the FORCED-synthesis iteration (signalled by
    * `toolChoice = Specific(respond)`), emits respond instead so
    * the cap-hit soft-stop completes successfully. */
  private final class CompliantOnForceProvider(recorder: CallRecorder) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      recorder.record(input)
      val n = recorder.callCount.get()
      val callId = CallId(s"call-$n")
      val emits: List[ProviderEvent] = input.toolChoice match {
        case ToolChoice.Specific(name) if name == RespondTool.schema.name =>
          // Forced-synthesis turn — comply with the pin.
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(
              callId,
              RespondInput(topicLabel = "Cap", topicSummary = "cap-hit synth", content = "synthesized from gathered context", endsTurn = true)
            ),
            ProviderEvent.Done(StopReason.Complete)
          )
        case _ =>
          List(
            ProviderEvent.ToolCallStart(callId, ChangeModeTool.schema.name.value),
            ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "conversation")),
            ProviderEvent.Done(StopReason.ToolCall)
          )
      }
      Stream.emits(emits)
    }
  }

  /** Even on the forced-synthesis turn, refuses to call respond
    * — keeps emitting change_mode. Drives the hard-throw
    * fallback path. */
  private final class StubbornProvider(recorder: CallRecorder) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      recorder.record(input)
      val n = recorder.callCount.get()
      val callId = CallId(s"stubborn-$n")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, ChangeModeTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "conversation")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  /** Drive the agent loop to cap-hit. Returns the (recorder,
    * outcome) — outcome is `Right(())` for a soft-stop success or
    * `Left(throwable)` for the hard-throw fallback. */
  private def runScenario(provider: CallRecorder => Provider): Task[(CallRecorder, Either[Throwable, Unit])] = {
    val recorder = new CallRecorder
    TestSigil.setProvider(Task.pure(provider(recorder)))
    val convId = Conversation.id(s"cap-soft-stop-${rapid.Unique()}")
    val agent  = makeAgent()
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("Do something")),
             state          = EventState.Complete
           ))
      _ <- Task.sleep(2.seconds)
      // Inspect SigilDB.events to find what landed.
      _ <- Task.unit
    } yield (recorder, Right(()))  // The agent loop runs on a background fiber; we observe via events below.
  }

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  "Iteration cap soft-stop (sigil bug #125)" should {

    "force a respond synthesis on cap-hit rather than throwing AgentRunawayException" in {
      val recorder = new CallRecorder
      TestSigil.setProvider(Task.pure(new CompliantOnForceProvider(recorder)))
      val convId = Conversation.id(s"cap-compliant-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Do something")),
               state          = EventState.Complete
             ))
        _   <- Task.sleep(3.seconds)
        evs <- eventsFor(convId)
      } yield {
        // The forced-synthesis turn ran exactly one call with the
        // respond pin; the synthesised respond Message lands.
        val forcedChoices = recorder.toolChoices.get().collect {
          case s: ToolChoice.Specific => s.toolName.value
        }
        forcedChoices should contain (RespondTool.schema.name.value)

        // The synthesised respond produced a Message authored by the agent.
        val agentMessages = evs.collect {
          case m: Message if m.participantId == TestAgent => m
        }
        withClue(s"events: ${evs.map(e => s"${e.getClass.getSimpleName}").mkString(", ")}: ") {
          agentMessages should not be empty
        }
        val texts = agentMessages.flatMap(_.content.collect {
          case ResponseContent.Text(t)     => t
          case ResponseContent.Markdown(t) => t
        })
        texts.mkString(" ") should include ("synthesized")
      }
    }

    "inject the cap-reached diagnostic Tool-role Message before the forced turn" in {
      val recorder = new CallRecorder
      TestSigil.setProvider(Task.pure(new CompliantOnForceProvider(recorder)))
      val convId = Conversation.id(s"cap-diagnostic-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = TestTopicEntry.id,
                 content        = Vector(ResponseContent.Text("Do something")),
                 state          = EventState.Complete
               ))
        _   <- Task.sleep(3.seconds)
        evs <- eventsFor(convId)
      } yield {
        val capDiagnostics = evs.collect {
          case m: Message if m.role == MessageRole.Tool &&
                              m.content.exists {
                                case ResponseContent.Text(t) => t.contains("iteration cap")
                                case _                       => false
                              } => m
        }
        capDiagnostics should have size 1
      }
    }

    "fall back to AgentRunawayException only when the forced-synthesis turn also fails" in {
      val recorder = new CallRecorder
      TestSigil.setProvider(Task.pure(new StubbornProvider(recorder)))
      val convId = Conversation.id(s"cap-stubborn-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = TestTopicEntry.id,
                 content        = Vector(ResponseContent.Text("Do something")),
                 state          = EventState.Complete
               ))
        _   <- Task.sleep(3.seconds)
        evs <- eventsFor(convId)
      } yield {
        // Forced-synthesis call DID happen (Specific(respond) pin).
        val forcedChoices = recorder.toolChoices.get().collect {
          case s: ToolChoice.Specific => s.toolName.value
        }
        forcedChoices should contain (RespondTool.schema.name.value)
        // No Success-disposition respond Message was produced (the
        // model refused to comply). The Failure-disposition messages
        // from the AgentRunawayException publish are separate and
        // expected — they don't represent a "respond" call.
        val agentRespondMessages = evs.collect {
          case m: Message
            if m.participantId == TestAgent &&
               m.role == MessageRole.Standard &&
               m.isSuccess &&
               m.content.collectFirst { case ResponseContent.Text(_) => true }.contains(true) =>
            m
        }
        agentRespondMessages shouldBe empty
        // A Failure-disposition message from publishFailureMessage(AgentRunawayException) should land.
        val failureMessages = evs.collect {
          case m: Message if m.isFailure && m.failureReason.exists(_.contains("AgentRunaway")) => m
        }
        failureMessages should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
