package spec

import fabric.Str
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.event.{Event, ToolOutcome}
import sigil.role.Role
import sigil.signal.ToolDelta
import sigil.tool.output.ToolOutputNode
import sigil.tooling.container.ContainerSupport
import sigil.tooling.dispatch.{DispatchWorkersInput, DispatchWorkersTool}

/**
 * Regression for sigil #288 — `dispatch_workers` refactored as
 * "fan out N delegate_task workers." The tool returns immediately
 * with a [[sigil.tooling.dispatch.DispatchWorkersOutput]] handle
 * and publishes a [[sigil.tooling.dispatch.DispatchStarted]] event
 * into the parent conversation. The aggregated
 * [[sigil.tooling.dispatch.DispatchCompleted]] event lands later
 * once every worker settles (downstream-integration territory —
 * here we exercise the synchronous handle + the no-WorkflowSigil
 * refusal contract).
 */
class DispatchWorkersSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val tool = new DispatchWorkersTool()

  private def turnContextFor(conv: Conversation): TurnContext =
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser, TestAgent),
      conversation = conv,
      turnInput    = TurnInput(conversationId = conv._id),
      model        = TestSigil.defaultTestModel
    )

  private def freshConv(suffix: String): Task[Conversation] = {
    val convId = Conversation.id(s"dispatch-$suffix-${rapid.Unique()}")
    val topic  = TopicEntry(sigil.conversation.Topic.id(s"topic-$convId"), "test", "test")
    val conv   = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  "dispatch_workers (sigil #288 refactor)" should {

    "REQUIRE WorkflowSigil — surface a Failure when the host lacks it" in {
      // TestSigil does NOT mix in WorkflowSigil; the tool must
      // reject the call with a didactic Failure rather than crash.
      for {
        conv <- freshConv("no-workflow")
        _ <- ContainerSupport.persistItems(
          host           = TestSigil,
          conversationId = conv._id,
          items          = List(Str("file-a.scala"), Str("file-b.scala"))
        )
        evs <- tool.execute(
          DispatchWorkersInput(
            itemsId      = Id[ToolOutputNode](rapid.Unique()),
            workerPrompt = "Refactor this file.",
            role         = Role(
              name        = "refactorer",
              description = "Refactor each file to the new API.",
              workType    = sigil.provider.CodingWork
            )
          ),
          turnContextFor(conv),
          Event.id()
        ).toList
      } yield {
        val failures = evs.collect {
          case d: ToolDelta => d.outcome
        }.flatten.collect { case f: ToolOutcome.Failure => f }
        failures should not be empty
        failures.head.reason should include("WorkflowSigil")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
