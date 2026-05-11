package sigil.workflow

import fabric.{Json, bool, num, obj, str}
import fabric.rw.*
import lightdb.id.Id
import lightdb.progress.ProgressManager
import rapid.Task
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, MessageVisibility}
import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent, TokenUsage}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.workflow.trigger.AnswerTrigger
import strider.Workflow
import strider.step.{Job, JobContext, Step}

/**
 * Strider executor for [[AgentDecisionStepInput]]. Runs one LLM
 * round-trip via [[sigil.Sigil.providerFor]] using the resolved
 * `modelId`, with `role.description` (plus a worker-loop coda)
 * as system prompt and the brief + accumulated reasoning as the
 * user prompt.
 *
 * **Loop shape (v3 — multi-iteration ReAct + ask_parent).** The LLM
 * response is scanned for marker lines:
 *
 *   - `Complete: <summary>` — terminate, capture summary
 *   - `AskParent: <question>` — suspend the run on an
 *     [[AnswerTrigger]] and post the question into the worker
 *     conversation's parent (the user-facing conversation) as a
 *     hidden-from-user `Message`. The parent agent fires on that
 *     Message, decides whether to answer from context or escalate
 *     to the user, and eventually calls `answer_worker` which
 *     publishes a [[sigil.signal.WorkerAnswer]] Notice that fires
 *     the trigger — the worker resumes
 *   - no marker — append the next [[AgentDecisionStepInput]] with
 *     this iteration's reasoning folded into the chain (regular
 *     ReAct continuation)
 *
 * `iteration >= maxIterations` is a hard runaway cap; the worker
 * settles with `exhausted = true` on the boundary regardless of
 * marker.
 */
