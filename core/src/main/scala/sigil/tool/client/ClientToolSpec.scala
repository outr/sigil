package sigil.tool.client

import fabric.*
import fabric.rw.*

/**
 * Wire-shape of one UI-registered interaction tool. The frontend
 * sends these (via [[sigil.signal.RegisterClientTools]]) when a
 * conversation loads, exposing its screens / panels / actions to the
 * agent without a backend change — the schema is data, exactly like
 * an MCP server's advertised tools.
 *
 *   - `name` — snake_case tool name; must not collide with any
 *     server-registered tool (the registration is rejected if it
 *     does).
 *   - `inputSchema` — a JSON-Schema object describing the tool's
 *     arguments (MCP-style). Converted via
 *     [[sigil.tool.JsonSchemaToDefinition]]; the agent's parsed call
 *     arrives as [[sigil.tool.JsonInput]].
 *   - `expectsResult` — `false` (default): the call settles
 *     immediately with an acknowledgment; the UI observes the
 *     [[sigil.event.ToolInvoke]] on its signal stream and acts
 *     (fire-and-forget — navigation, opening panels). `true`: the
 *     framework parks the call until the client answers with a
 *     [[sigil.signal.ClientToolResult]] (or the timeout elapses) —
 *     for tools that read UI state back to the agent.
 *   - `readOnly` / `destructive` — the standard tool annotations,
 *     declared by the client. Defaults describe a typical UI
 *     interaction: not destructive to external state, but not
 *     `readOnly` either (triggering navigation is an effect).
 *   - `consequence` — what a `destructive` call actually does, in the
 *     client's own words ("Deletes the selected board and its cards").
 *     Rendered to the agent ahead of the call; when omitted the
 *     framework substitutes a stub naming the tool.
 */
case class ClientToolSpec(name: String,
                          description: String,
                          keywords: Set[String] = Set.empty,
                          inputSchema: Json = obj(),
                          expectsResult: Boolean = false,
                          readOnly: Boolean = false,
                          destructive: Boolean = false,
                          consequence: Option[String] = None)
  derives RW
