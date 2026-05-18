package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, Message, MessageRole, ToolApproval, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.signal.{EventState, Signal}
import sigil.tool.{InMemoryToolFinder, ToolInput, ToolName, TypedTool}
import sigil.tool.core.RecordConsentTool
import sigil.tool.model.{RecordConsentInput, ResponseContent}

/**
 * Coverage for sigil bug #83 — tools that declare
 * `requiresUserConsent = true` are gated by the orchestrator
 * until a [[ToolApproval]] event records the user's decision
 * for `(toolName, conversationId)`.
 *
 * Verifies:
 *   1. No record exists → tool is REFUSED with a Tool-role
 *      Failure Message instructing the agent to call
 *      `record_consent`. Tool's `executeTyped` does NOT run.
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

  case object GatedTool
    extends TypedTool[GatedInput](
      name = ToolName("gated_demo_tool"),
      description = "A consent-gated demo tool used by the spec."
    ) {
    override def paginate: Boolean = false

    override def requiresUserConsent: Boolean = true
    override protected def executeTyped(input: GatedInput, ctx: TurnContext): Stream[Event] = {
      invocations.incrementAndGet()
      Stream.emit[Event](Message(
        participantId = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId = ctx.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(s"ran with ${input.payload}")),
        role = MessageRole.Tool,
        state = EventState.Complete
      ))
    }
  }

  // No-consent tool — exercises the fast path.
  case class FreeInput(payload: String) extends ToolInput derives RW

  case object FreeTool
    extends TypedTool[FreeInput](
      name = ToolName("free_demo_tool"),
      description = "A no-consent demo tool — should always dispatch."
    ) {
    override def paginate: Boolean = false
    override protected def executeTyped(input: FreeInput, ctx: TurnContext): Stream[Event] =
      Stream.emit[Event](Message(
        participantId = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId = ctx.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(s"free ran with ${input.payload}")),
        role = MessageRole.Tool,
        state = EventState.Complete
      ))
  }

  ToolInput.register(RW.static(GatedInput("")), RW.static(FreeInput("")))

  // Sigil bug #160 — `record_consent` validates `toolName` against
  // the registry, so the spec's in-test `GatedTool` / `FreeTool` need
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
      turnInput = TurnInput(conversationId = conv._id)
    )

  /**
   * Drive `tool.execute(input, ctx)` through the orchestrator's
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
        val text =
          (refusal.failureReason.toVector ++ refusal.content.collect {
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
        // confirmation Message (#84). Direct `execute` bypasses
        // origin-stamping and trips the framework's #64 invariant.
        recordSignals <- dispatch(
          RecordConsentTool,
          RecordConsentInput(
            toolName = GatedTool.name.value,
            approved = true,
            reason = Some("user said yes")),
          ctx)
        _ <- Task.sequence(recordSignals.collect { case ev: Event => TestSigil.publish(ev) })
        // Now dispatch the gated tool.
        signals <- dispatch(GatedTool, GatedInput("hello"), ctx)
      } yield {
        invocations.get() shouldBe 1
        val msg = signals.collectFirst {
          case m: Message => m
        }.getOrElse(fail("expected Message"))
        val text = msg.content.collect { case ResponseContent.Text(t) => t }.mkString
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
          RecordConsentInput(
            toolName = GatedTool.name.value,
            approved = false,
            reason = Some("user explicitly declined import")),
          ctx)
        _ <- Task.sequence(recordSignals.collect { case ev: Event => TestSigil.publish(ev) })
        signals <- dispatch(GatedTool, GatedInput("nope"), ctx)
      } yield {
        invocations.get() shouldBe 0
        val refusal = signals.collectFirst {
          case m: Message if m.role == MessageRole.Tool => m
        }.getOrElse(fail("expected refusal"))
        val text =
          (refusal.failureReason.toVector ++ refusal.content.collect {
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
        val msg = signals.collectFirst { case m: Message => m }.getOrElse(fail("expected Message"))
        val text = msg.content.collect { case ResponseContent.Text(t) => t }.mkString
        text should include("free ran with ok")
      }
    }
  }

  "tear down" should {
    "clear the per-spec ToolFinder override" in Task { TestSigil.clearToolFinder(); succeed }
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