final case class SigilAgentDecisionStep(input: AgentDecisionStepInput,
                                        id: Id[Step] = Step.id()) extends Job[Json] derives RW {
  override def name: String = input.name.getOrElse(input.id)

  /** Default execute path — used when invoked outside the runner
    * (e.g., direct Strider scheduling without contextualized
    * dispatch). Falls back to opening a separate transaction for
    * `updateSteps`, which races with the runner's post-execute
    * modify (bug #5). The contextualized path below threads the
    * runner's txn correctly and is the production code path. */
  override def execute(workflow: Workflow, pm: ProgressManager): Task[Json] =
    runIteration(workflow, ctx = None)

  /** The runner's preferred entry point — receives the runner's
    * own [[JobContext]] so any `updateSteps` calls thread through
    * the same transaction the runner's post-execute modify will
    * read. Closes bug #5. */
  override def executeToJsonContextualized(workflow: Workflow,
                                           pm: ProgressManager,
                                           ctx: JobContext): Task[Json] =
    runIteration(workflow, ctx = Some(ctx))

  private def runIteration(workflow: Workflow, ctx: Option[JobContext]): Task[Json] = {
    val host = WorkflowHost.get
    // Bug #65 — `input.modelId` is required on AgentDecisionStepInput
    // by construction, but if it's empty (legacy data, hand-built
    // step with placeholder), fall back to the workflow-level
    // default sourced from `workflow.variables` under
    // [[SigilWorkflowVariables.DefaultModelId]]. Lets workflow
    // authors pin the model once at workflow creation rather
    // than threading it through every worker delegation.
    val resolvedModelId =
      Some(input.modelId.trim).filter(_.nonEmpty)
        .orElse(SigilWorkflowVariables.defaultModelIdOf(workflow))
        .getOrElse(input.modelId)
    val modelId = Id[Model](resolvedModelId)

    // Resolve the worker's role-supplied tool roster. Each name
    // gets looked up via the host's tool finder; missing tools are
    // silently skipped (warned) so a bad `toolNames` entry doesn't
    // crash the worker. `complete_task` is always appended.
    SigilAgentDecisionStep.resolveTools(host, input.toolNames).flatMap { workerTools =>
      val toolsForRequest = (workerTools :+ sigil.tool.util.CompleteTaskTool).toVector
      host.providerFor(modelId, Nil).flatMap { provider =>
        val answersFromParent = SigilAgentDecisionStep.extractParentAnswers(workflow)
        val systemPrompt = SigilAgentDecisionStep.buildSystemPrompt(input)
        val userPrompt   = SigilAgentDecisionStep.buildUserPrompt(input, answersFromParent)
        // `maxOutputTokens = 2000` — workers run on reasoning models
        // (Qwen3.6-A3B, DeepSeek-R1, o-series) that burn output
        // tokens on internal thinking before emitting the
        // `complete_task` tool call OR the `Complete: <summary>`
        // marker. The provider's default (often 512-1024) cut these
        // models off mid-think and the worker never settled — every
        // iteration looped forward without termination signal. 2000
        // covers the worst-case thinking budget plus a multi-paragraph
        // summary; workers whose body genuinely needs more
        // override via `AgentDecisionStepInput.maxOutputTokens` (TODO
        // — surface as input field).
        val request = OneShotRequest(
          modelId            = modelId,
          systemPrompt       = systemPrompt,
          userPrompt         = userPrompt,
          generationSettings = GenerationSettings(maxOutputTokens = Some(2000)),
          tools              = toolsForRequest
        )
        val acc = new java.lang.StringBuilder
        val usageRef = new java.util.concurrent.atomic.AtomicReference[Option[TokenUsage]](None)
        val toolCompletionRef = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)
        // Capture EVERY tool call the LLM made during this turn —
        // complete_task short-circuits termination, all others get
        // dispatched against the host's tool registry and their
        // results folded into the next iteration's priorReasoning.
        val otherCalls = new java.util.concurrent.ConcurrentLinkedQueue[(String, sigil.tool.ToolInput)]()
        provider(request).evalMap {
          case ProviderEvent.TextDelta(t)            => Task { acc.append(t); () }
          case ProviderEvent.ContentBlockDelta(_, t) => Task { acc.append(t); () }
          case ProviderEvent.Usage(u)                => Task { usageRef.set(Some(u)); () }
          case ProviderEvent.ToolCallComplete(_, ti: sigil.tool.model.CompleteTaskInput) =>
            Task { toolCompletionRef.set(Some(ti.summary)); () }
          case ProviderEvent.ToolCallStart(callId, name) =>
            // Capture the name keyed by callId so the matching
            // ToolCallComplete can route. We hold name + input at
            // ToolCallComplete time anyway, but ToolCallStart isn't
            // load-bearing — we just need ToolCallComplete to know
            // which tool was called. Strider's orchestrator already
            // pairs them, so the input arrives typed via inputRW.
            Task.unit
          case ProviderEvent.ToolCallComplete(_, ti) =>
            // A non-complete_task tool call. Look up by class —
            // the orchestrator already decoded the args via the
            // tool's inputRW, so `ti` is the typed input. We need
            // to find the tool whose inputRW produced this type and
            // dispatch.
            Task { otherCalls.add((ti.getClass.getName, ti)); () }
          case _ => Task.unit
        }.drain.flatMap { _ =>
          val response = acc.toString
          toolCompletionRef.get() match {
            // LLM called complete_task as a structured tool — settle
            // immediately with its supplied summary.
            case Some(summary) =>
              Task.pure(SigilAgentDecisionStep.withUsage(obj(
                "complete"  -> bool(true),
                "summary"   -> str(summary),
                "iteration" -> num(input.iteration),
                "exhausted" -> bool(false)
              ), usageRef.get()))
            // Tool calls (non-complete_task) — dispatch each, fold
            // results into priorReasoning, continue iterating.
            case None if !otherCalls.isEmpty =>
              dispatchToolCallsAndContinue(host, workflow, response, otherCalls, workerTools, ctx).map { json =>
                SigilAgentDecisionStep.withUsage(json, usageRef.get())
              }
            // Fall through to marker-based handling for `Complete:` /
            // `AskParent:` / `Report:` / `Status:` / continuation.
            case None =>
              decideNext(host, workflow, response, usageRef.get(), ctx)
          }
        }
      }
    }
  }

  /** Dispatch each captured non-complete_task tool call against the
    * worker's tool roster, accumulate their text results, fold them
    * into the next iteration's `priorReasoning`, and append a
    * follow-up [[AgentDecisionStepInput]] to the run. */
  private def dispatchToolCallsAndContinue(host: sigil.Sigil,
                                           workflow: Workflow,
                                           response: String,
                                           calls: java.util.concurrent.ConcurrentLinkedQueue[(String, sigil.tool.ToolInput)],
                                           workerTools: List[sigil.tool.Tool],
                                           ctx: Option[JobContext]): Task[Json] = host match {
    case ws: WorkflowSigil =>
      import scala.jdk.CollectionConverters.*
      val callList = calls.iterator().asScala.toList
      // Build a map from input class name → tool that produces that input,
      // so we can dispatch by-input-type. (The orchestrator's typed input
      // arrives as the result of `tool.inputRW.write(json)`, so its class
      // is the tool's input case-class.)
      val byInputClass: Map[String, sigil.tool.Tool] = workerTools.map { t =>
        // `inputRW.definition.className` is the canonical fabric class name;
        // the input we got back has matching getClass.getName.
        val cls = t.inputRW.definition.className.getOrElse(t.name.value)
        cls -> t
      }.toMap
      SyntheticTurnContext.build(host, workflow).flatMap { tcx =>
        val toolEvents: Task[List[String]] = rapid.Task.sequence(callList.map { case (className, ti) =>
          // Match by input-class against the registered worker tools.
          val toolOpt = byInputClass.get(className).orElse {
            // Fallback: linear scan accepting any tool whose
            // inputRW class name matches. Covers the case where
            // the dispatcher captured a slightly different class
            // representation (boxed primitives, etc.).
            workerTools.find(_.inputRW.definition.className.contains(className))
          }
          toolOpt match {
            case Some(t) =>
              t.execute(ti, tcx).toList.map { evs =>
                val text = evs.collect { case m: sigil.event.Message =>
                  m.content.collect { case sigil.tool.model.ResponseContent.Text(s) => s }.mkString
                }.filter(_.nonEmpty).mkString("\n")
                s"[Tool ${t.name.value}] $text"
              }
            case None =>
              rapid.Task.pure(s"[Tool $className] (no matching tool in worker roster — call dropped)")
          }
        })
        toolEvents.flatMap { results =>
          val toolBlock = results.mkString("\n\n")
          val priorReasoning = input.priorReasoning :+ s"$response\n\n--- Tool results ---\n$toolBlock"
          val nextStep = input.copy(
            id              = s"decision-${input.iteration + 1}",
            iteration       = input.iteration + 1,
            priorReasoning  = priorReasoning
          )
          import sigil.workflow.SigilWorkflowModel.stepRW
          val compiled = WorkflowStepInputCompiler.compile(List(nextStep))
          val newSteps = workflow.steps ++ compiled.steps
          val appendTask = ctx match {
            case Some(c) => c.updateStepsInTxn(workflow._id, newSteps).unit
            case None    => ws.workflowManager.updateSteps(workflow._id, newSteps).unit
          }
          appendTask.map { _ =>
            obj(
              "complete"     -> bool(false),
              "toolsCalled"  -> num(callList.size),
              "iteration"    -> num(input.iteration)
            ): Json
          }
        }
      }
    case _ =>
      Task.error(new IllegalStateException(
        "AgentDecisionStep tool dispatch requires the host Sigil to mix in WorkflowSigil."
      ))
  }

  private def decideNext(host: sigil.Sigil, workflow: Workflow, response: String, usage: Option[TokenUsage], ctx: Option[JobContext]): Task[Json] =
    SigilAgentDecisionStep.parseMarker(response) match {
      case SigilAgentDecisionStep.MarkerCompletion(summary) =>
        Task.pure(SigilAgentDecisionStep.withUsage(obj(
          "complete"  -> bool(true),
          "summary"   -> str(summary),
          "iteration" -> num(input.iteration),
          "exhausted" -> bool(false)
        ), usage))

      case _ if input.iteration + 1 >= input.maxIterations =>
        // Cap reached — settle whatever response we got as the summary.
        Task.pure(SigilAgentDecisionStep.withUsage(obj(
          "complete"  -> bool(true),
          "summary"   -> str(response),
          "iteration" -> num(input.iteration),
          "exhausted" -> bool(true)
        ), usage))

      case SigilAgentDecisionStep.MarkerAskParent(question) =>
        suspendForAnswer(host, workflow, response, question, ctx).map { questionId =>
          SigilAgentDecisionStep.withUsage(obj(
            "complete"   -> bool(false),
            "asked"      -> bool(true),
            "questionId" -> str(questionId),
            "question"   -> str(question),
            "iteration"  -> num(input.iteration)
          ), usage)
        }

      case SigilAgentDecisionStep.MarkerReport(report) =>
        publishReport(host, workflow, report).flatMap { _ =>
          appendNextIteration(host, workflow, response, ctx).map { _ =>
            SigilAgentDecisionStep.withUsage(obj(
              "complete"  -> bool(false),
              "reported"  -> bool(true),
              "report"    -> str(report),
              "iteration" -> num(input.iteration)
            ), usage)
          }
        }

      case SigilAgentDecisionStep.MarkerStatus(status) =>
        appendNextIteration(host, workflow, response, ctx).map { _ =>
          SigilAgentDecisionStep.withUsage(obj(
            "complete"      -> bool(false),
            "currentStatus" -> str(status),
            "iteration"     -> num(input.iteration)
          ), usage)
        }

      case SigilAgentDecisionStep.MarkerNone =>
        appendNextIteration(host, workflow, response, ctx).map { _ =>
          SigilAgentDecisionStep.withUsage(obj(
            "complete"  -> bool(false),
            "partial"   -> str(response),
            "iteration" -> num(input.iteration)
          ), usage)
        }
    }

  /** Publish a worker `Report:` line into the parent conversation
    * with default (`MessageVisibility.All`) visibility, so the user
    * sees mid-task progress updates inline in their conversation.
    * Same parent-resolution path as `publishToParent` (used by
    * `AskParent`) but with user-visible visibility instead of
    * Agents-only. */
  private def publishReport(host: sigil.Sigil, workflow: Workflow, report: String): Task[Unit] = {
    val workerConvIdOpt = workflow.conversationId.map(s => Id[Conversation](s))
    workerConvIdOpt match {
      case None => Task.unit
      case Some(workerConvId) =>
        host.withDB(_.conversations.transaction(_.get(workerConvId))).flatMap {
          case Some(workerConv) => workerConv.parentConversationId match {
            case Some(parentConvId) =>
              host.withDB(_.conversations.transaction(_.get(parentConvId))).flatMap {
                case Some(parentConv) =>
                  parentConv.participants.headOption match {
                    case Some(senderParticipant) =>
                      val msg = Message(
                        participantId  = senderParticipant.id,
                        conversationId = parentConvId,
                        topicId        = parentConv.currentTopicId,
                        content        = Vector(ResponseContent.Text(report)),
                        state          = EventState.Complete,
                        role           = MessageRole.Standard,
                        visibility     = MessageVisibility.All
                      )
                      host.publish(msg).unit
                    case None => Task.unit
                  }
                case None => Task.unit
              }
            case None => Task.unit
          }
          case None => Task.unit
        }
    }
  }

  /** Suspend the run: post the question into the parent conversation
    * as a hidden-from-user Message, then append a TriggerStep on an
    * AnswerTrigger plus a follow-up AgentDecisionStep that will see
    * the answer in priorReasoning when the trigger fires. */
  private def suspendForAnswer(host: sigil.Sigil, workflow: Workflow, response: String, question: String, ctx: Option[JobContext]): Task[String] = host match {
    case ws: WorkflowSigil =>
      val workerConvIdOpt = workflow.conversationId.map(s => Id[Conversation](s))
      val questionId = s"q${input.iteration + 1}-${rapid.Unique()}"
      for {
        _ <- workerConvIdOpt match {
          case Some(workerConvId) => publishToParent(host, workerConvId, questionId, question)
          case None               => Task.unit  // worker has no conv — orphan ask, log only
        }
        triggerStep = TriggerStepInput(
          id      = s"answer-trigger-${input.iteration + 1}",
          name    = Some(s"Awaiting parent answer (q${input.iteration + 1})"),
          trigger = AnswerTrigger(taskId = workflow._id.value, questionId = questionId)
        )
        nextStep = input.copy(
          id              = s"decision-${input.iteration + 1}",
          iteration       = input.iteration + 1,
          priorReasoning  = input.priorReasoning :+ s"$response\n\n[Asked parent: $question — awaiting answer]"
        )
        _ <- {
          import sigil.workflow.SigilWorkflowModel.stepRW
          val compiled = WorkflowStepInputCompiler.compile(List(triggerStep, nextStep))
          val newSteps = workflow.steps ++ compiled.steps
          ctx match {
            case Some(c) => c.updateStepsInTxn(workflow._id, newSteps).unit
            case None    => ws.workflowManager.updateSteps(workflow._id, newSteps).unit
          }
        }
      } yield questionId
    case _ =>
      Task.error(new IllegalStateException(
        "AgentDecisionStep requires the host Sigil to mix in WorkflowSigil. " +
        "ask_parent suspend/resume cannot run without the workflow runtime active."
      ))
  }

  /** Look up the worker conversation, find its parent (the user-
    * facing conversation that delegated the work), and publish a
    * Message there carrying the question with `MessageVisibility.Agents`
    * so the user doesn't see it but the parent agent's TriggerFilter
    * does. Worker convs without a parentConversationId, or parent
    * convs without participants, log a warning and skip — the
    * question is dropped but the worker still suspends on its
    * trigger; an external `answer_worker` call resumes it. */
  private def publishToParent(host: sigil.Sigil, workerConvId: Id[Conversation], questionId: String, question: String): Task[Unit] =
    host.withDB(_.conversations.transaction(_.get(workerConvId))).flatMap {
      case Some(workerConv) => workerConv.parentConversationId match {
        case Some(parentConvId) =>
          host.withDB(_.conversations.transaction(_.get(parentConvId))).flatMap {
            case Some(parentConv) =>
              parentConv.participants.headOption match {
                case Some(senderParticipant) =>
                  val msg = Message(
                    participantId  = senderParticipant.id,
                    conversationId = parentConvId,
                    topicId        = parentConv.currentTopicId,
                    content        = Vector(ResponseContent.Text(s"[Worker question $questionId]: $question")),
                    state          = EventState.Complete,
                    role           = MessageRole.Standard,
                    visibility     = MessageVisibility.Agents
                  )
                  host.publish(msg).unit
                case None =>
                  Task { scribe.warn(s"AgentDecisionStep ask_parent: parent conv $parentConvId has no participants; question dropped"); () }
              }
            case None =>
              Task { scribe.warn(s"AgentDecisionStep ask_parent: parent conv $parentConvId not found for worker $workerConvId"); () }
          }
        case None =>
          Task { scribe.warn(s"AgentDecisionStep ask_parent: worker conv $workerConvId has no parentConversationId; question dropped"); () }
      }
      case None =>
        Task { scribe.warn(s"AgentDecisionStep ask_parent: worker conv $workerConvId not found"); () }
    }

  private def appendNextIteration(host: sigil.Sigil, workflow: Workflow, lastResponse: String, ctx: Option[JobContext]): Task[Unit] = host match {
    case ws: WorkflowSigil =>
      val priorReasoning = input.priorReasoning :+ lastResponse
      val nextStep = input.copy(
        id              = s"decision-${input.iteration + 1}",
        iteration       = input.iteration + 1,
        priorReasoning  = priorReasoning
      )
      import sigil.workflow.SigilWorkflowModel.stepRW
      val compiled = WorkflowStepInputCompiler.compile(List(nextStep))
      val newSteps = workflow.steps ++ compiled.steps
      ctx match {
        case Some(c) => c.updateStepsInTxn(workflow._id, newSteps).unit
        case None    => ws.workflowManager.updateSteps(workflow._id, newSteps).unit
      }
    case _ =>
      Task.error(new IllegalStateException(
        "AgentDecisionStep requires the host Sigil to mix in WorkflowSigil. " +
        "Multi-iteration workers can't run without the workflow runtime active."
      ))
  }
}

