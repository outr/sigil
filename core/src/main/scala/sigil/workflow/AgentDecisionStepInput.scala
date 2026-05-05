package sigil.workflow

import fabric.rw.*
import sigil.role.Role

/**
 * Step that runs one iteration of an agent loop on behalf of a worker
 * delegation. The step's body invokes a single LLM round-trip with
 * the worker's [[Role]] description as system prompt and `brief` as
 * the input directive; the model's text response is captured as the
 * step output.
 *
 * Foundation step type for the worker delegation pattern: a
 * `delegate_task(role, brief)` tool spawns a workflow run whose
 * initial step list is `[AgentDecisionStepInput(role, brief)]`. As
 * the framework evolves through phase 2 the executor will translate
 * the LLM's tool calls into appended workflow steps (tools like
 * `complete_task`, `ask_parent`, `set_status` materializing as
 * matching step inputs), so a single agent decision can grow the
 * workflow by multiple steps. v1 ships the single-shot shape; the
 * iterative ReAct expansion lands in subsequent commits.
 *
 * `modelId` is required — the spawning agent resolves it from the
 * role's [[sigil.provider.WorkType]] via `ProviderStrategy.routed`
 * before the step is appended, so the step itself just runs the
 * resolved model.
 *
 * `iteration` (default 0) tracks how many AgentDecisionSteps have
 * fired in the current run; future expansion uses this against
 * `maxIterations` as a runaway cap.
 */
case class AgentDecisionStepInput(id: String,
                                  name: Option[String] = None,
                                  role: Role,
                                  brief: String,
                                  modelId: String,
                                  iteration: Int = 0,
                                  maxIterations: Int = 50,
                                  priorReasoning: List[String] = Nil,
                                  toolNames: List[String] = Nil) extends WorkflowStepInput derives RW
