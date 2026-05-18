package spec

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the test-only [[EchoBackTool]] used by worker-tool-dispatch
 * coverage. Carries a single `text` field; the tool emits a Message
 * with `Echo: <text>` so the spec can assert the text round-tripped
 * through the worker's tool-dispatch path.
 */
case class EchoBackInput(text: String) extends ToolInput derives RW