object SigilAgentDecisionStep {

  /** Append a `usage: {prompt, completion, total}` block to a step
    * settle payload when token usage is known. Apps doing per-step
    * cost attribution read this directly off the step result; UI
    * panels can show "this iteration cost X tokens." When the
    * provider didn't surface usage events on this round-trip, the
    * payload stays unchanged. */
  def withUsage(payload: fabric.Json, usage: Option[TokenUsage]): Json = usage match {
    case None => payload
    case Some(u) =>
      payload.merge(obj("usage" -> obj(
        "prompt"     -> num(u.promptTokens),
        "completion" -> num(u.completionTokens),
        "total"      -> num(u.totalTokens)
      )))
  }

  /** Parsed marker — drives the next-step decision in `decideNext`.
    * Priority when multiple appear in one response (highest wins):
    * Completion (terminate) > AskParent (suspend) > Report
    * (user-visible message + continue) > Status (panel-status update
    * + continue) > None (regular ReAct continuation). */
  sealed trait Marker
  case class MarkerCompletion(summary: String) extends Marker
  case class MarkerAskParent(question: String) extends Marker
  case class MarkerReport(report: String) extends Marker
  case class MarkerStatus(status: String) extends Marker
  case object MarkerNone extends Marker

  /** System prompt: role description plus the worker-loop coda
    * (how to terminate, how to ask the parent for clarification).
    * Kept terse — the role's own description does most of the
    * identity work. */
  def buildSystemPrompt(input: AgentDecisionStepInput): String = {
    val toolBlock =
      if (input.toolNames.isEmpty) ""
      else s"""
              |Tools available to you on this run: ${input.toolNames.mkString(", ")}.
              |Call them via the standard tool-call interface; the framework dispatches each
              |call, captures its result, and folds the result text into your context on the
              |next iteration so you can read what came back and decide what to do next.
              |""".stripMargin
    s"""${input.role.description}
       |
       |You are running as a worker — this is iteration ${input.iteration + 1} of up to ${input.maxIterations}.
       |$toolBlock
       |When you have completed the work the user briefed you on, you have two equivalent ways
       |to terminate:
       |  - Call the `complete_task` tool with `summary` — preferred for tool-calling-capable
       |    models (typed args, schema-validated).
       |  - Or respond with a single line: `Complete: <one-paragraph summary>` — fallback
       |    for plain-text emission.
       |
       |If you need clarification or a decision from the parent agent before you can proceed,
       |emit a single line:
       |  AskParent: <your question>
       |The framework suspends your run, routes the question to the parent agent (which may
       |answer from context or escalate to the user), and resumes you with the answer in your
       |context on the next iteration.
       |
       |If you have a meaningful progress update for the user (a milestone reached, an
       |intermediate finding, etc.), emit:
       |  Report: <user-visible message>
       |The framework posts this into the user's conversation and continues your run.
       |
       |If you want a short status line on your task card without surfacing it to the user
       |(panel display only), emit:
       |  Status: <short status text>
       |
       |If you need to keep working (research more, plan further, etc.), just write your reasoning
       |and the framework will give you another turn with your reasoning carried forward. Don't
       |emit any of these markers until you genuinely need them.""".stripMargin
  }

