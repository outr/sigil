package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, Message, MessageRole, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{GenerationSettings, Instructions, SafetyPosture}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}
import sigil.tool.ToolContext

/**
 * Coverage for sigil bug #160 (Problem B) — when the caller agent's
 * [[Instructions.posture]] is [[SafetyPosture.Autonomous]], the
 * orchestrator bypasses the `requiresUserConsent` gate instead of
 * forcing the agent to call `record_consent` on itself to clear it.
 *
 * Confirming-posture agents (the framework default) still gate
 * normally — the bypass is opt-in via `Instructions.autonomous()`
 * (or `.withPosture(Autonomous)` on a custom Instructions).
 */
class AutonomousConsentBypassSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class BypassInput(payload: String) extends ToolInput derives RW

  private val ranCount = new java.util.concurrent.atomic.AtomicInteger(0)

  case object ConsentGatedTool extends Tool {
    type Input  = BypassInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[BypassInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName("bypass_demo_tool")
    val description = "A consent-gated demo tool used by the bypass spec."

    override def requiresUserConsent: Boolean = true

    override def executeResult(input: BypassInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task {
        ranCount.incrementAndGet()
        ToolResult.Success(TextToolOutput(s"executed with ${input.payload}"))
      }
  }

  ToolInput.register(RW.static(BypassInput("")))

  private def newConvWithAgent(suffix: String, instructions: Instructions): Task[Conversation] = {
    val convId = Conversation.id(s"bypass-$suffix-${rapid.Unique()}")
    val topic  = TopicEntry(
      id      = sigil.conversation.Topic.id(s"topic-$convId"),
      label   = "test",
      summary = "test"
    )
    val agent = DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = sigil.db.Model.id("test", "bypass"),
      toolNames          = List(ConsentGatedTool.schema.name),
      instructions       = instructions,
      generationSettings = GenerationSettings()
    )
    val conv = Conversation(_id = convId, topics = List(topic), participants = List(agent))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def turnContextFor(conv: Conversation): TurnContext =
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser, TestAgent),
      conversation = conv,
      turnInput    = TurnInput(conversationId = conv._id),
      model = TestSigil.defaultTestModel
    )

  private def dispatch(input: BypassInput, ctx: TurnContext): Task[List[Signal]] = {
    val invokeId = sigil.event.Event.id()
    Orchestrator.dispatchAtomic(ConsentGatedTool, input, ctx, invokeId).toList
  }

  /** Text payloads carried by settling `ToolDelta`s. */
  private def resultTexts(signals: List[Signal]): List[String] =
    signals.collect {
      case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) =>
        d.output.collect { case TextToolOutput(t) => t }
    }.flatten

  "consent gate" should {

    "BYPASS the requiresUserConsent check when the caller agent's posture is Autonomous" in {
      ranCount.set(0)
      for {
        conv <- newConvWithAgent("autonomous", Instructions.autonomous())
        ctx   = turnContextFor(conv)
        evs  <- dispatch(BypassInput("auth-ok"), ctx)
      } yield {
        ranCount.get() shouldBe 1
        // No Failure with "requires user consent" surfaces — the
        // tool executed directly.
        val failures = evs.collect {
          case m: Message if m.role == MessageRole.Tool => m.failureReason.toVector
        }.flatten
        failures.exists(_.toLowerCase.contains("requires user consent")) shouldBe false
        // The tool's typed result is present.
        resultTexts(evs).exists(_.contains("executed with auth-ok")) shouldBe true
      }
    }

    "STILL gate the requiresUserConsent check when the caller agent's posture is Confirming" in {
      ranCount.set(0)
      for {
        conv <- newConvWithAgent("confirming", Instructions())
        ctx   = turnContextFor(conv)
        evs  <- dispatch(BypassInput("blocked"), ctx)
      } yield {
        // Default posture → gate fires → tool does NOT run.
        ranCount.get() shouldBe 0
        val failures = evs.collect {
          case m: Message if m.role == MessageRole.Tool => m.failureReason.toVector
        }.flatten
        failures.exists(_.toLowerCase.contains("requires user consent")) shouldBe true
      }
    }

    "honor Autonomous posture set via withPosture on a hand-built Instructions" in {
      ranCount.set(0)
      val hand = Instructions().withPosture(SafetyPosture.Autonomous)
      for {
        conv <- newConvWithAgent("withposture", hand)
        ctx   = turnContextFor(conv)
        evs  <- dispatch(BypassInput("hand-built"), ctx)
      } yield {
        ranCount.get() shouldBe 1
        resultTexts(evs).exists(_.contains("hand-built")) shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
