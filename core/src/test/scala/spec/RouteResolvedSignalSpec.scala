package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Event, Message, RouteResolved}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  AnalysisWork, CallId, Complexity, ConversationWork, GenerationSettings,
  Instructions, ModelCandidate, Provider, ProviderCall, ProviderEvent, ProviderStrategy,
  ProviderType, StopReason, WorkType
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondContent, RespondInput}
import spice.http.HttpRequest

import scala.concurrent.duration.*

class RouteResolvedSignalSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val testModelId: Id[Model] = Model.id("test", "routing-signal")

  private final class RespondOnce extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(List(
      ProviderEvent.ToolCallStart(CallId(s"r-${rapid.Unique()}"), RespondTool.schema.name.value),
      ProviderEvent.ToolCallComplete(
        CallId(s"r-${rapid.Unique()}"),
        RespondInput(topicLabel = "RR", topicSummary = "spec", content = RespondContent.Text("ok"), endsTurn = true)
      ),
      ProviderEvent.Done(StopReason.Complete)
    ))
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = testModelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  "runAgentTurn" should {

    "publish a RouteResolved event with classifier-driven workType + complexity" in {
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(testModelId, supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(Model.id("test", "high"), supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
        ),
        routes = Map(
          AnalysisWork -> List(
            ModelCandidate(Model.id("test", "analysis-low"),  supportedComplexity = Set(Complexity.Low)),
            ModelCandidate(Model.id("test", "analysis-high"), supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
          )
        ),
        inferWorkType   = Some((_, _) => Task.pure(AnalysisWork)),
        inferComplexity = Some((_, _) => Task.pure(Complexity.High))
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      TestSigil.setProvider(Task.pure(new RespondOnce))
      val convId = Conversation.id(s"route-resolved-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = TestTopicEntry.id,
                 content        = Vector(ResponseContent.Text("Audit this auth flow for vulnerabilities")),
                 state          = EventState.Complete
               ))
        _   <- Task.sleep(2.seconds)
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val rrs = evs.collect { case rr: RouteResolved if rr.conversationId == convId => rr }
        rrs should not be empty
        val rr = rrs.head
        rr.inferredWorkType shouldBe Some(AnalysisWork)
        rr.inferredComplexity shouldBe Some(Complexity.High)
        rr.chosenModelId shouldBe Model.id("test", "analysis-high")
        rr.candidateChain should contain(Model.id("test", "analysis-high"))
        rr.skipReasons.keySet should contain(Model.id("test", "analysis-low"))
        rr.skipReasons(Model.id("test", "analysis-low")) should include("High")
        rr.escalationCount shouldBe 0
      }
    }

    "publish RouteResolved with None classifier fields when skip gates trivialise the decision" in {
      val uniform = List(ModelCandidate(testModelId))
      val strategy = ProviderStrategy.routed(
        default = uniform,
        routes  = Map(AnalysisWork -> uniform, ConversationWork -> uniform)
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      TestSigil.setProvider(Task.pure(new RespondOnce))
      val convId = Conversation.id(s"route-resolved-skip-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = TestTopicEntry.id,
                 content        = Vector(ResponseContent.Text("Hi")),
                 state          = EventState.Complete
               ))
        _   <- Task.sleep(2.seconds)
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val rrs = evs.collect { case rr: RouteResolved if rr.conversationId == convId => rr }
        rrs should not be empty
        val rr = rrs.head
        rr.inferredWorkType shouldBe None
        rr.inferredComplexity shouldBe None
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