  /** User prompt: the brief plus any accumulated reasoning from
    * prior iterations, plus any answers that have arrived from the
    * parent agent since the worker's last AskParent suspension.
    * Parent answers go into a clearly-marked block so the LLM
    * can pick out the new context vs the prior reasoning chain. */
  def buildUserPrompt(input: AgentDecisionStepInput, parentAnswers: List[ParentAnswer] = Nil): String = {
    val priorBlock =
      if (input.priorReasoning.isEmpty) ""
      else input.priorReasoning.zipWithIndex.map { case (text, i) =>
        s"--- Iteration ${i + 1} ---\n$text"
      }.mkString("\n\n") + "\n\n"
    val answersBlock =
      if (parentAnswers.isEmpty) ""
      else parentAnswers.map { a =>
        s"--- Parent answer to question ${a.questionId} ---\n${a.answer}"
      }.mkString("\n\n") + "\n\n"
    if (priorBlock.isEmpty && answersBlock.isEmpty) input.brief
    else
      s"""${input.brief}
         |
         |$priorBlock$answersBlock--- Continue ---""".stripMargin
  }

  /** Walk the workflow's persisted step results and pull out every
    * answer the parent has provided so far. Each AnswerTrigger
    * settles with `{taskId, questionId, answer}` in its `output`
    * payload; we project to a typed [[ParentAnswer]] for
    * `buildUserPrompt`. Returns oldest-to-newest so the LLM sees
    * answers in the order they arrived. */
  def extractParentAnswers(workflow: strider.Workflow): List[ParentAnswer] = {
    workflow.stepResults.reverse.flatMap { sr =>
      sr.output.flatMap { json =>
        for {
          questionId <- json.get("questionId").map(_.asString)
          answer     <- json.get("answer").map(_.asString)
        } yield ParentAnswer(questionId, answer)
      }
    }
  }

