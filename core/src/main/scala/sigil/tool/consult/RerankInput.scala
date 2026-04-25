package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Output of the LLM reranker — an ordered list of candidate ids
 * from most-to-least relevant to the query.
 */
case class RerankInput(orderedIds: List[String]) extends ToolInput derives RW
