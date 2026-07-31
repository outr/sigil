package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ParticipantProjection, RecentToolInvocation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason, ToolPolicy
}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, FindCapabilityTool, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

/**
 * Sigil #397 — when discovery is suppressed (`ToolPolicy.ActiveOnly` / `None` /
 * `Exclusive`), `find_capability` is correctly stripped from the roster (#388),
 * but several system-prompt prose fragments + an orchestrator corrective still
 * NAMED it — telling the model to call a tool it doesn't have. No prompt text
 * should name `find_capability` when it isn't offered.
 */
class FindCapabilityPromptSuppressionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  private val convId = Conversation.id("fc-prompt-suppression")

  /** A roster WITHOUT find_capability mimics what ToolPolicy.ActiveOnly leaves. */
  private val rosterNoDiscovery: Vector[sigil.tool.Tool] =
    CoreTools.all.filterNot(_.schema.name == FindCapabilityTool.schema.name)

  private def dupProjection: ParticipantProjection = {
    val inv = RecentToolInvocation(
      toolName = ToolName("browser_navigate"), argsHash = "h1",
      argsPreview = "{\"url\":\"x\"}", invokedAt = Timestamp())
    ParticipantProjection.empty(TestAgent, convId).copy(recentToolInvocations = List(inv, inv))
  }

  private def buildRequest(discoveryOn: Boolean, isGreeting: Boolean, withDuplicates: Boolean): ConversationRequest = {
    val proj = if (withDuplicates) Map(TestAgent -> dupProjection) else Map.empty[sigil.participant.ParticipantId, ParticipantProjection]
    ConversationRequest(
      conversationId     = convId,
      model              = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId).copy(participantProjections = proj),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools              = if (discoveryOn) CoreTools.all else rosterNoDiscovery,
      chain              = List(TestUser, TestAgent),
      isGreeting         = isGreeting
    )
  }

  private def renderPrompt(request: ConversationRequest): Task[String] = {
    val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
    provider.requestConverter(request).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })
  }

  "System-prompt prose with discovery suppressed (sigil #397)" should {

    "drop find_capability from the greeting block (keeping the no_response guidance)" in {
      renderPrompt(buildRequest(discoveryOn = false, isGreeting = true, withDuplicates = false)).map { body =>
        body should include("Greeting turn")
        body should include("Do NOT call `no_response`")
        body should not include "find_capability"
      }
    }

    "still name find_capability in the greeting block when discovery IS available" in {
      renderPrompt(buildRequest(discoveryOn = true, isGreeting = true, withDuplicates = false)).map { body =>
        body should include("Greeting turn")
        body should include("find_capability")
      }
    }

    "drop find_capability from the repeated-tool-calls block" in {
      renderPrompt(buildRequest(discoveryOn = false, isGreeting = false, withDuplicates = true)).map { body =>
        body should include("Repeated tool calls")
        body should not include "find_capability"
      }
    }

    "still name find_capability in the repeated-tool-calls block when discovery IS available" in {
      renderPrompt(buildRequest(discoveryOn = true, isGreeting = false, withDuplicates = true)).map { body =>
        body should include("Repeated tool calls")
        body should include("find_capability")
      }
    }
  }

  // --- Orchestrator refusal-challenge gating ---

  private final class RefuseProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"call-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.toolCall(callId, RespondTool)(RespondInput(topicLabel = "Refusal", topicSummary = "no discovery",
            content = "I can't switch to GPT-5.5 — that's beyond what I do.", endsTurn = true)),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Discovery-off agent: ActiveOnly(respond) strips find_capability (#388). */
  private def discoveryOffAgent: AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = List(RespondTool.name),
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools              = ToolPolicy.ActiveOnly(List(RespondTool.name)))

  "Orchestrator refusal-challenge with discovery suppressed (sigil #397)" should {
    "NOT challenge a refusal when find_capability isn't in the roster (an informed refusal is the only option)" in {
      TestSigil.setProvider(Task.pure(new RefuseProvider))
      val cId = Conversation.id(s"fc-refuse-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(discoveryOffAgent), _id = cId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = cId,
                 topicId        = TestTopicEntry.id,
                 content        = Vector(ResponseContent.Text("switch to gpt-5.5")),
                 state          = EventState.Complete))
        _   <- TestSigil.awaitSettled(cId)
        evs <- TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == cId))
      } yield {
        // No challenge — the corrective would tell the agent to call a
        // find_capability that isn't offered.
        evs.collect { case ti: ToolInvoke if ti.toolName.value == "_refusal_challenge" => ti } shouldBe empty
        // The refusal landed as a real user-facing reply instead.
        evs.collect {
          case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard => m
        } should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
