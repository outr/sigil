package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, RouteResolved}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  AnalysisWork, CallId, Complexity, ConversationWork, GenerationSettings,
  Instructions, ModelCandidate, Provider, ProviderCall, ProviderEvent, ProviderStrategy,
  ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Regression for Sigil bug #175 — when every candidate in the strategy
 * chain is skipped (e.g. an env-var-driven candidate is unavailable),
 * routing falls back to `agent.modelId` silently. RouteResolved
 * captures the skip reasons but is a ControlPlaneEvent — it doesn't
 * enter the agent's ContextFrame projection, so the agent can't see
 * the structural failure and (small models in particular) loops on
 * `change_mode` thinking the prior call didn't apply.
 *
 * Fix: publish a Standard-role Message (visibility=All, source =
 * "routing-fallback") alongside RouteResolved when
 * `chosen.isEmpty && candidateChain.nonEmpty`. Lands as a Text frame
 * on the agent's next iteration with a "don't loop" hint that stops
 * the cycle. Tool-role would be semantically closer to "framework
 * output," but #174's contract requires Tool-role events to carry an
 * origin pointing at a parent ToolInvoke — there's no invoke here.
 */
class RoutingFallbackNoticeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val agentModelId: Id[Model] = Model.id("test", "fallback-agent-default")

  private final class RespondOnce extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(s"r-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          cid,
          RespondInput(topicLabel = "T", topicSummary = "spec", content = "ok", endsTurn = true)
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = agentModelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  "Bug #175 — every-candidate-skipped routing fallback" should {

    "publish a Tool-role Message describing the gap alongside RouteResolved" in {
      // Two candidates, neither supporting Medium — the classifier returns
      // Medium, so every candidate is skipped and the framework falls
      // back to agent.modelId.
      val onlyLow      = Model.id("test", "only-low")
      val onlyVeryHigh = Model.id("test", "only-veryhigh")
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(onlyLow,      supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(onlyVeryHigh, supportedComplexity = Set(Complexity.VeryHigh))
        ),
        inferComplexity = Some((_, _) => Task.pure(Complexity.Medium))
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      TestSigil.setProvider(Task.pure(new RespondOnce))

      val convId = Conversation.id(s"routing-fallback-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = TestTopicEntry.id,
                 content        = Vector(ResponseContent.Text("Do the thing")),
                 state          = EventState.Complete
               ))
        _   <- Task.sleep(2.seconds)
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val rrs = evs.collect { case rr: RouteResolved if rr.conversationId == convId => rr }
        rrs should not be empty
        rrs.head.chosenModelId shouldBe agentModelId
        rrs.head.skipReasons.keySet should contain allOf (onlyLow, onlyVeryHigh)

        val noticeMessages = evs.collect {
          case m: Message
            if m.conversationId == convId
              && m.source.contains("routing-fallback") => m
        }
        noticeMessages should have size 1
        val rendered = noticeMessages.head.content.collect { case ResponseContent.Text(t) => t }.mkString
        rendered should include ("Medium")
        rendered should include (agentModelId.value)
        rendered should include ("don't loop")
        rendered should include (onlyLow.value)
        rendered should include (onlyVeryHigh.value)
      }
    }

    "NOT publish the fallback notice when at least one candidate fits" in {
      val fits = Model.id("test", "fits-medium")
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(fits, supportedComplexity = Set(Complexity.Low, Complexity.Medium))
        ),
        inferComplexity = Some((_, _) => Task.pure(Complexity.Medium))
      )
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
      TestSigil.setProvider(Task.pure(new RespondOnce))

      val convId = Conversation.id(s"routing-fallback-fits-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _   <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _   <- TestSigil.publish(Message(
                 participantId  = TestUser,
                 conversationId = convId,
                 topicId        = TestTopicEntry.id,
                 content        = Vector(ResponseContent.Text("Do the thing")),
                 state          = EventState.Complete
               ))
        _   <- Task.sleep(2.seconds)
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val noticeMessages = evs.collect {
          case m: Message
            if m.conversationId == convId
              && m.source.contains("routing-fallback") => m
        }
        noticeMessages shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
