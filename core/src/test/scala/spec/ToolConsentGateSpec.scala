package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, Message, MessageRole, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.{
  ConsentSpec,
  DiscoverySpec,
  Effect,
  InMemoryToolFinder,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  ToolGates,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolSpec
}
import sigil.tool.ToolContext
import sigil.tool.core.RecordConsentTool
import sigil.tool.model.{RecordConsentInput, ResponseContent}

/**
 * Coverage for tools that declare
 * `requiresUserConsent = true` and are gated by the orchestrator
 * until a [[ToolApproval]] event records the user's decision
 * for `(toolName, conversationId)`.
 *
 * Verifies:
 *   1. No record exists → tool is REFUSED with a Tool-role
 *      Failure Message instructing the agent to call
 *      `record_consent`. Tool's `executeOutput` does NOT run.
 *   2. `record_consent(approved=true)` records an approved
 *      ToolApproval; subsequent dispatch proceeds.
 *   3. `record_consent(approved=false)` records a declined
 *      ToolApproval; subsequent dispatch refuses with the
 *      decline reason in the failure message.
 *   4. Tool without `requiresUserConsent` (default `false`)
 *      runs without any approval record — no regression.
 */
class ToolConsentGateSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // -- a tool that records when it actually ran --

  case class GatedInput(payload: String) extends ToolInput derives RW

  /**
   * Tracks every successful execute() invocation so the spec
   * can assert "the tool didn't run when refused."
   */
  private val invocations = new java.util.concurrent.atomic.AtomicInteger(0)

  case object GatedTool extends sigil.tool.Tool {
    type Input = GatedInput
    type Output = TextToolOutput
    val io: ToolIO[GatedInput, TextToolOutput] = ToolIO.derived[GatedInput, TextToolOutput]

    override val name = ToolName("gated_demo_tool")
    override val description = "A consent-gated demo tool used by the spec."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(
        effect = Effect.Mutating(MutationTargeting.none),
        gates = ToolGates(consent = Some(ConsentSpec("Allow this test tool to run?")))
      ),
      discovery = DiscoverySpec(keywords = Set("test", "gated"))
    )

    protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

    private def executeOutput(input: GatedInput, ctx: ToolContext): Task[TextToolOutput] = Task {
      invocations.incrementAndGet()
      TextToolOutput(s"ran with ${input.payload}")
    }
  }

  // No-consent tool — exercises the fast path.
  case class FreeInput(payload: String) extends ToolInput derives RW

  case object FreeTool extends sigil.tool.Tool {
    type Input = FreeInput
    type Output = TextToolOutput
    val io: ToolIO[FreeInput, TextToolOutput] = ToolIO.derived[FreeInput, TextToolOutput]

    override val name = ToolName("free_demo_tool")
    override val description = "A no-consent demo tool — should always dispatch."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "free"))
    )

    protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

    private def executeOutput(input: FreeInput, ctx: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput(s"free ran with ${input.payload}"))
  }

  ToolInput.register(RW.static(GatedInput("")), RW.static(FreeInput("")))

  // The spec's in-test `GatedTool` / `FreeTool` need
  // to be discoverable for the dispatch paths that record consent
  // via the real tool. Override the finder so byName succeeds for
  // both; cleared in tear-down so other specs see the default
  // `DbToolFinder`.
  TestSigil.setToolFinder(InMemoryToolFinder(List(GatedTool, FreeTool, RecordConsentTool)))

  private def newConv(suffix: String): Task[Conversation] = {
    val convId = Conversation.id(s"consent-$suffix-${rapid.Unique()}")
    val topic = TopicEntry(
      id = sigil.conversation.Topic.id(s"topic-$convId"),
      label = "test",
      summary = "test"
    )
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def turnContextFor(conv: Conversation): TurnContext =
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conv,
      turnInput = TurnInput(conversationId = conv._id),
      model = TestSigil.defaultTestModel
    )

  /**
   * Drive `tool.execute(input, ctx, Event.id())` through the orchestrator's
   * consent + precondition gates — same path the agent loop
   * uses for atomic dispatches. Returns the resulting signals.
   */
  private def dispatch(tool: sigil.tool.Tool, input: ToolInput, ctx: TurnContext): Task[List[Signal]] = {
    val invokeId = sigil.event.Event.id()
    Orchestrator.dispatchAtomic(tool, input, ctx, invokeId).toList
  }

  "Tool consent gate (#83)" should {

    "REFUSE a `requiresUserConsent` tool when no ToolApproval exists" in {
      invocations.set(0)
      for {
        conv <- newConv("no-record")
        ctx = turnContextFor(conv)
        signals <- dispatch(GatedTool, GatedInput("hi"), ctx)
      } yield {
        invocations.get() shouldBe 0
        val refusal = signals.collectFirst {
          case m: Message if m.role == MessageRole.Tool => m
        }.getOrElse(fail("expected Tool-role refusal Message"))
        val text = (refusal.failureReason.toVector ++ refusal.content.collect {
          case ResponseContent.Text(t) => t
        }).mkString("\n")
        text should include("requires user consent")
        text should include("record_consent")
      }
    }

    "PROCEED after `record_consent(approved=true)` is recorded" in {
      invocations.set(0)
      for {
        conv <- newConv("approved")
        ctx = turnContextFor(conv)
        // Drive record_consent through dispatchAtomic so the
        // orchestrator stamps `origin` on the Tool-role
        // confirmation Message. Direct `execute` bypasses
        // origin-stamping and trips the framework's invariant.
        recordSignals <- dispatch(
          RecordConsentTool,
          RecordConsentInput(toolName = GatedTool.name.value, approved = true, reason = Some("user said yes")),
          ctx)
        _ <- Task.sequence(recordSignals.collect { case ev: Event => TestSigil.publish(ev) })
        // Now dispatch the gated tool.
        signals <- dispatch(GatedTool, GatedInput("hello"), ctx)
      } yield {
        invocations.get() shouldBe 1
        // A successful tool settles its ToolInvoke via a ToolDelta
        // carrying the typed TextToolOutput payload.
        val text = signals.collectFirst {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) =>
            d.output.collect { case TextToolOutput(t) => t }
        }.flatten.getOrElse(fail("expected a Success ToolDelta with TextToolOutput"))
        text should include("ran with hello")
      }
    }

    "REFUSE with the decline reason after `record_consent(approved=false)`" in {
      invocations.set(0)
      for {
        conv <- newConv("declined")
        ctx = turnContextFor(conv)
        recordSignals <- dispatch(
          RecordConsentTool,
          RecordConsentInput(toolName = GatedTool.name.value, approved = false, reason = Some("user explicitly declined import")),
          ctx)
        _ <- Task.sequence(recordSignals.collect { case ev: Event => TestSigil.publish(ev) })
        signals <- dispatch(GatedTool, GatedInput("nope"), ctx)
      } yield {
        invocations.get() shouldBe 0
        val refusal = signals.collectFirst {
          case m: Message if m.role == MessageRole.Tool => m
        }.getOrElse(fail("expected refusal"))
        val text = (refusal.failureReason.toVector ++ refusal.content.collect {
          case ResponseContent.Text(t) => t
        }).mkString("\n")
        text should include("previously declined")
        text should include("user explicitly declined import")
      }
    }

    "always dispatch a tool that does NOT require consent (regression)" in {
      for {
        conv <- newConv("free-tool")
        ctx = turnContextFor(conv)
        signals <- dispatch(FreeTool, FreeInput("ok"), ctx)
      } yield {
        val text = signals.collectFirst {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) =>
            d.output.collect { case TextToolOutput(t) => t }
        }.flatten.getOrElse(fail("expected a Success ToolDelta with TextToolOutput"))
        text should include("free ran with ok")
      }
    }
  }

  "tear down" should {
    "clear the per-spec ToolFinder override" in Task { TestSigil.clearToolFinder(); succeed }
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
