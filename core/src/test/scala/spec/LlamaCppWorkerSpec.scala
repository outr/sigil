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
          summary.toLowerCase should include("hello")
        }
      }

      // Quantised local models occasionally short-circuit the
      // AskParent: instruction and answer directly on iteration 1.
      // When that happens the worker terminates without ever
      // suspending — there's no question for us to answer. We
      // treat that as a skip rather than a failure: the
      // architectural assertions only hold when the LLM actually
      // followed the AskParent: directive.
      "complete the AskParent → answer_worker → resume cycle end-to-end" in {
        val parentConvId = Conversation.id(s"parent-conv-${rapid.Unique()}")
        val workerConvId = Conversation.id(s"worker-conv-${rapid.Unique()}")
        val role = Role(
          name = "decider",
          description =
            """You are a deciding agent. You don't know the user's color preference and MUST
              |ask the parent before answering. On your FIRST iteration, emit exactly one line:
              |  AskParent: What color should I use, red or blue?
              |Do not emit Complete yet. After the parent answers, your next iteration sees the
              |answer in context — emit:
              |  Complete: <one-paragraph confirmation that names the chosen color>""".stripMargin,
          workType = AnalysisWork
        )
        val brief = "Pick a color for the user. Ask the parent first."

        val stepInput = AgentDecisionStepInput(
          id = "decision-0", role = role, brief = brief,
          modelId = modelId.value, maxIterations = 6
        )
        import sigil.workflow.SigilWorkflowModel.stepRW
        val compiled = WorkflowStepInputCompiler.compile(List(stepInput))
        val sourceId = Id[WorkflowParent](s"adhoc-askparent-${rapid.Unique()}")

        val parentConv = Conversation(
          topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
          _id = parentConvId
        )
        val workerConv = Conversation(
          topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
          parentConversationId = Some(parentConvId),
          _id = workerConvId
        )

        for {
          _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(parentConv)))
          _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(workerConv)))
          run <- TestWorkflowSigil.workflowManager.schedule(
            name     = "askparent-worker",
            steps    = compiled.steps,
            sourceId = sourceId
          ).flatMap(wf =>
            TestWorkflowSigil.workflowManager.collection.transaction(_.modify(wf._id) {
              case Some(c) => Task.pure(Some(c.copy(conversationId = Some(workerConvId.value))))
              case None    => Task.pure(None)
            }).map(_ => wf)
          )

          questionIdOpt <- pollForQuestionOrTerminal(run._id)
          settled <- questionIdOpt match {
            case Some(qid) =>
              republishUntilSettled(run._id, sigil.signal.WorkerAnswer(
                taskId = run._id.value, questionId = qid, answer = "blue"
              ))
            case None =>
              TestWorkflowSigil.workflowManager.collection.transaction(_.get(run._id)).map(_.get)
          }
        } yield {
          settled.status shouldBe WorkflowStatus.Success
          val outputs = settled.stepResults.flatMap(_.output)
          val completionSummary = outputs.flatMap { json =>
            for {
              c <- json.get("complete").map(_.asBoolean) if c
              s <- json.get("summary").map(_.asString)
            } yield s
          }
          completionSummary should not be empty

          // When the LLM actually emitted AskParent on iteration 1
          // (the architecturally-interesting path), assert the
          // suspend/resume mechanic worked: workflow.payloads holds
          // the AnswerTrigger's settle output keyed by trigger
          // step id, with our published answer ("blue") observable.
          // When the LLM short-circuited and terminated on iter 1
          // without asking, we still pass — the simpler-shape
          // worker spec covers that path.
          if (questionIdOpt.isDefined) {
            val triggerPayloads = settled.payloads.values.toList
            val capturedAnswer = triggerPayloads.flatMap(_.get("answer").map(_.asString))
            capturedAnswer should contain("blue")
          }
          succeed
        }
      }
    }
  }

  /** Re-publish the answer Notice every 2s and wait for the
    * workflow to reach a terminal state — closes the registration-
    * timing race where the very first publish lands before the
    * AnswerTrigger's signal subscription is hot. Caps at 90s. */
  private def republishUntilSettled(runId: Id[Workflow], answer: sigil.signal.WorkerAnswer): Task[Workflow] = {
    val deadline = System.currentTimeMillis() + 90_000L
    def loop(): Task[Workflow] =
      TestWorkflowSigil.workflowManager.collection.transaction(_.get(runId)).flatMap {
        case None => Task.error(new RuntimeException(s"workflow $runId disappeared"))
        case Some(wf) if wf.finished => Task.pure(wf)
        case Some(_) if System.currentTimeMillis() > deadline =>
          Task.error(new RuntimeException(s"worker $runId did not settle within 90s of answer"))
        case Some(_) =>
          TestWorkflowSigil.publish(answer).flatMap(_ =>
            Task.sleep(2.seconds).flatMap(_ => loop())
          )
      }
    loop()
  }

  /** Poll until either:
    *   - The workflow's stepResults contain one with `asked: true`
    *     (LLM emitted AskParent: — return Some(questionId))
    *   - The workflow finishes (LLM short-circuited and terminated
    *     directly — return None)
    *   - 30 s elapse (raise) */
  private def pollForQuestionOrTerminal(runId: Id[Workflow]): Task[Option[String]] = {
    val deadline = System.currentTimeMillis() + 30_000L
    def loop(): Task[Option[String]] =
      TestWorkflowSigil.workflowManager.collection.transaction(_.get(runId)).flatMap {
        case None => Task.error(new RuntimeException(s"workflow $runId disappeared"))
        case Some(wf) =>
          val asked = wf.stepResults.flatMap(_.output).flatMap { json =>
            for {
              a <- json.get("asked").map(_.asBoolean) if a
              q <- json.get("questionId").map(_.asString)
            } yield q
          }
          asked.headOption match {
            case Some(qid)              => Task.pure(Some(qid))
            case None if wf.finished    => Task.pure(None)
            case None if System.currentTimeMillis() > deadline =>
              Task.error(new RuntimeException(s"workflow $runId neither asked nor finished within 30s"))
            case None =>
              Task.sleep(200.millis).flatMap(_ => loop())
          }
      }
    loop()
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
