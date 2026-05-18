package spec

import fabric.io.JsonParser
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.Message
import sigil.signal.{Signal, WorkerAnswer}
import sigil.tool.model.{AnswerWorkerInput, ResponseContent}
import sigil.tool.util.AnswerWorkerTool
import sigil.workflow.WorkflowTrigger
import sigil.workflow.trigger.AnswerTrigger

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Coverage for the suspend/resume primitives that compose into
 * `ask_parent` / `answer_worker`:
 *
 *   - [[WorkerAnswer]] Notice round-trips through the polymorphic
 *     Signal RW
 *   - [[AnswerTrigger]] type round-trips through the polymorphic
 *     Trigger RW
 *   - [[AnswerWorkerTool]] publishes a `WorkerAnswer` Notice into
 *     the host Sigil's signal stream when invoked, with the
 *     `taskId` / `questionId` / `answer` fields preserved
 *
 * The end-to-end suspend/resume (TriggerStep waiting on
 * AnswerTrigger, parent agent firing answer_worker, worker run
 * resuming) is covered by the integration suite that drives a real
 * workflow run.
 */
class AnswerTriggerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // TestSigil doesn't mix in WorkflowSigil so the WorkflowTrigger
  // poly isn't populated for the framework-shipped triggers. Register
  // AnswerTrigger manually so the round-trip test has a discriminator
  // to find.
  WorkflowTrigger.register(summon[RW[AnswerTrigger]])

  private val convId = Conversation.id("answer-trigger-spec")

  private def turnContext(): TurnContext = {
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id = convId
    )
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser),
      conversation = conv,
      turnInput = TurnInput(conversationId = convId)
    )
  }

  "WorkerAnswer Notice" should {
    "round-trip through the Signal poly" in {
      val n: Signal = WorkerAnswer(taskId = "wf-1", questionId = "q1", answer = "go")
      val rw = summon[RW[Signal]]
      rw.write(rw.read(n)) shouldBe n
      rapid.Task.pure(succeed)
    }
  }

  "AnswerTrigger" should {
    "round-trip through the WorkflowTrigger poly" in {
      val t: WorkflowTrigger = AnswerTrigger(taskId = "wf-1", questionId = "q1")
      val rw = summon[RW[WorkflowTrigger]]
      rw.write(rw.read(t)) shouldBe t
      rapid.Task.pure(succeed)
    }
  }

  "AnswerWorkerTool" should {
    "publish a WorkerAnswer Notice carrying the supplied answer" in {
      val received = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      // Subscribe before invoking so the publish lands while the
      // subscription is hot.
      TestSigil.signals
        .evalMap(s => rapid.Task { received.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(60)

      val input = AnswerWorkerInput(taskId = "wf-1", questionId = "q1", answer = "go ahead with OAuth")
      AnswerWorkerTool.execute(input, turnContext()).toList.map { events =>
        running = false
        // Tool emits a confirmation Message + the framework also
        // broadcasts the WorkerAnswer notice through `signals`.
        val toolOk = events.collectFirst { case m: Message =>
          m.content.collectFirst { case ResponseContent.Text(t) => t }
        }.flatten.map(JsonParser(_)).flatMap(_.get("ok").map(_.asBoolean))
        toolOk shouldBe Some(true)

        Thread.sleep(80)
        val answer = {
          import scala.jdk.CollectionConverters.*
          received.iterator().asScala.collectFirst { case w: WorkerAnswer => w }
        }
        answer.map(_.taskId) shouldBe Some("wf-1")
        answer.map(_.questionId) shouldBe Some("q1")
        answer.map(_.answer) shouldBe Some("go ahead with OAuth")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
