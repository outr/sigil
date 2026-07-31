package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.{Event, Message, MessageRole, MessageVisibility, ToolOutcome}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.model.ResponseContent
import sigil.tool.{DiscoverySpec, Effect, LateEmissionException, MutationTargeting, Resolution, TextToolOutput, Tool, ToolContext, ToolIO, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Executor pairing property — for resolutions that succeed, logically
 * fail, throw, emit-then-throw, emit-after-resolution, and produce
 * oversized output: exactly one paired result event settles the
 * invoke, the emit buffer is drained exactly once, the typed output is
 * preserved, and a late emit raises loudly instead of silently
 * dropping the event.
 */
class ToolExecutorPairingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"pairing-${rapid.Unique()}")
  private def turn: TurnContext = TurnContext(
    sigil        = TestSigil,
    chain        = List(TestUser),
    conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
    turnInput    = TurnInput(ConversationView(conversationId = convId)),
    model        = TestSigil.defaultTestModel
  )

  private def spec(n: String): ToolSpec = ToolSpec(
    name = ToolName.parse(n).fold(sys.error, identity),
    description = s"pairing fixture $n",
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("test", n))
  )

  private def emitted(ctx: ToolContext, marker: String): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    role           = MessageRole.Tool,
    content        = Vector(ResponseContent.Text(marker)),
    state          = EventState.Complete,
    visibility     = MessageVisibility.Agents
  )

  private def settleDeltas(signals: List[Signal]): List[ToolDelta] =
    signals.collect { case d: ToolDelta if d.state.contains(EventState.Complete) => d }

  private def markerCount(signals: List[Signal], marker: String): Int =
    signals.count {
      case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString.contains(marker)
      case _          => false
    }

  private abstract class Fixture(n: String) extends Tool {
    type Input  = PairingProbeInput
    type Output = TextToolOutput
    val io: ToolIO[PairingProbeInput, TextToolOutput] = ToolIO.derived[PairingProbeInput, TextToolOutput]
    val spec: ToolSpec = ToolExecutorPairingSpec.this.spec(n)
  }

  "ToolExecutor pairing" should {

    "settle a successful resolution with exactly one result delta carrying the typed output" in {
      val tool = new Fixture("pair_ok") {
        protected def resolve: Resolution[Input, Output] =
          Resolution.Simple((_, _) => Task.pure(TextToolOutput("done")))
      }
      tool.execute(PairingProbeInput(), turn, Event.id()).toList.map { signals =>
        val settles = settleDeltas(signals)
        settles should have size 1
        settles.head.outcome shouldBe Some(ToolOutcome.Success)
        settles.head.output.collect { case t: TextToolOutput => t.text } shouldBe Some("done")
      }
    }

    "settle a logical failure with exactly one Failure result delta" in {
      val tool = new Fixture("pair_fail") {
        protected def resolve: Resolution[Input, Output] =
          Resolution.Explicit((_, _) => Task.pure(ToolResult.failure("nope", hint = Some("try harder"))))
      }
      tool.execute(PairingProbeInput(), turn, Event.id()).toList.map { signals =>
        val settles = settleDeltas(signals)
        settles should have size 1
        val reason = settles.head.outcome.collect { case ToolOutcome.Failure(r, _) => r }.getOrElse("")
        reason should include("nope")
        reason should include("try harder")
      }
    }

    "settle a thrown resolution with exactly one recoverable Failure delta" in {
      val tool = new Fixture("pair_throw") {
        protected def resolve: Resolution[Input, Output] =
          Resolution.Simple((_, _) => Task.error(new RuntimeException("kaboom")))
      }
      tool.execute(PairingProbeInput(), turn, Event.id()).toList.map { signals =>
        val settles = settleDeltas(signals)
        settles should have size 1
        val outcome = settles.head.outcome.collect { case f: ToolOutcome.Failure => f }
        outcome.map(_.reason).getOrElse("") should include("kaboom")
        outcome.map(_.recoverable) shouldBe Some(true)
      }
    }

    "drain emitted events ahead of the failure delta when the body emits then throws" in {
      val tool = new Fixture("pair_emit_throw") {
        protected def resolve: Resolution[Input, Output] = Resolution.Explicit { (_, ctx) =>
          ctx.emit(emitted(ctx, "EMITTED-BEFORE-THROW")).flatMap(_ => Task.error(new RuntimeException("late boom")))
        }
      }
      tool.execute(PairingProbeInput(), turn, Event.id()).toList.map { signals =>
        markerCount(signals, "EMITTED-BEFORE-THROW") shouldBe 1
        val settles = settleDeltas(signals)
        settles should have size 1
        settles.head.outcome.collect { case ToolOutcome.Failure(r, _) => r }.getOrElse("") should include("late boom")
        // The emitted event precedes the settle delta in the stream.
        val emitIdx = signals.indexWhere {
          case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString.contains("EMITTED-BEFORE-THROW")
          case _          => false
        }
        val settleIdx = signals.indexWhere {
          case d: ToolDelta if d.state.contains(EventState.Complete) => true
          case _                                                     => false
        }
        emitIdx should be < settleIdx
      }
    }

    "drain each emitted event exactly once" in {
      val tool = new Fixture("pair_emit_twice") {
        protected def resolve: Resolution[Input, Output] = Resolution.Explicit { (_, ctx) =>
          ctx.emit(emitted(ctx, "MARKER-ONE"))
            .flatMap(_ => ctx.emit(emitted(ctx, "MARKER-TWO")))
            .map(_ => ToolResult.success(TextToolOutput("ok")))
        }
      }
      tool.execute(PairingProbeInput(), turn, Event.id()).toList.map { signals =>
        markerCount(signals, "MARKER-ONE") shouldBe 1
        markerCount(signals, "MARKER-TWO") shouldBe 1
        settleDeltas(signals) should have size 1
      }
    }

    "raise loudly on an emit after the resolution settled — the event is not silently dropped" in {
      val captured = new java.util.concurrent.atomic.AtomicReference[Option[ToolContext]](None)
      val tool = new Fixture("pair_late_emit") {
        protected def resolve: Resolution[Input, Output] = Resolution.Simple { (_, ctx) =>
          captured.set(Some(ctx))
          Task.pure(TextToolOutput("ok"))
        }
      }
      tool.execute(PairingProbeInput(), turn, Event.id()).toList.flatMap { signals =>
        settleDeltas(signals) should have size 1
        val ctx = captured.get().getOrElse(fail("resolution never ran"))
        ctx.emit(emitted(ctx, "TOO-LATE"))
          .map(_ => fail("expected LateEmissionException"))
          .handleError {
            case _: LateEmissionException => Task.pure(succeed)
            case other                    => Task(fail(s"expected LateEmissionException, got $other"))
          }
      }
    }

    "settle an oversized result with one delta preserving the typed output plus an overflow pointer" in {
      val big = "y" * 50000
      val tool = new Fixture("pair_oversized") {
        protected def resolve: Resolution[Input, Output] =
          Resolution.Simple((_, _) => Task.pure(TextToolOutput(big)))
      }
      tool.execute(PairingProbeInput(), turn, Event.id()).toList.map { signals =>
        val settles = settleDeltas(signals)
        settles should have size 1
        settles.head.output.collect { case t: TextToolOutput => t.text } shouldBe Some(big)
        settles.head.overflow shouldBe defined
        settles.head.summary.getOrElse("").length should be < big.length
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

case class PairingProbeInput() extends ToolInput derives RW
