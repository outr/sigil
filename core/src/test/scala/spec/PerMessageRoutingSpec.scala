package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AsyncWordSpec}
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.provider.{
  AnalysisWork, Complexity, ConversationWork, CodingWork, GenerationSettings,
  Instructions, ModelCandidate, Provider, ProviderCall, ProviderEvent, ProviderStrategy,
  ProviderType, StopReason, WorkType
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RequestEscalationInput, RequestEscalationTool, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #128 — per-message routing via inferWorkType
 * + inferComplexity callbacks, ModelCandidate.supportedComplexity
 * filtering, request_escalation tool, and cap-hit (#125) composition.
 *
 * Pure-function tests against the strategy + skip gates (synchronous)
 * plus end-to-end runs through `runAgentTurn` against fake providers
 * (asynchronous).
 */
class ProviderStrategySkipGatesSpec extends AnyWordSpec with Matchers {

  private val llama = ModelCandidate(
    Model.id("llama", "9b"),
    supportedComplexity = Set(Complexity.Low)
  )
  private val gpt = ModelCandidate(
    Model.id("openai", "gpt-frontier"),
    supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High)
  )
  private val claude = ModelCandidate(
    Model.id("anthropic", "claude-opus"),
    supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High)
  )

  "RoutedStrategy.workTypeMatters" should {

    "be false when every WorkType chain equals the default" in {
      val s = ProviderStrategy.routed(
        default = List(llama),
        routes  = Map(CodingWork -> List(llama), ConversationWork -> List(llama))
      )
      s.workTypeMatters shouldBe false
      s.shouldClassifyWorkType shouldBe false
    }

    "be true when any chain differs from default" in {
      val s = ProviderStrategy.routed(
        default = List(llama, gpt, claude),
        routes  = Map(CodingWork -> List(gpt, claude, llama))
      )
      s.workTypeMatters shouldBe true
    }

    "compose with inferWorkType.isDefined → shouldClassifyWorkType" in {
      val classifier: ProviderStrategy.InferWorkType = (_, _) => Task.pure(CodingWork)
      val s = ProviderStrategy.routed(
        default       = List(llama, gpt),
        routes        = Map(CodingWork -> List(gpt, llama)),
        inferWorkType = Some(classifier)
      )
      s.shouldClassifyWorkType shouldBe true
    }
  }

  "RoutedStrategy.complexityMatters" should {

    "be false when every candidate in the resolved chain shares the same supportedComplexity" in {
      val allTiers = ModelCandidate(Model.id("uniform", "alpha"))
      val s = ProviderStrategy.routed(
        default = List(allTiers, allTiers.copy(modelId = Model.id("uniform", "beta")))
      )
      s.complexityMatters(ConversationWork) shouldBe false
    }

    "be true when candidates in the resolved chain differ" in {
      val s = ProviderStrategy.routed(
        default = List(llama, gpt, claude)
      )
      s.complexityMatters(ConversationWork) shouldBe true
    }

    "shouldClassifyComplexity composes with inferComplexity.isDefined + per-chain check" in {
      val classifier: ProviderStrategy.InferComplexity = (_, _) => Task.pure(Complexity.High)
      val s = ProviderStrategy.routed(
        default         = List(llama, gpt, claude),
        inferComplexity = Some(classifier)
      )
      s.shouldClassifyComplexity(ConversationWork) shouldBe true
    }
  }

  "Complexity.bumpUp" should {
    "raise Low → Medium → High → VeryHigh and clamp at VeryHigh" in {
      Complexity.bumpUp(Complexity.Low) shouldBe Complexity.Medium
      Complexity.bumpUp(Complexity.Medium) shouldBe Complexity.High
      Complexity.bumpUp(Complexity.High) shouldBe Complexity.VeryHigh
      Complexity.bumpUp(Complexity.VeryHigh) shouldBe Complexity.VeryHigh
    }
  }

  "Complexity.ordered" should {
    "enumerate every tier in ascending order" in {
      Complexity.ordered shouldBe List(Complexity.Low, Complexity.Medium, Complexity.High, Complexity.VeryHigh)
    }
  }
}

class PerMessageRoutingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val testModelId: Id[Model] = Model.id("test", "routing")

  /** Counts how many times each callback fired — drives the
    * "fires exactly once per turn / N iterations" assertions. */
  private final class CallbackCounters {
    val workType   = new AtomicInteger(0)
    val complexity = new AtomicInteger(0)
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = testModelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  "Routing cache" should {

    "share one classification across multiple iterations of the same user turn" in {
      // Drive two manual classifyForRoute calls against the same conv
      // + same user message. Counter should increment only once.
      // CodingWork's chain has tier variation so complexityMatters
      // fires the classifier.
      val counters = new CallbackCounters
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(testModelId, supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(Model.id("test", "high"), supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
        ),
        routes = Map(
          CodingWork -> List(
            ModelCandidate(Model.id("test", "code-low"),  supportedComplexity = Set(Complexity.Low)),
            ModelCandidate(Model.id("test", "code-high"), supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
          )
        ),
        inferWorkType = Some((_, _) => Task { counters.workType.incrementAndGet(); CodingWork }),
        inferComplexity = Some((_, _) => Task { counters.complexity.incrementAndGet(); Complexity.High })
      )
      val convId = Conversation.id(s"routing-cache-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
      val userMsg = Message(
        participantId  = TestUser,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        content        = Vector(ResponseContent.Text("hi there")),
        state          = EventState.Complete
      )
      val ctx = TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser),
        conversation = conv,
        turnInput    = sigil.conversation.TurnInput(conversationId = convId, frames = Vector.empty, participantProjections = Map.empty)
      )
      for {
        first  <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), ctx)
        second <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), ctx)
        third  <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), ctx)
      } yield {
        first shouldBe (CodingWork, Complexity.High)
        second shouldBe first
        third shouldBe first
        // Only one classifier call total across three iterations.
        counters.workType.get() shouldBe 1
        counters.complexity.get() shouldBe 1
      }
    }

    "re-classify when the user message changes (new user turn)" in {
      val counters = new CallbackCounters
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(testModelId, supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(Model.id("test", "high"), supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
        ),
        routes = Map(
          CodingWork -> List(
            ModelCandidate(Model.id("test", "code-low"),  supportedComplexity = Set(Complexity.Low)),
            ModelCandidate(Model.id("test", "code-high"), supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
          )
        ),
        inferWorkType   = Some((_, _) => Task { counters.workType.incrementAndGet(); CodingWork }),
        inferComplexity = Some((_, _) => Task { counters.complexity.incrementAndGet(); Complexity.High })
      )
      val convId = Conversation.id(s"routing-cache-newmsg-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
      val firstMsg  = Message(participantId = TestUser, conversationId = convId, topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("first turn")), state = EventState.Complete)
      val secondMsg = Message(participantId = TestUser, conversationId = convId, topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("second turn")), state = EventState.Complete)
      val ctx = TurnContext(
        sigil = TestSigil, chain = List(TestUser), conversation = conv,
        turnInput = sigil.conversation.TurnInput(conversationId = convId, frames = Vector.empty, participantProjections = Map.empty)
      )
      for {
        _ <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(firstMsg), ctx)
        _ <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(secondMsg), ctx)
      } yield {
        counters.workType.get() shouldBe 2
        counters.complexity.get() shouldBe 2
      }
    }
  }

  "Skip gates" should {

    "not invoke the classifier when workTypeMatters is false" in {
      val counters = new CallbackCounters
      val uniform = List(ModelCandidate(testModelId))
      val strategy = ProviderStrategy.routed(
        default = uniform,
        routes  = Map(CodingWork -> uniform, ConversationWork -> uniform),
        inferWorkType   = Some((_, _) => Task { counters.workType.incrementAndGet(); CodingWork }),
        inferComplexity = Some((_, _) => Task { counters.complexity.incrementAndGet(); Complexity.High })
      )
      val convId = Conversation.id(s"skip-wt-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
      val userMsg = Message(participantId = TestUser, conversationId = convId, topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("any")), state = EventState.Complete)
      val ctx = TurnContext(
        sigil = TestSigil, chain = List(TestUser), conversation = conv,
        turnInput = sigil.conversation.TurnInput(conversationId = convId, frames = Vector.empty, participantProjections = Map.empty)
      )
      for {
        result <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), ctx)
      } yield {
        result shouldBe (ConversationWork, Complexity.Medium)
        counters.workType.get() shouldBe 0
        counters.complexity.get() shouldBe 0
      }
    }
  }

  "RequestEscalationTool" should {

    "bump the cached tier one step up and return bumped = true" in {
      val counters = new CallbackCounters
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(testModelId, supportedComplexity = Set(Complexity.Low)),
          ModelCandidate(Model.id("test", "med"), supportedComplexity = Set(Complexity.Medium)),
          ModelCandidate(Model.id("test", "hi"),  supportedComplexity = Set(Complexity.High))
        ),
        inferWorkType   = Some((_, _) => Task { counters.workType.incrementAndGet(); ConversationWork }),
        inferComplexity = Some((_, _) => Task { counters.complexity.incrementAndGet(); Complexity.Low })
      )
      val convId = Conversation.id(s"escalate-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
      val userMsg = Message(participantId = TestUser, conversationId = convId, topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("simple")), state = EventState.Complete)
      val ctx = TurnContext(
        sigil = TestSigil, chain = List(TestUser), conversation = conv,
        turnInput = sigil.conversation.TurnInput(conversationId = convId, frames = Vector.empty, participantProjections = Map.empty)
      )
      for {
        _    <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), ctx)
        out1 <- RequestEscalationTool.invoke(RequestEscalationInput(reason = "harder than I thought"), ctx)
        out2 <- RequestEscalationTool.invoke(RequestEscalationInput(reason = "harder still"), ctx)
        out3 <- RequestEscalationTool.invoke(RequestEscalationInput(reason = "frontier needed"), ctx)
        out4 <- RequestEscalationTool.invoke(RequestEscalationInput(reason = "max it out"), ctx)
      } yield {
        out1.tier shouldBe Complexity.Medium
        out1.bumped shouldBe true
        out2.tier shouldBe Complexity.High
        out2.bumped shouldBe true
        out3.tier shouldBe Complexity.VeryHigh
        out3.bumped shouldBe true
        out4.tier shouldBe Complexity.VeryHigh
        out4.bumped shouldBe false  // clamp at VeryHigh
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