  /** Resolve a list of tool names against the host's tool finder.
    * Missing names log a warning and are skipped — the worker's
    * roster degrades gracefully rather than failing the whole run
    * on a typo'd or app-decommissioned tool name. */
  def resolveTools(host: sigil.Sigil, names: List[String]): rapid.Task[List[sigil.tool.Tool]] =
    rapid.Task.sequence(names.map { n =>
      host.findTools.byName(sigil.tool.ToolName(n)).map {
        case Some(t) => Some(t)
        case None    => scribe.warn(s"AgentDecisionStep: tool '$n' not found in host registry; dropping from worker roster"); None
      }
    }).map(_.flatten)

  /** Scan the response for the first matching marker, by priority
    * order: Completion (terminate) > AskParent (suspend) > Report
    * (user-visible message + continue) > Status (panel update +
    * continue) > None. All markers are line-anchored (case-
    * insensitive); mid-paragraph mentions don't false-positive. */
  def parseMarker(response: String): Marker = {
    parseCompletion(response) match {
      case Some(summary) => MarkerCompletion(summary)
      case None => parseAskParent(response) match {
        case Some(question) => MarkerAskParent(question)
        case None => parseReport(response) match {
          case Some(report) => MarkerReport(report)
          case None => parseStatus(response) match {
            case Some(status) => MarkerStatus(status)
            case None         => MarkerNone
          }
        }
      }
    }
  }

