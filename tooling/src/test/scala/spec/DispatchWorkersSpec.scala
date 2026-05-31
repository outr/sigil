package spec

import fabric.Str
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.participant.WorkerParticipantId
import sigil.provider.CodingWork
import sigil.event.Event
import sigil.role.Role
import sigil.tool.{ToolContext, ToolName, ToolResult}
import sigil.tooling.container.ContainerSupport
import sigil.tooling.dispatch.{DispatchWorkersInput, DispatchWorkersOutput, DispatchWorkersTool}

/**
 * Coverage for `dispatch_workers` (sigil #327 — the headless fan-out
 * sibling of the supervised `delegate_task` bridge). The tool fans out
 * one worker agent per container item into its own sub-conversation,
 * returns a synchronous dispatch handle, and publishes a
 * [[sigil.tooling.dispatch.DispatchStarted]] event. The aggregated
 * [[sigil.tooling.dispatch.DispatchCompleted]] lands later once every
 * worker settles (live-integration territory); here we exercise the
 * synchronous handle + the per-item worker sub-conversation wiring —
 * pure conversations + agents, no workflow runtime.
 */
class DispatchWorkersSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  DispatchTestSigil.initFor(getClass.getSimpleName)

  private val tool = new DispatchWorkersTool()

  private def toolCtxFor(conv: Conversation): ToolContext =
    ToolContext(
      TurnContext(
        sigil        = DispatchTestSigil,
        chain        = List(DispatchTestUser),
        conversation = conv,
        turnInput    = TurnInput(conversationId = conv._id),
        model        = DispatchTestSigil.defaultTestModel
      ),
      Event.id(),
      ToolName("dispatch_workers")
    )

  private def freshConv(suffix: String): Task[Conversation] = {
    val convId = Conversation.id(s"dispatch-$suffix-${rapid.Unique()}")
    val topic  = TopicEntry(sigil.conversation.Topic.id(s"topic-$convId"), "test", "test")
    val conv   = Conversation(_id = convId, topics = List(topic))
    DispatchTestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  private def role = Role(
    name        = "refactorer",
    description = "Refactor each file to the new API.",
    workType    = CodingWork
  )

  "dispatch_workers" should {

    "fan out one worker sub-conversation per item, return a handle, and not abort" in {
      for {
        conv             <- freshConv("fanout")
        (containerId, _) <- ContainerSupport.persistItems(
          host = DispatchTestSigil, conversationId = conv._id,
          items = List(Str("file-a.scala"), Str("file-b.scala"))
        )
        result <- tool.executeResult(
          DispatchWorkersInput(itemsId = containerId, workerPrompt = "Refactor this file.", role = role),
          toolCtxFor(conv)
        )
        output = result match {
          case ToolResult.Success(o: DispatchWorkersOutput) => o
          case other                                        => fail(s"expected DispatchWorkersOutput success, got $other")
        }
        children <- DispatchTestSigil.withDB(_.conversations.transaction(_.list))
          .map(_.filter(_.parentConversationId.contains(conv._id)))
      } yield {
        output.total shouldBe 2
        output.workersStarted shouldBe 2  // both fit under the default maxParallel
        output.abortReason shouldBe None
        withClue(s"worker sub-conversations: ${children.map(_._id.value)}: ") {
          children should have size 2
          children.forall(_.participants.exists(_.id.isInstanceOf[WorkerParticipantId])) shouldBe true
          children.forall(_.parentConversationId.contains(conv._id)) shouldBe true
        }
      }
    }

    "abort cleanly when the container resolves to zero items" in {
      for {
        conv             <- freshConv("empty")
        (containerId, _) <- ContainerSupport.persistItems(host = DispatchTestSigil, conversationId = conv._id, items = Nil)
        result <- tool.executeResult(
          DispatchWorkersInput(itemsId = containerId, workerPrompt = "Refactor this file.", role = role),
          toolCtxFor(conv)
        )
      } yield result match {
        case ToolResult.Success(o: DispatchWorkersOutput) =>
          o.total shouldBe 0
          o.workersStarted shouldBe 0
          o.abortReason should not be empty
        case other => fail(s"expected DispatchWorkersOutput success, got $other")
      }
    }
  }

  "tear down" should {
    "dispose DispatchTestSigil" in DispatchTestSigil.shutdown.map(_ => succeed)
  }
}
