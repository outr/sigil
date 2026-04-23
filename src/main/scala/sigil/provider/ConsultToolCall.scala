package sigil.provider

import sigil.tool.{ToolInput, ToolName}

/**
 * The structured output of a one-shot [[Provider.consult]] call when the
 * model invoked the supplied tool. Carries the tool's name (for sanity
 * checks against what was requested) and the parsed input.
 */
case class ConsultToolCall(toolName: ToolName, input: ToolInput)