  /** Pull the post-`Complete:` summary out of the response. */
  def parseCompletion(response: String): Option[String] = {
    val pattern = "(?im)^\\s*Complete:\\s*(.+)$".r
    pattern.findFirstMatchIn(response).map { m =>
      val afterMarker = response.substring(m.start)
      "(?im)^\\s*Complete:\\s*".r.replaceFirstIn(afterMarker, "").trim
    }
  }

  /** Pull the post-`AskParent:` question out of the response. Same
    * anchored / case-insensitive shape as parseCompletion. */
  def parseAskParent(response: String): Option[String] = {
    val pattern = "(?im)^\\s*AskParent:\\s*(.+)$".r
    pattern.findFirstMatchIn(response).map { m =>
      val afterMarker = response.substring(m.start)
      "(?im)^\\s*AskParent:\\s*".r.replaceFirstIn(afterMarker, "").trim
    }
  }

  /** Pull the post-`Report:` user-visible message out of the
    * response. Workers use this to surface progress updates to the
    * user mid-task without terminating. */
  def parseReport(response: String): Option[String] = {
    val pattern = "(?im)^\\s*Report:\\s*(.+)$".r
    pattern.findFirstMatchIn(response).map { m =>
      val afterMarker = response.substring(m.start)
      "(?im)^\\s*Report:\\s*".r.replaceFirstIn(afterMarker, "").trim
    }
  }

  /** Pull the post-`Status:` short status line out of the response.
    * Workers use this for panel-display progress text ("Compiling
    * step 3/7") that doesn't need to surface to the user but should
    * appear on the worker's task card. */
  def parseStatus(response: String): Option[String] = {
    val pattern = "(?im)^\\s*Status:\\s*(.+)$".r
    pattern.findFirstMatchIn(response).map { m =>
      val afterMarker = response.substring(m.start)
      "(?im)^\\s*Status:\\s*".r.replaceFirstIn(afterMarker, "").trim
    }
  }
}
