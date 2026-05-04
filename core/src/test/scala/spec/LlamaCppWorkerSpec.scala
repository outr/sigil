package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, TopicEntry}
import sigil.db.Model
import sigil.provider.AnalysisWork
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.role.Role
import sigil.workflow.{AgentDecisionStepInput, SigilWorkflowModel, WorkflowStepInputCompiler}
import strider.{Workflow, WorkflowParent, WorkflowStatus}

import scala.concurrent.duration.*

/**
 * Live end-to-end coverage for the worker delegation path against
 * the local llama.cpp server. Schedules a workflow with a single
 * [[AgentDecisionStepInput]], waits for the run to settle, and
 * verifies the worker emitted a `Complete:` marker that the
 * framework parsed into a typed step result.
 *
 * Self-skips when `TestSigil.llamaCppHost` is unreachable, matching
 * other `LlamaCpp*Spec.scala` patterns.
 */
class LlamaCppWorkerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)
  TestWorkflowSigil.setProvider(LlamaCppProvider(TestWorkflowSigil, TestSigil.llamaCppHost).singleton)

  override implicit protected val testTimeout: FiniteDuration = 3.minutes

  private val convId = Conversation.id("worker-llamacpp-conv")
  private val modelId = Model.id("qwen3.5-9b-q4_k_m")

  /** Schedule + wait for terminal status, then return the settled run. */
  private def runWorker(role: Role, brief: String): Task[Workflow] = {
    val stepInput = AgentDecisionStepInput(
      id            = "decision-0",
      role          = role,
      brief         = brief,
      modelId       = modelId.value,
      maxIterations = 6
    )
    import sigil.workflow.SigilWorkflowModel.stepRW
    val compiled = WorkflowStepInputCompiler.compile(List(stepInput))

    val sourceId = Id[WorkflowParent](s"adhoc-worker-${rapid.Unique()}")
    for {
      conv <- Task.pure(Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        _id    = convId
      ))
      _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      run <- TestWorkflowSigil.workflowManager.schedule(
        name     = "live-worker",
        steps    = compiled.steps,
        sourceId = sourceId
      ).flatMap(wf =>
        // Stamp conversationId so lifecycle Events / cost projection
        // route through the test conv.
        TestWorkflowSigil.workflowManager.collection.transaction(_.modify(wf._id) {
          case Some(current) => Task.pure(Some(current.copy(conversationId = Some(conv._id.value))))
          case None          => Task.pure(None)
        }).map(_ => wf)
      )
      settled <- waitForTerminal(run._id)
    } yield settled
  }

  /** Poll the workflow run until its status becomes terminal (Success
    * / Failure / Cancelled / TimedOut). 200ms poll, 2-min cap so a
    * stuck run fails the test rather than hanging forever. */
  private def waitForTerminal(runId: Id[Workflow]): Task[Workflow] = {
    val deadline = System.currentTimeMillis() + 120_000L
    def loop(): Task[Workflow] =
      TestWorkflowSigil.workflowManager.collection.transaction(_.get(runId)).flatMap {
        case None => Task.error(new RuntimeException(s"workflow $runId disappeared"))
        case Some(wf) if wf.finished => Task.pure(wf)
        case Some(_) if System.currentTimeMillis() > deadline =>
          Task.error(new RuntimeException(s"worker $runId did not settle within 2 minutes"))
        case Some(_) =>
          Task.sleep(200.millis).flatMap(_ => loop())
      }
    loop()
  }

  private def isReachable: Boolean =
    scala.util.Try {
      val url  = new java.net.URL(TestSigil.llamaCppHost.toString)
      val conn = url.openConnection().asInstanceOf[java.net.HttpURLConnection]
      conn.setConnectTimeout(2000)
      conn.setReadTimeout(2000)
      conn.setRequestMethod("HEAD")
      val ok = conn.getResponseCode < 600
      conn.disconnect()
      ok
    }.getOrElse(false)

  if (!isReachable) {
    "LlamaCppWorkerSpec" should {
      "skip when llama.cpp is unreachable" in pending
    }
  } else {
    "AgentDecisionStep against live llama.cpp" should {
      "settle a single-iteration worker that's told to terminate immediately" in {
        val role = Role(
          name = "echo",
          description = "You produce a single short response. Always emit `Complete: <answer>` on its own line.",
          workType = AnalysisWork
        )
        val brief = "Reply with `Complete: hello-from-worker` and nothing else."
        for {
          settled <- runWorker(role, brief)
        } yield {
          settled.status shouldBe WorkflowStatus.Success
          val finalResult = settled.stepResults.headOption.flatMap(_.output)
          val complete = finalResult.flatMap(_.get("complete").map(_.asBoolean)).getOrElse(false)
          val summary  = finalResult.flatMap(_.get("summary").map(_.asString)).getOrElse("")
          complete shouldBe true
          summary should not be empty
          // Loose match — quantised models sometimes paraphrase. We're
          // verifying the loop captured a meaningful summary, not the
          // exact wording.
          summary.toLowerCase should include("hello")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
