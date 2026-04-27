package sigil.mcp

import fabric.{Json, JsonWrapper, Obj}
import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Generic [[ToolInput]] for tools whose schema is dynamic (not
 * derived from a Scala case class). [[McpTool]] uses this for every
 * remote tool: the MCP server advertises a JSON Schema; we surface
 * that schema directly via `inputDefinition` and let the LLM produce
 * raw [[Json]] arguments.
 *
 * Extending [[JsonWrapper]] is the fabric-level trick that makes the
 * persisted form *identical to* `json` — no `{"json": {...}}`
 * envelope. The tool-call wire body is exactly what the LLM produced;
 * Sigil round-trips it without alteration.
 */
case class JsonInput(json: Json = Obj.empty) extends ToolInput with JsonWrapper derives RW
