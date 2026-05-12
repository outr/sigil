package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Message, ToolApproval}
import sigil.orchestrator.Orchestrator
import sigil.signal.EventState
import sigil.tool.core.RecordConsentTool
import sigil.tool.model.{RecordConsentInput, ResponseContent}

/**
 * Coverage for sigil bug #160 (Problem A) — `record_consent` validates
 * the supplied `toolName` against the live registry. Fabricated names
 * (`start_coding`, etc.) no longer persist a useless `ToolApproval`
 * row that pollutes the audit log and never matches a real tool.
 */
class RecordConsentValidationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConv(suffix: String): Task[Conversation] = {
    val convId = Conversation.id(s"consent-validate-$suffix-${rapid.Unique()}")
    val topic  = TopicEntry(
      id      = sigil.conversation.Topic.id(s"topic-$convId"),
      label   = "test",
      summary = "test"
    )
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def turnContextFor(conv: Conversation): TurnContext =
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser, TestAgent),
      conversation = conv,
      turnInput    = TurnInput(conversationId = conv._id)
    )

  "record_consent" should {

    "REFUSE to persist a ToolApproval for an unknown tool name" in {
      for {
        conv <- freshConv("unknown")
        ctx   = turnContextFor(conv)
        evs  <- RecordConsentTool.execute(
                  RecordConsentInput(toolName = "definitely_not_a_real_tool",
                                     approved = true,
                                     reason   = Some("test")),
                  ctx
                ).toList
        persistedApprovals <- TestSigil.withDB(_.events.transaction(_.list)).map { all =>
                                all.collect { case ta: ToolApproval => ta }
                                   .filter(_.toolName.value == "definitely_not_a_real_tool")
                              }
      } yield {
        val failures = evs.collect {
          case m: Message =>
            m.content.collect { case ResponseContent.Failure(reason, _, _) => reason }
        }.flatten
        failures should not be empty
        failures.head.toLowerCase should include("unknown tool")
        persistedApprovals shouldBe empty
      }
    }

    "ALLOW a ToolApproval to persist for a known tool name" in {
      for {
        conv <- freshConv("known")
        ctx   = turnContextFor(conv)
        // Drive via `dispatchAtomic` so the orchestrator stamps
        // `origin` on the Tool-role confirmation Message; direct
        // `execute` bypasses origin-stamping and trips the
        // framework's Tool-role-needs-origin invariant on publish.
        invokeId = sigil.event.Event.id()
        evs  <- Orchestrator.dispatchAtomic(
                  RecordConsentTool,
                  RecordConsentInput(toolName = RecordConsentTool.schema.name.value,
                                     approved = true,
                                     reason   = Some("self-test")),
                  ctx,
                  invokeId
                ).toList
        _    <- Task.sequence(evs.collect { case e: sigil.event.Event => TestSigil.publish(e) })
        approvalsForConv <- TestSigil.withDB(_.events.transaction(_.list)).map { all =>
                              all.collect { case ta: ToolApproval => ta }
                                 .filter(ta => ta.conversationId == conv._id &&
                                               ta.toolName == RecordConsentTool.schema.name)
                            }
      } yield {
        approvalsForConv.size shouldBe 1
        approvalsForConv.head.approved shouldBe true
        val confirmations = evs.collect {
          case m: Message =>
            m.content.collect { case ResponseContent.Text(t) => t }
        }.flatten
        confirmations.exists(_.contains("approved")) shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
