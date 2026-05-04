package sigil.workflow

import fabric.rw.*

/**
 * Typed projection of an [[sigil.workflow.trigger.AnswerTrigger]]
 * settle result — what
 * [[SigilAgentDecisionStep.buildUserPrompt]] folds into the
 * worker's next user message after a suspend/resume cycle. Each
 * `ask_parent` round-trip produces one of these once the parent's
 * `answer_worker` call lands.
 */
case class ParentAnswer(questionId: String, answer: String) derives RW
