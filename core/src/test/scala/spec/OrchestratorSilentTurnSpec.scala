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
  CallId, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.core.{CoreTools, NoResponseTool, RespondTool}
import sigil.tool.model.{NoResponseInput, RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * When the provider's first iteration returns Done with no tool
 * calls and no content (a "silent turn"), the framework MUST NOT
 * accept that as a turn outcome. Instead, the agent loop forces ONE
 * more iteration with `tool_choice` restricted to the respond family
 * so the model is required to emit a real reply (respond /
 * respond_options / respond_field / respond_failure / respond_card /
 * respond_cards / no_response).
 *
 * No fake "(agent completed without a reply)" placeholder Message is
 * synthesized. Either the agent actually responds, or the forced
 * iteration also fails to call respond — at which point
 * AgentRunawayException surfaces as a hard failure.
 */
class OrchestratorSilentTurnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /** Provider that's silent on the first call, then emits a respond
    * on the second call (the forced-synthesis iteration). */
  private class SilentThenRespondProvider extends Provider {
    private val callCount = new atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      // Skip classifier subcalls (no respond in tool roster).
      val isPrimary = input.tools.exists(_.name.value == "respond")
      if (!isPrimary) return Stream.empty
      callCount.incrementAndGet() match {
        case 1 =>
          // First call: silent (no tool calls, no content).
          Stream.emit(ProviderEvent.Done(StopReason.Complete))
        case _ =>
          // Forced iteration: produce a real respond.
          val callId = CallId("respond-forced")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(callId,
              RespondInput(topicLabel = "Test", topicSummary = "Forced reply", content = "Forced reply.", endsTurn = true)),
            ProviderEvent.Done(StopReason.Complete)
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

  private def runScenario(provider: Provider, suffix: String): Task[List[Signal]] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"silent-turn-loop-$suffix")
    val agent = makeAgent()
    val conv  = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)

    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running  = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()

    for {
      _ <- Task.sleep(100.millis)
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("hi")),
             state          = EventState.Complete
           ))
      _ <- Task.sleep(1500.millis)
    } yield {
      running.set(false)
      recorded.iterator().asScala.toList
    }
  }

  "Sigil.runAgentLoop silent-turn recovery" should {

    "force a respond-family iteration when the first call is silent" in {
      runScenario(new SilentThenRespondProvider, suffix = "force").map { signals =>
        // The forced iteration produces a real respond → a Standard-role
        // agent Message with the expected content.
        val agentReplies = signals.collect {
          case m: Message
            if m.participantId == TestAgent &&
              m.role == MessageRole.Standard &&
              m.content.exists {
                case ResponseContent.Text(t) => t.contains("Forced reply.")
                case sigil.tool.model.ResponseContent.Markdown(t) => t.contains("Forced reply.")
                case _ => false
              } => m
        }
        agentReplies should not be empty
        // No fake placeholder text leaks into the conversation.
        signals.collect {
          case m: Message
            if m.content.exists {
              case ResponseContent.Text(t) => t.contains("without a reply")
              case _ => false
            } => m
        } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
