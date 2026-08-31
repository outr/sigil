package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ConversationBudget}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, Complexity, ConversationWork, GenerationSettings, Instructions, ModelCandidate,
  Provider, ProviderCall, ProviderEvent, ProviderStrategy, ProviderType, StopReason,
  TokenUsage, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RequestDeescalationTool, RespondTool, SetBudgetTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import sigil.tool.ToolContext
import sigil.TurnContext
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import spec.GetMagicNumberTool

/**
 * Spend budgets: cost is a first-class failure dimension — a
 * correctly-progressing turn can still be wrong purely on the bill,
 * and no other guard is entitled to interrupt it. Soft budgets pause
 * the turn at a summary + user check-in; hard ceilings force an
 * honest wrap-up; a conversation past its hard ceiling refuses new
 * turns until `set_budget` raises it.
 *
 * Verifies:
 *   1. Crossing the turn soft budget injects the `_budget_checkin`
 *      directive exactly once; the loop continues so the agent can
 *      ask; a NEW turn re-arms and fires again.
 *   2. Crossing the turn hard ceiling forces terminal synthesis (one
 *      wrap-up iteration, tool_choice pinned) with a spend-and-state
 *      directive; no further billed iterations.
 *   3. Conversation-scope soft budget fires on the crossing turn.
 *   4. A conversation past its hard ceiling refuses to start a turn
 *      (no provider call; one user-visible exhaustion notice) and
 *      `set_budget` re-arms it.
 *   5. `request_deescalation` steps an escalated turn back down and
 *      no-ops at the classified base tier.
 */
class SpendBudgetSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val pricing: ModelPricing = ModelPricing(
    prompt = BigDecimal("0.000001"),
    completion = BigDecimal("0.000002"),
    webSearch = None,
    inputCacheRead = None
  )
  private val pricedModelId: Id[Model] = Model.id("test", "budget-model")
  TestSigil.cache.merge(List(Model(
    canonicalSlug = "test/budget-model",
    huggingFaceId = "",
    name = "budget-model",
    description = "Synthetic priced model for spend-budget tests",
    contextLength = 32768L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = pricing,
    topProvider = ModelTopProvider(contextLength = Some(32768L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = pricedModelId
  ))).sync()

  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[_root_.sigil.participant.ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }

  /**
   * Each tool-calling iteration bills 1,000,000 prompt tokens =
   * $1.00. `respondAfter` caps the tool phase; iterations after it
   * (or any forced-synthesis call) emit a terminating respond.
   */
  final private class BillingProvider(respondAfter: Int) extends Provider {
    val calls = new AtomicInteger(0)
    @volatile var sawForcedChoice = false
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = calls.incrementAndGet()
      val callId = CallId(s"call-${rapid.Unique()}")
      val forced = input.toolChoice match {
        case ToolChoice.Specific(name) if name == RespondTool.schema.name => true
        case _ => false
      }
      if (forced) sawForcedChoice = true
      val emits: List[ProviderEvent] =
        if (forced || n > respondAfter)
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
              topicLabel = TestTopicEntry.label,
              topicSummary = TestTopicEntry.summary,
              content = s"Wrap-up after $n calls.",
              endsTurn = true
            )),
            ProviderEvent.Usage(TokenUsage(10000, 100, 10100)),
            ProviderEvent.Done(StopReason.ToolCall)
          )
        else
          List(
            ProviderEvent.ToolCallStart(callId, GetMagicNumberTool.name.value),
            ProviderEvent.toolCall(callId, GetMagicNumberTool)(GetMagicNumberInput()),
            ProviderEvent.Usage(TokenUsage(1000000, 100, 1000100)),
            ProviderEvent.Done(StopReason.ToolCall)
          )
      Stream.emits(emits)
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = pricedModelId,
      toolNames = CoreTools.coreToolNames :+ GetMagicNumberTool.name,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def seedConv(budget: Option[ConversationBudget], cost: BigDecimal = BigDecimal(0)): Task[Id[Conversation]] = {
    val convId = Conversation.id(s"budget-${rapid.Unique()}")
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, participants = List(makeAgent()), budget = budget, cost = cost, _id = convId)
    ))).map(_ => convId)
  }

  private def userMessage(convId: Id[Conversation], text: String): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete
    )

  private def waitFor(timeout: FiniteDuration)(cond: => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (cond || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    loop
  }

  private def eventsOf(convId: Id[Conversation]): Task[List[Event]] =
    TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.filter(_.conversationId == convId))

  private def invokesNamed(events: List[Event], name: String): List[ToolInvoke] =
    events.collect { case ti: ToolInvoke if ti.toolName.value == name => ti }

  private def agentReplied(convId: Id[Conversation]): Task[Boolean] =
    eventsOf(convId).map(_.exists {
      case m: Message => m.participantId == TestAgent && m.role == MessageRole.Standard
      case _ => false
    })

  private def agentReplyCount(convId: Id[Conversation]): Task[Int] =
    eventsOf(convId).map(_.count {
      case m: Message => m.participantId == TestAgent && m.role == MessageRole.Standard
      case _ => false
    })

  /**
   * Run one full user turn to QUIESCENCE: a NEW agent reply landed
   * AND the provider stops being called (no increment across a
   * settle window). Early returns bleed a still-running loop into
   * the next test's provider (setProvider is by-name).
   */
  private def runTurn(provider: BillingProvider, convId: Id[Conversation], text: String): Task[Unit] = {
    TestSigil.setProvider(Task.pure(provider))
    TestSigil.setMemoryExtractor(NoExtraction)
    def quiesce(lastCalls: Int, stableMs: Long): Task[Unit] = {
      val cur = provider.calls.get()
      if (cur == lastCalls && stableMs >= 600L) Task.unit
      else Task.sleep(200.millis).flatMap(_ => quiesce(cur, if (cur == lastCalls) stableMs + 200 else 0L))
    }
    for {
      repliesBefore <- agentReplyCount(convId)
      _ <- TestSigil.publish(userMessage(convId, text))
      _ <- waitFor(30.seconds)(agentReplyCount(convId).sync() > repliesBefore)
      _ <- quiesce(provider.calls.get(), 0L)
    } yield ()
  }

  "the turn soft budget" should {

    "inject one check-in directive per turn and re-arm on the next turn" in {
      // $1.00 per tool iteration; soft budget $1.50 → crossed after
      // iteration 2; directive at that boundary; iteration 3 responds.
      val provider = new BillingProvider(respondAfter = 2)
      for {
        convId <- seedConv(Some(ConversationBudget(turnSoft = Some(BigDecimal("1.5")))))
        _ <- runTurn(provider, convId, "Do the expensive thing.")
        firstTurn <- eventsOf(convId)
        _ <- runTurn(new BillingProvider(respondAfter = 2), convId, "Yes, continue.")
        secondTurn <- eventsOf(convId)
      } yield {
        val checkinsAfterTurn1 = invokesNamed(firstTurn, "_budget_checkin")
        withClue(s"turn-1 checkins=${checkinsAfterTurn1.size}: ") {
          checkinsAfterTurn1 should have size 1
        }
        val directive = firstTurn.collect {
          case m: Message if m.role == MessageRole.Tool && m.content.exists {
                case t: ResponseContent.Text => t.text.contains("soft budget")
                case _ => false
              } => m
        }
        directive should not be empty
        directive.head.content.collectFirst { case t: ResponseContent.Text => t.text }.get should
          include("request_deescalation")
        // Fresh turn, fresh budget: the second turn crossed again and
        // fired its own check-in.
        invokesNamed(secondTurn, "_budget_checkin") should have size 2
      }
    }
  }

  "the turn hard ceiling" should {

    "force terminal synthesis with a spend-and-state directive and stop billing" in {
      // Ceiling $0.50 → crossed after iteration 1; forced wrap-up is
      // call 2; nothing after.
      val provider = new BillingProvider(respondAfter = 99)
      for {
        convId <- seedConv(Some(ConversationBudget(turnHard = Some(BigDecimal("0.5")))))
        _ <- runTurn(provider, convId, "Do the expensive thing.")
        events <- eventsOf(convId)
      } yield {
        invokesNamed(events, "_budget_ceiling") should have size 1
        val directive = events.collect {
          case m: Message if m.role == MessageRole.Tool && m.content.exists {
                case t: ResponseContent.Text => t.text.contains("hard per-turn ceiling")
                case _ => false
              } => m
        }
        directive should not be empty
        withClue(s"calls=${provider.calls.get()} sawForced=${provider.sawForcedChoice}: ") {
          provider.sawForcedChoice shouldBe true
          provider.calls.get() shouldBe 2
        }
      }
    }
  }

  "the conversation-scope budget" should {

    "fire the soft check-in on the turn that crosses the accumulated threshold" in {
      // Conversation soft $2.50: turn 1 spends ~$1 (under), turn 2
      // accumulates past it → check-in fires in turn 2 only.
      val budget = Some(ConversationBudget(conversationSoft = Some(BigDecimal("2.5"))))
      for {
        convId <- seedConv(budget)
        _ <- runTurn(new BillingProvider(respondAfter = 1), convId, "First task.")
        afterTurn1 <- eventsOf(convId)
        _ <- runTurn(new BillingProvider(respondAfter = 2), convId, "Second task.")
        afterTurn2 <- eventsOf(convId)
      } yield {
        invokesNamed(afterTurn1, "_budget_checkin") shouldBe empty
        val checkins = invokesNamed(afterTurn2, "_budget_checkin")
        withClue(s"turn-2 checkins=${checkins.size}: ") {
          checkins should have size 1
        }
      }
    }

    "refuse to start a turn past the hard ceiling and re-arm via set_budget" in {
      val provider = new BillingProvider(respondAfter = 1)
      for {
        convId <- seedConv(Some(ConversationBudget(conversationHard = Some(BigDecimal("5")))), cost = BigDecimal("6"))
        _ <- {
          TestSigil.setProvider(Task.pure(provider)); TestSigil.setMemoryExtractor(NoExtraction)
          TestSigil.publish(userMessage(convId, "Are you there?"))
        }
        _ <- Task.sleep(1.second)
        blocked <- eventsOf(convId)
        // Raise the ceiling conversationally and try again.
        conv <- TestSigil.withDB(_.conversations.transaction(_.get(convId))).map(_.get)
        ctx = TurnContext(
          sigil = TestSigil,
          chain = List(TestUser, TestAgent),
          conversation = conv,
          turnInput = sigil.conversation.TurnInput(conversationId = convId),
          model = TestSigil.defaultTestModel
        )
        result <- SetBudgetTool.invoke(
          sigil.tool.core.SetBudgetInput(conversationHard = Some(BigDecimal("20"))),
          ToolContext(ctx, Event.id(), SetBudgetTool.name))
        _ <- runTurn(provider, convId, "Are you there now?")
        after <- eventsOf(convId)
      } yield withClue(s"calls-after-blocked=${provider.calls.get()}: ") {
        // The blocked publish ran NO provider call...
        val exhaustion = blocked.collect {
          case m: Message if m.content.exists {
                case t: ResponseContent.Text => t.text.contains("spend ceiling")
                case _ => false
              } => m
        }
        exhaustion should have size 1
        // ...and the raised budget let the next turn run.
        result.text should include("Budget updated")
        provider.calls.get() should be >= 1
        invokesNamed(after, GetMagicNumberTool.name.value) should not be empty
      }
    }
  }

  "request_deescalation" should {

    "step an escalated turn back down and no-op at the base tier" in {
      val strategy = ProviderStrategy.routed(
        default = List(
          ModelCandidate(pricedModelId, supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High))
        )
      )
      for {
        convId <- seedConv(None)
        conv <- TestSigil.withDB(_.conversations.transaction(_.get(convId))).map(_.get)
        ctx = TurnContext(
          sigil = TestSigil,
          chain = List(TestUser, TestAgent),
          conversation = conv,
          turnInput = sigil.conversation.TurnInput(conversationId = convId),
          model = TestSigil.defaultTestModel
        )
        // Seed per-turn routing state, then escalate twice.
        _ <- TestSigil.classifyForRoute(
          strategy,
          ConversationWork,
          conv,
          Some(userMessage(convId, "hard task")),
          ctx)
        up1 <- TestSigil.requestEscalation(convId, "harder than it looked")
        up2 <- TestSigil.requestEscalation(convId, "harder still")
        down1 <- TestSigil.requestDeescalation(convId, "mechanical tail")
        down2 <- TestSigil.requestDeescalation(convId, "mechanical tail")
        down3 <- TestSigil.requestDeescalation(convId, "already at base")
        toolOut <- RequestDeescalationTool.invoke(
          sigil.tool.core.RequestDeescalationInput(reason = "still at base"),
          ToolContext(ctx, Event.id(), RequestDeescalationTool.name))
      } yield {
        up1._2 shouldBe true
        up2._2 shouldBe true
        down1._2 shouldBe true
        down2._2 shouldBe true
        // Base tier reached — nothing left to unwind.
        down3._2 shouldBe false
        toolOut.lowered shouldBe false
        // Two ups then two downs lands back where one up would sit minus one:
        // the classified base tier.
        down2._1 shouldBe down3._1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
