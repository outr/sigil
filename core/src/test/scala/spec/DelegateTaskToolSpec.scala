package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.ToolOutcome
import sigil.provider.AnalysisWork
import sigil.role.Role
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.model.DelegateTaskInput
import sigil.tool.util.DelegateTaskTool
import sigil.event.Event

/**
 * Coverage for `delegate_task` against a vanilla Sigil — the tool
 * detects the missing [[sigil.workflow.WorkflowSigil]] mixin and
 * returns a structured error rather than crashing. End-to-end
 * worker spawning (real conversation creation, real workflow
 * scheduling, real LLM round-trip) lives in the worker-flow
 * integration spec that ships alongside the worker-iteration
 * machinery in subsequent phase-2 commits.
 */
class DelegateTaskToolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("delegate-task-spec")

  private def turnContext(): TurnContext = {
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id    = convId
    )
    TurnContext(
      sigil            = TestSigil,
      chain            = List(TestUser),
      conversation     = conv,
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  private def sampleInput: DelegateTaskInput = DelegateTaskInput(
    role = Role(
      name = "researcher",
      description = "Research and synthesize.",
      workType = AnalysisWork
    ),
    brief = "Find recent papers on RAG",
    modelId = "anthropic/claude-sonnet-4-6"
  )

  private def failureText(signals: List[Signal]): String =
    signals.collectFirst {
      case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) =>
        d.summary.getOrElse(d.outcome.collect { case ToolOutcome.Failure(r, _) => r }.getOrElse(""))
    }.getOrElse("")

  "DelegateTaskInput" should {
    "round-trip through fabric RW" in {
      import fabric.rw.*
      val rw = summon[RW[DelegateTaskInput]]
      rw.write(rw.read(sampleInput)) shouldBe sampleInput
      rapid.Task.pure(succeed)
    }
  }

  "DelegateTaskTool" should {
    "return a structured error when the host Sigil doesn't mix in WorkflowSigil" in {
      DelegateTaskTool.execute(sampleInput, turnContext(), Event.id()).toList.map { signals =>
        val text = failureText(signals)
        text should include("WorkflowSigil")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
