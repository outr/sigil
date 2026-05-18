package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Typed shape for the agent's progress-checkpoint reflection.
 * The framework dispatches a one-shot LLM call asking the agent
 * to compare the current state of the task against the prior
 * checkpoint's status and report whether meaningful progress has
 * happened.
 */
case class ProgressReflectionInput(currentStatus: String,
                                   meaningfulProgress: Boolean,
                                   remainingSteps: String,
                                   stuckOn: Option[String],
                                   shouldAskUser: Boolean)
  extends ToolInput derives RW
