package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, ToolApproval, ToolOutcome}
import sigil.signal.ToolDelta
import sigil.tool.TextToolOutput
import sigil.orchestrator.Orchestrator
import sigil.signal.EventState
import sigil.tool.core.RecordConsentTool
import sigil.tool.model.{RecordConsentInput, ResponseContent}
import sigil.tool.{Resolution, ToolIO}

/**
 * Coverage for sigil bug #160 (Problem A) + bug #285 —
 * `record_consent` validates the supplied `toolName` against the
 * live registry AND refuses tools that don't declare
 * `requiresUserConsent = true`. Fabricated names (`start_coding`,
 * `just_do_it`, etc.) and consent-free tool names (`read_file`)
 * both refuse before persisting; only real consent-gated tools
 * land a [[ToolApproval]] row.
 */
class RecordConsentValidationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Stub consent-gated tool — the happy-path target for the
   * "known + consent-gated" assertion below.
   */
  private object ConsentGatedStub extends sigil.tool.Tool {
    type Input = RecordConsentInput
    type Output = TextToolOutput
    val io: ToolIO[RecordConsentInput, TextToolOutput] = ToolIO.derived[RecordConsentInput, TextToolOutput]
    override val name = sigil.tool.ToolName("load_claude_state")
    override val description = "Stub consent-gated tool — for RecordConsentValidationSpec."
    val spec: sigil.tool.ToolSpec = sigil.tool.ToolSpec(
      name = name,
      description = description,
      profile = sigil.tool.ToolProfile(
        effect = sigil.tool.Effect.Mutating(sigil.tool.MutationTargeting.none),
        gates = sigil.tool.ToolGates(consent = Some(sigil.tool.ConsentSpec("Allow this test tool to run?")))
      ),
      discovery = sigil.tool.DiscoverySpec(keywords = Set("test", "consent"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

    private def executeResult(input: RecordConsentInput, ctx: sigil.tool.ToolContext) =
      Task.pure(sigil.tool.ToolResult.success(TextToolOutput("")))
  }

  /**
   * Stub tool that exists but doesn't require consent — the
   * "REFUSE consent-free" assertion below targets this.
   */
  private object ConsentFreeStub extends sigil.tool.Tool {
    type Input = RecordConsentInput
    type Output = TextToolOutput
    val io: ToolIO[RecordConsentInput, TextToolOutput] = ToolIO.derived[RecordConsentInput, TextToolOutput]
    override val name = sigil.tool.ToolName("read_file")
    override val description = "Stub tool that doesn't need consent."
    val spec: sigil.tool.ToolSpec = sigil.tool.ToolSpec(
      name = name,
      description = description,
      profile = sigil.tool.ToolProfile(effect = sigil.tool.Effect.Mutating(sigil.tool.MutationTargeting.none)),
      discovery = sigil.tool.DiscoverySpec(keywords = Set("test", "consent"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

    private def executeResult(input: RecordConsentInput, ctx: sigil.tool.ToolContext) =
      Task.pure(sigil.tool.ToolResult.success(TextToolOutput("")))
  }

  TestSigil.setToolFinder(sigil.tool.InMemoryToolFinder(List(ConsentGatedStub, ConsentFreeStub)))

  private def freshConv(suffix: String): Task[Conversation] = {
    val convId = Conversation.id(s"consent-validate-$suffix-${rapid.Unique()}")
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

  "record_consent" should {

    "REFUSE to persist a ToolApproval for an unknown tool name" in {
      for {
        conv <- freshConv("unknown")
        ctx = turnContextFor(conv)
        evs <- RecordConsentTool.execute(
          RecordConsentInput(toolName = "definitely_not_a_real_tool", approved = true, reason = Some("test")),
          ctx,
          Event.id()
        ).toList
        persistedApprovals <- TestSigil.withDB(_.events.transaction(_.list)).map { all =>
          all.collect { case ta: ToolApproval => ta }
            .filter(_.toolName.value == "definitely_not_a_real_tool")
        }
      } yield {
        val failures = evs.collect {
          case d: ToolDelta =>
            d.outcome.collect { case ToolOutcome.Failure(reason, _) => reason }.toVector
        }.flatten
        failures should not be empty
        failures.head.toLowerCase should include("unknown tool")
        persistedApprovals shouldBe empty
      }
    }

    "ALLOW a ToolApproval to persist for a known consent-gated tool" in {
      for {
        conv <- freshConv("known")
        ctx = turnContextFor(conv)
        // Drive via `dispatchAtomic` so the orchestrator stamps
        // `origin` on the Tool-role confirmation Message; direct
        // `execute` bypasses origin-stamping and trips the
        // framework's Tool-role-needs-origin invariant on publish.
        invokeId = sigil.event.Event.id()
        evs <- Orchestrator.dispatchAtomic(
          RecordConsentTool,
          RecordConsentInput(toolName = ConsentGatedStub.name.value, approved = true, reason = Some("self-test")),
          ctx,
          invokeId
        ).toList
        _ <- Task.sequence(evs.collect { case e: sigil.event.Event => TestSigil.publish(e) })
        approvalsForConv <- TestSigil.withDB(_.events.transaction(_.list)).map { all =>
          all.collect { case ta: ToolApproval => ta }
            .filter(ta =>
              ta.conversationId == conv._id &&
                ta.toolName == ConsentGatedStub.name)
        }
      } yield {
        approvalsForConv.size shouldBe 1
        approvalsForConv.head.approved shouldBe true
        val confirmations = evs.collect {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) =>
            d.output.collect { case TextToolOutput(t) => t }
        }.flatten
        confirmations.exists(_.contains("approved")) shouldBe true
      }
    }

    "REFUSE to persist a ToolApproval for a tool that doesn't require consent (#285)" in {
      for {
        conv <- freshConv("no-consent-needed")
        ctx = turnContextFor(conv)
        evs <- RecordConsentTool.execute(
          RecordConsentInput(toolName = ConsentFreeStub.name.value, approved = true, reason = Some("habit; not actually consent-gated")),
          ctx,
          Event.id()
        ).toList
        persistedApprovals <- TestSigil.withDB(_.events.transaction(_.list)).map { all =>
          all.collect { case ta: ToolApproval => ta }
            .filter(_.toolName == ConsentFreeStub.name)
        }
      } yield {
        val failures = evs.collect {
          case d: ToolDelta =>
            d.outcome.collect { case ToolOutcome.Failure(reason, _) => reason }.toVector
        }.flatten
        failures should not be empty
        failures.head should include(ConsentFreeStub.name.value)
        failures.head.toLowerCase should include("does not require user consent")
        persistedApprovals shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
