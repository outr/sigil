package sigil.tool

import fabric.{Json, JsonWrapper, Obj}
import fabric.rw.*

/**
 * Generic [[ToolInput]] for tools whose schema is dynamic (not derived
 * from a Scala case class). Used by [[sigil.mcp.McpTool]] for every
 * remote tool (the MCP server advertises a JSON Schema; we surface
 * that schema via `inputDefinition` and let the LLM produce raw
 * [[Json]] arguments) and by `sigil.script.ScriptTool` for
 * runtime-authored tools whose parameter shape lives on the record
 * rather than a static case class.
 *
 * Extending [[JsonWrapper]] is the fabric-level trick that makes the
 * persisted form *identical to* `json` — no `{"json": {...}}`
 * envelope. The tool-call wire body is exactly what the LLM produced;
 * Sigil round-trips it without alteration.
 */
case class JsonInput(json: Json = Obj.empty) extends ToolInput with JsonWrapper derives RW
