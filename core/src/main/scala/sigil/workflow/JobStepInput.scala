package sigil.workflow

import fabric.rw.*
import sigil.provider.Complexity

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
 * `complexity` (optional) biases an LLM `prompt` step's difficulty tier; the
 * model is resolved automatically from the conversation's active Mode + this
 * tier (#380) — a step never names a concrete model. Omit to auto-classify.
 * `tools` (optional) restricts the LLM's tool roster to a subset; empty means
 * whatever the agent normally has.
 */
case class JobStepInput(id: String,
                        name: Option[String] = None,
                        prompt: Option[String] = None,
                        tool: Option[String] = None,
                        arguments: Option[String] = None,
                        output: Option[String] = None,
                        complexity: Option[Complexity] = None,
                        tools: List[String] = Nil,
                        continueOnError: Boolean = false,
                        retryCount: Int = 0,
                        retryDelayMs: Long = 5000L) extends WorkflowStepInput derives RW
