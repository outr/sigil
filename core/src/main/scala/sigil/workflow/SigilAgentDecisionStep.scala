package sigil.workflow

import fabric.{Json, str}
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
 * `modelId`, with `role.description` as system prompt and `brief`
 * as user prompt; returns the accumulated text response as the
 * step's output.
 *
 * v1 shape: single-shot. The iterative ReAct expansion (translating
 * the LLM's tool calls into appended workflow steps via
 * [[strider.AbstractWorkflowManager.updateSteps]]) lands in phase 2
 * follow-on commits — `complete_task`, `ask_parent`, `set_status`,
 * etc. become the step inputs the agent emits.
 */
final case class SigilAgentDecisionStep(input: AgentDecisionStepInput,
                                        id: Id[Step] = Step.id()) extends Job[Json] derives RW {
  override def name: String = input.name.getOrElse(input.id)

  override def execute(workflow: Workflow, pm: ProgressManager): Task[Json] = {
    val host = WorkflowHost.get
    val modelId = Id[Model](input.modelId)
    host.providerFor(modelId, Nil).flatMap { provider =>
      val request = OneShotRequest(
        modelId = modelId,
        systemPrompt = input.role.description,
        userPrompt = input.brief,
        generationSettings = GenerationSettings()
      )
      val acc = new java.lang.StringBuilder
      provider(request).evalMap {
        case ProviderEvent.TextDelta(t)            => Task { acc.append(t); () }
        case ProviderEvent.ContentBlockDelta(_, t) => Task { acc.append(t); () }
        case _                                     => Task.unit
      }.drain.map(_ => str(acc.toString): Json)
    }
  }
}
