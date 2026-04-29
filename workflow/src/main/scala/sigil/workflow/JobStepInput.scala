package sigil.workflow

import fabric.rw.*

/**
 * Step that runs an LLM prompt or a tool call. The most common step
 * shape — the workhorse of any workflow.
 *
 * Mode of operation:
 *   - `prompt` set, `tool` blank → run an LLM prompt, store the
 *     model's text reply at the step's output variable
 *   - `tool` set, `prompt` blank → invoke the named tool with
 *     `arguments`, store the tool's result
 *   - both set → an LLM call where the agent is constrained to the
 *     named tool and `arguments` are pre-filled; useful for
 *     "summarize this then save_memory it" patterns
 *
 * `output` is the variable name the result is written to —
 * subsequent steps reference it via `{{output}}` substitution in
 * their own prompts / arguments.
 *
 * `modelId` (optional) overrides the conversation's default model
 * for this step. `tools` (optional) restricts the LLM's tool
 * roster to a subset; empty means whatever the agent normally has.
 */
case class JobStepInput(id: String,
                        name: String = "",
                        prompt: String = "",
                        tool: String = "",
                        arguments: String = "",
                        output: String = "",
                        modelId: String = "",
                        tools: List[String] = Nil,
                        continueOnError: Boolean = false,
                        retryCount: Int = 0,
                        retryDelayMs: Long = 5000L) extends WorkflowStepInput derives RW
