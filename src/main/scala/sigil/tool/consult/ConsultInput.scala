package sigil.tool.consult

import fabric.rw.*
import lightdb.id.Id
import sigil.db.Model
import sigil.tool.ToolInput

/**
 * Input for the [[ConsultTool]] — an LLM-callable wrapper for one-shot
 * cross-model consultations. The agent uses this to ask another model
 * (or itself, with no conversation history) a focused question and get
 * back a free-form text answer.
 *
 * @param modelId      the model to consult (any registered model id; can be a
 *                     different family than the agent's own).
 * @param systemPrompt the system message for the consult call. Should set
 *                     the consulted model's role / context for this question.
 * @param userPrompt   the actual question or task. The consulted model
 *                     answers in free-form text.
 *
 * The result is emitted as a Message into the conversation, attributed to
 * the calling agent, so the agent can read it on its next turn.
 *
 * Structured / typed consults are available to framework code via
 * [[ConsultTool.invoke]] — that path is not exposed to LLMs.
 */
case class ConsultInput(modelId: Id[Model],
                        systemPrompt: String,
                        userPrompt: String) extends ToolInput derives RW
