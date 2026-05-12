package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AsyncWordSpec}
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{AgentState, Event, Message}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, ConversationRequest, GenerationSettings, Instructions, OneShotRequest,
  Provider, ProviderCall, ProviderEvent, ProviderMessage, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #132 — agent-initiated turns (greeting,
 * scheduled, autonomous, worker-spawn) used to produce empty
 * `messages` after translation, leading OpenAI Responses /
 * Anthropic Messages / Google generateContent to reject the
 * request with HTTP 400. The framework now synthesizes a single
 * placeholder user message ([[Provider.AgentInitiatedTurnTrigger]])
 * when `renderFrames` returns empty.
 */
class AgentInitiatedTurnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "agent-initiated")

  /** Capture the `ProviderCall.messages` passed in on the provider's
    * `call` so the spec can assert the wire-bound messages directly. */
  private final class CaptureProvider extends Provider {
    @volatile var captured: Option[Vector[ProviderMessage]] = None
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      captured = Some(input.messages)
      Stream.emits(List(
        ProviderEvent.ToolCallStart(CallId("c-1"), "respond"),
        ProviderEvent.ToolCallComplete(
          CallId("c-1"),
          _root_.sigil.tool.model.RespondInput(
            topicLabel = "Greet", topicSummary = "agent-initiated test",
            content = _root_.sigil.tool.model.RespondContent.Text("hi"), endsTurn = true
          )
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def makeAgent(greetsOnJoin: Boolean): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      greetsOnJoin       = greetsOnJoin
    )

  "Agent-initiated turn (greeting)" should {

    "produce ProviderCall.messages with at least one synthesised user-role message" in {
      val provider = new CaptureProvider
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"greet-${rapid.Unique()}")
      val agent  = makeAgent(greetsOnJoin = true)
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.fireGreeting(agent, conv)
        _ <- Task.sleep(3.seconds)
      } yield {
        provider.captured.isDefined shouldBe true
        val msgs = provider.captured.get
        msgs should not be empty
        // The synthesised message has user role + non-empty text.
        msgs.head shouldBe a [ProviderMessage.User]
        val text = msgs.head.asInstanceOf[ProviderMessage.User].content.collect {
          case _root_.sigil.provider.MessageContent.Text(t) => t
        }.mkString
        text should include("agent-initiated")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

/** Synchronous coverage for the `OneShotRequest` constructor guard. */
class OneShotRequestGuardSpec extends AnyWordSpec with Matchers {

  "OneShotRequest" should {

    "construct successfully when userPrompt is non-empty" in {
      val r = OneShotRequest(
        modelId = Model.id("t", "m"),
        systemPrompt = "sys",
        userPrompt = "do the thing"
      )
      r.userPrompt shouldBe "do the thing"
    }

    "construct successfully when userContent is non-empty (multimodal)" in {
      val r = OneShotRequest(
        modelId = Model.id("t", "m"),
        systemPrompt = "sys",
        userPrompt = "",
        userContent = Vector(_root_.sigil.tool.model.ResponseContent.Text("the content"))
      )
      r.userContent should have size 1
    }

    "reject construction when both userPrompt and userContent are empty" in {
      val ex = intercept[IllegalArgumentException] {
        OneShotRequest(
          modelId = Model.id("t", "m"),
          systemPrompt = "sys",
          userPrompt = "",
          userContent = Vector.empty
        )
      }
      ex.getMessage should include("OneShotRequest requires non-empty userPrompt OR non-empty userContent")
    }
  }
}
