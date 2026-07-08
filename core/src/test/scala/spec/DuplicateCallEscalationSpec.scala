package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ParticipantProjection, RecentToolInvocation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageDisposition, MessageRole, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, Complexity, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, ModelCandidate, Provider, ProviderCall, ProviderEvent, ProviderStrategy,
  ProviderType, StopReason, ConversationWork
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.core.{FindCapabilityInput, FindCapabilityTool}
import sigil.tool.{ToolInputCanonicalizer, ToolName}
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Regression for sigil bug #287 — the duplicate-call cap
 * ([[Sigil.maxIdenticalToolCallsInWindow]]) MUST fire on identical
 * (toolName, canonical-args) repeats AND escalate the conversation's
 * complexity tier on each trip. Detection alone isn't enough on
 * small models — the same model that produced the duplicate keeps
 * producing it; escalation routes the next iteration to a more
 * capable model that can read the Failure and pick a different
 * move.
 */
class DuplicateCallEscalationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  TestSigil.testModel(modelId)

  /** Stub provider that emits one `find_capability` tool call with
    * the supplied keywords. Used to drive the orchestrator past the
    * cap check; the cap inspects `request.turnInput.projectionFor`
    * (pre-populated by the test setup), not the provider stream. */
  private class FindCapStubProvider(keywords: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(s"fc-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, FindCapabilityTool.name.value),
        ProviderEvent.ToolCallComplete(cid, FindCapabilityInput(keywords = keywords)),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  /** Build a ConversationRequest whose turnInput pre-seeds the
    * agent's `recentToolInvocations` with `priorIdentical` entries
    * matching `(toolName, argsHash)`. This is what the orchestrator's
    * cap check reads. */
  private def requestWithPriorInvocations(convId: Id[Conversation],
                                          priorIdentical: Int,
                                          keywords: String,
                                          resulted: Boolean = true,
                                          failed: Boolean = false): ConversationRequest = {
    val canonicalHash = ToolInputCanonicalizer.argsHash(FindCapabilityInput(keywords = keywords))
    val canonicalPreview = ToolInputCanonicalizer.argsPreview(FindCapabilityInput(keywords = keywords))
    val priorInvocations = (1 to priorIdentical).toList.map { _ =>
      RecentToolInvocation(
        toolName    = FindCapabilityTool.name,
        argsHash    = canonicalHash,
        argsPreview = canonicalPreview,
        invokedAt   = Timestamp(Nowish()),
        resulted    = resulted,
        failed      = failed
      )
    }
    val projection = ParticipantProjection.empty(TestAgent, convId)
      .copy(recentToolInvocations = priorInvocations)
    val turnInput = TurnInput(
      conversationId         = convId,
      participantProjections = Map(TestAgent -> projection)
    )
    ConversationRequest(
      conversationId     = convId,
      model              = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = turnInput,
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools              = Vector(FindCapabilityTool),
      chain              = List(TestUser, TestAgent)
    )
  }

  // Distinct per-tier candidates so the chain has
  // differing `supportedComplexity` sets — that's what makes
  // `ProviderStrategy.shouldClassifyComplexity` return true (it
  // only matters when the chain can actually route differently
  // per tier). Register all three under the same nominal model id.
  private val lowModelId    = Model.id("test", "low")
  private val mediumModelId = Model.id("test", "medium")
  private val highModelId   = Model.id("test", "high")
  TestSigil.testModel(lowModelId)
  TestSigil.testModel(mediumModelId)
  TestSigil.testModel(highModelId)

  /** Routed strategy with three per-tier candidates so
    * `shouldClassifyComplexity` is true; classifier returns the
    * supplied tier so the escalation counter accumulates against a
    * real classification memo. */
  private def routedStrategy(calls: AtomicInteger,
                             classifyAs: Complexity): ProviderStrategy = ProviderStrategy.routed(
    default = List(
      ModelCandidate(lowModelId,    supportedComplexity = Set(Complexity.Low)),
      ModelCandidate(mediumModelId, supportedComplexity = Set(Complexity.Medium)),
      ModelCandidate(highModelId,   supportedComplexity = Set(Complexity.High))
    ),
    routes  = Map.empty,
    inferWorkType   = Some((_, _) => Task.pure(ConversationWork)),
    inferComplexity = Some((_, _) => Task {
      calls.incrementAndGet()
      classifyAs
    })
  )

  override implicit val testTimeout: FiniteDuration = 30.seconds

  "Sigil bug #287 — duplicate-call cap + escalation" should {

    "REFUSE the dispatch when prior identical calls reach the cap" in {
      // Default `maxIdenticalToolCallsInWindow = 3`; seed 2 priors so
      // this is call #3 — the one the cap refuses.
      val convId = Conversation.id(s"dup-cap-refuse-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = requestWithPriorInvocations(convId, priorIdentical = 2, keywords = "x")
      val provider = new FindCapStubProvider(keywords = "x")
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        val invokes = signals.collect { case t: ToolInvoke => t }
        // The orchestrator still EMITS the invoke (the agent's call
        // is real); the refusal short-circuits the dispatch.
        invokes.exists(_.toolName == FindCapabilityTool.name) shouldBe true
        // The settling ToolDelta carries a Failure outcome explaining
        // the refusal — that's the cap's wire-visible signal.
        val failures = signals.collect {
          case d: ToolDelta => d.outcome
        }.flatten.collect { case f: ToolOutcome.Failure => f }
        val capFailures = failures.filter { f =>
          f.reason.contains("Refused to dispatch") && f.reason.contains("find_capability")
        }
        // Tool-role Failure Message paired by origin to the invoke;
        // the agent reads it on its next iteration.
        val capMsgs = signals.collect {
          case m: Message if m.role == MessageRole.Tool && m.isFailure => m
        }.filter { m =>
          m.content.exists {
            case t: ResponseContent.Text => t.text.contains("Refused to dispatch")
            case _                        => false
          }
        }
        withClue(s"expected a cap-refusal Failure to surface (outcomes: $failures, msgs: ${capMsgs.size}): ") {
          (capFailures.nonEmpty || capMsgs.nonEmpty) shouldBe true
        }
      }
    }

    "NOT count race-induced retries (Pending-result priors) toward the cap (#354)" in {
      // A raced prior (resulted = false) below the #407 raced-reissue bound is
      // not the agent spinning — retrying to obtain the missing result of a
      // transient race must be allowed, so the duplicate-call cap (and its
      // escalation/restriction spiral) must NOT fire. Seed 1 raced prior:
      // below `maxRacedReissues` (default 2), so #407's persistent-racer
      // redirect stays dormant here and this isolates the #354 guarantee. (The
      // #407 spec covers the at/above-bound redirect separately.)
      val convId = Conversation.id(s"dup-cap-race-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = requestWithPriorInvocations(convId, priorIdentical = 1, keywords = "x", resulted = false)
      val provider = new FindCapStubProvider(keywords = "x")
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        val capFailure = signals.collect { case d: ToolDelta => d.outcome }.flatten
          .collect { case f: ToolOutcome.Failure => f }
          .exists(_.reason.contains("Refused to dispatch"))
        val capMsg = signals.collect { case m: Message if m.role == MessageRole.Tool && m.isFailure => m }
          .exists(_.content.exists {
            case t: ResponseContent.Text => t.text.contains("Refused to dispatch")
            case _                        => false
          })
        withClue("race-induced retries (resulted=false priors) must NOT trip the duplicate-call cap: ") {
          (capFailure || capMsg) shouldBe false
        }
      }
    }

    "ESCALATE the conversation's complexity tier when the cap fires" in {
      val calls    = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))

      val convId = Conversation.id(s"dup-cap-escalate-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = requestWithPriorInvocations(convId, priorIdentical = 2, keywords = "x")
      val provider = new FindCapStubProvider(keywords = "x")
      val userMsg = Message(
        participantId  = TestUser,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        content        = Vector(ResponseContent.Text("hi")),
        state          = EventState.Complete
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        // Prime the classifier so requestEscalation has a memo entry
        // to bump on top of.
        _ <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg),
               sigil.TurnContext(
                 sigil        = TestSigil,
                 chain        = List(TestUser, TestAgent),
                 conversation = conv,
                 turnInput    = TurnInput(conversationId = convId),
                 model        = TestSigil.defaultTestModel
               ))
        // Verify pre-trip tier.
        before <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg),
                    sigil.TurnContext(
                      sigil        = TestSigil,
                      chain        = List(TestUser, TestAgent),
                      conversation = conv,
                      turnInput    = TurnInput(conversationId = convId),
                      model        = TestSigil.defaultTestModel
                    ))
        // Trip the cap.
        _ <- Orchestrator.process(TestSigil, provider, request, conv).toList
        // Re-classify — escalation should have bumped Low → Medium.
        after <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg),
                   sigil.TurnContext(
                     sigil        = TestSigil,
                     chain        = List(TestUser, TestAgent),
                     conversation = conv,
                     turnInput    = TurnInput(conversationId = convId),
                     model        = TestSigil.defaultTestModel
                   ))
      } yield {
        before._2 shouldBe Complexity.Low
        after._2 shouldBe Complexity.Medium
        // #371 — the escalation is no longer silent: its reason (trigger +
        // from→to tier) is recorded for surfacing on RouteResolved / UIs.
        val reason = TestSigil.escalationReasonFor(convId)
        withClue(s"escalation reason: $reason\n") {
          reason.isDefined shouldBe true
          reason.get should include("duplicate-call cap")
          reason.get should include("Low→Medium")
        }
      }
    }

    "NOT escalate when the prior identical calls all FAILED — a tooling-seam loop, not capability (#371)" in {
      val calls    = new AtomicInteger(0)
      val strategy = routedStrategy(calls, classifyAs = Complexity.Low)
      TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))

      val convId = Conversation.id(s"dup-cap-seam-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      // Priors are identical AND all FAILED — the same call hitting the same
      // recoverable failure. A stronger model can't change a deterministic tool
      // failure, so the cap must refuse WITHOUT escalating the tier.
      val request = requestWithPriorInvocations(convId, priorIdentical = 2, keywords = "x", failed = true)
      val provider = new FindCapStubProvider(keywords = "x")
      val userMsg = Message(
        participantId  = TestUser,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        content        = Vector(ResponseContent.Text("hi")),
        state          = EventState.Complete
      )
      val tc = sigil.TurnContext(
        sigil = TestSigil, chain = List(TestUser, TestAgent), conversation = conv,
        turnInput = TurnInput(conversationId = convId), model = TestSigil.defaultTestModel
      )
      for {
        _      <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _      <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), tc)
        before <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), tc)
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
        after  <- TestSigil.classifyForRoute(strategy, ConversationWork, conv, Some(userMsg), tc)
      } yield {
        // The cap still REFUSES the duplicate...
        val capMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool && m.isFailure => m }
          .flatMap(_.content.collect { case t: ResponseContent.Text => t.text })
        withClue(s"cap messages: $capMsgs\n") {
          capMsgs.exists(_.contains("Refused to dispatch")) shouldBe true
          // ...but does NOT escalate the tier, and the guidance points at the seam.
          capMsgs.exists(_.contains("keeps failing on these exact args")) shouldBe true
          capMsgs.exists(_.contains("Next iteration will run on the")) shouldBe false
        }
        before._2 shouldBe Complexity.Low
        after._2 shouldBe Complexity.Low // no escalation
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
