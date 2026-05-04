package sigil.workflow

import fabric.{Json, bool, num, obj, str}
import fabric.rw.*
import lightdb.id.Id
import lightdb.progress.ProgressManager
import rapid.Task
import sigil.db.Model
import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent}
import strider.Workflow
import strider.step.{Job, Step}

/**
 * Strider executor for [[AgentDecisionStepInput]]. Runs one LLM
 * round-trip via [[sigil.Sigil.providerFor]] using the resolved
 * `modelId`, with `role.description` (plus a worker-loop coda)
 * as system prompt and the brief + accumulated reasoning as the
 * user prompt.
 *
 * **Loop shape (v2 — multi-iteration ReAct).** The LLM response is
 * scanned for a `Complete:` marker — when present, the run
 * terminates and the post-marker text is captured as the worker's
 * summary. When absent, the executor appends another
 * [[AgentDecisionStepInput]] to the workflow run via
 * [[strider.AbstractWorkflowManager.updateSteps]] with the iteration
 * counter bumped and the prior reasoning folded into the next
 * iteration's brief — Strider's queue picks it up after this step
 * settles.
 *
 * `iteration >= maxIterations` is a hard runaway cap; the worker
 * settles with `exhausted = true` on the boundary.
 *
 * The single-tool worker surface is intentionally minimal in v2 —
 * the LLM emits free text plus a `Complete:` terminator. Phase 2c
 * follow-on adds typed tool calls (`set_status`, `ask_parent`,
 * `report_to_user`) that translate to additional appended workflow
 * step inputs (sugar steps + AnswerTrigger) without breaking the
 * loop's contract.
 */
final case class SigilAgentDecisionStep(input: AgentDecisionStepInput,
                                        id: Id[Step] = Step.id()) extends Job[Json] derives RW {
  override def name: String = input.name.getOrElse(input.id)

  override def execute(workflow: Workflow, pm: ProgressManager): Task[Json] = {
    val host = WorkflowHost.get
    val modelId = Id[Model](input.modelId)

    host.providerFor(modelId, Nil).flatMap { provider =>
      val systemPrompt = SigilAgentDecisionStep.buildSystemPrompt(input)
      val userPrompt   = SigilAgentDecisionStep.buildUserPrompt(input)
      val request = OneShotRequest(
        modelId            = modelId,
        systemPrompt       = systemPrompt,
        userPrompt         = userPrompt,
        generationSettings = GenerationSettings()
      )
      val acc = new java.lang.StringBuilder
      provider(request).evalMap {
        case ProviderEvent.TextDelta(t)            => Task { acc.append(t); () }
        case ProviderEvent.ContentBlockDelta(_, t) => Task { acc.append(t); () }
        case _                                     => Task.unit
      }.drain.flatMap { _ =>
        val response = acc.toString
        SigilAgentDecisionStep.parseCompletion(response) match {
          case Some(summary) =>
            // Worker terminated explicitly.
            Task.pure(obj(
              "complete"   -> bool(true),
              "summary"    -> str(summary),
              "iteration"  -> num(input.iteration),
              "exhausted"  -> bool(false)
            ): Json)

          case None if input.iteration + 1 >= input.maxIterations =>
            // Hit the runaway cap on the NEXT iteration; settle now.
            Task.pure(obj(
              "complete"   -> bool(true),
              "summary"    -> str(response),
              "iteration"  -> num(input.iteration),
              "exhausted"  -> bool(true)
            ): Json)

          case None =>
            // Append another decision step with this iteration's
            // response folded into context.
            appendNextIteration(host, workflow, response).map { _ =>
              obj(
                "complete"  -> bool(false),
                "partial"   -> str(response),
                "iteration" -> num(input.iteration)
              ): Json
            }
        }
      }
    }
  }

  private def appendNextIteration(host: sigil.Sigil, workflow: Workflow, lastResponse: String): Task[Unit] = host match {
    case ws: WorkflowSigil =>
      val priorReasoning = input.priorReasoning :+ lastResponse
      val nextStep = input.copy(
        id              = s"decision-${input.iteration + 1}",
        iteration       = input.iteration + 1,
        priorReasoning  = priorReasoning
      )
      import sigil.workflow.SigilWorkflowModel.stepRW
      val compiled = WorkflowStepInputCompiler.compile(List(nextStep))
      ws.workflowManager.updateSteps(workflow._id, workflow.steps ++ compiled.steps).unit
    case _ =>
      Task.error(new IllegalStateException(
        "AgentDecisionStep requires the host Sigil to mix in WorkflowSigil. " +
        "Multi-iteration workers can't run without the workflow runtime active."
      ))
  }
}

object SigilAgentDecisionStep {

  /** System prompt: role description plus the worker-loop coda
    * (how to terminate). Kept terse — the role's own description
    * does most of the identity work. */
  def buildSystemPrompt(input: AgentDecisionStepInput): String = {
    s"""${input.role.description}
       |
       |You are running as a worker — this is iteration ${input.iteration + 1} of up to ${input.maxIterations}.
       |
       |When you have completed the work the user briefed you on, respond with a single line:
       |  Complete: <one-paragraph summary of what you did and what the result is>
       |
       |If you need to keep working (research more, plan further, etc.), just write your reasoning
       |and the framework will give you another turn with your reasoning carried forward. Don't
       |emit `Complete:` until you genuinely have a result to hand back.""".stripMargin
  }

  /** User prompt: the brief plus any accumulated reasoning from
    * prior iterations, so each turn sees the full chain. Kept
    * compact — the brief is the agent's working memory between
    * turns. */
  def buildUserPrompt(input: AgentDecisionStepInput): String = {
    if (input.priorReasoning.isEmpty) input.brief
    else {
      val priors = input.priorReasoning.zipWithIndex.map { case (text, i) =>
        s"--- Iteration ${i + 1} ---\n$text"
      }.mkString("\n\n")
      s"""${input.brief}
         |
         |$priors
         |
         |--- Continue ---""".stripMargin
    }
  }

  /** Pull the post-`Complete:` summary out of the response, if
    * present. Anchored at line start so prose mentioning "Complete:"
    * mid-paragraph doesn't false-positive; case-insensitive on the
    * marker since LLMs occasionally drift to "complete:". */
  def parseCompletion(response: String): Option[String] = {
    val pattern = "(?im)^\\s*Complete:\\s*(.+)$".r
    pattern.findFirstMatchIn(response).map { m =>
      // Capture the matched line + everything after it (multi-line summary OK).
      val start = m.start
      val afterMarker = response.substring(start)
      "(?im)^\\s*Complete:\\s*".r.replaceFirstIn(afterMarker, "").trim
    }
  }
}
