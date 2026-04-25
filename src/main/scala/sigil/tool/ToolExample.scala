package sigil.tool

import fabric.rw.*

/**
 * Worked example for a tool — surfaced in the tool's schema so the LLM can
 * see how the tool is meant to be used. `input` is a polymorphic
 * [[ToolInput]]; the example serializes through the registered ToolInput
 * RW.
 */
case class ToolExample(description: String, input: ToolInput) derives RW
