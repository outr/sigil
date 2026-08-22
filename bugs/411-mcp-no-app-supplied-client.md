# ❌ #411 — No way for an app to supply its own McpClient, so a user's local MCP server is unreachable from a hosted deployment

**Where:**
- `mcp/src/main/scala/sigil/mcp/McpManager.scala:238-239` — `clientFor` matches the sealed transport and constructs the impl directly
- `mcp/src/main/scala/sigil/mcp/McpTransport.scala` — sealed `enum` with exactly `Stdio` and `HttpSse`
- `mcp/src/main/scala/sigil/mcp/McpClient.scala:23` — `McpClient` *is* an open trait, so the capability is nearly there

**What's wrong:** an app cannot register an MCP server it reaches over its own
transport. `clientFor` decides the implementation:

```scala
case _: McpTransport.Stdio   => new StdioMcpClient(cfg, samplingHandlerFor(cfg), notificationListener)
case _: McpTransport.HttpSse => new HttpSseMcpClient(cfg, samplingHandlerFor(cfg), notificationListener)
```

Both shipped transports assume the *server* can initiate the connection — spawn a
local subprocess, or make an outbound HTTP request to a reachable URL.

That excludes the hosted shape where the MCP server runs on an end user's own
machine. In Voidcraft, `add_mcp_server` is offered to users, but a user pointing
it at something on their laptop (`http://localhost:9000`, or a stdio binary
installed there) cannot work: the agent runs centrally, the user's machine is
behind NAT, and the only route to it is a WebSocket that machine opened
outbound. The server would need an `McpClient` that tunnels JSON-RPC through
that socket, and there is no seam to supply one.

Note this is not an MCP-protocol limitation. MCP is transport-agnostic JSON-RPC;
the constraint is purely that `clientFor` picks from a closed set.

**Scope, honestly:** this is an enhancement, not a blocker. Voidcraft's own
Metals integration does *not* need it — there the client hosts Metals, reaches
its MCP endpoint over its own loopback, and answers proxied tool calls, so the
server never speaks MCP at all. (An earlier revision of this file claimed
Metals was blocked on it; that was wrong.) What remains blocked is only the
user-registered-local-MCP-server case.

**Suggested fix:** an overridable factory on `McpManager` (or on the host `Sigil`)
consulted before the built-in match:

```scala
/** Apps override to supply a client for transports the framework doesn't
  * construct itself — e.g. JSON-RPC tunnelled over an app's own socket. */
def customClientFor(config: McpServerConfig): Option[McpClient] = None
```

with `clientFor` becoming `customClientFor(cfg).getOrElse(builtIn(cfg))`. That
keeps `McpTransport` closed and needs no new wire shape — an app selects its
transport by convention (a URL scheme, or a marker in `McpServerConfig`) and
returns its own `McpClient`.

Adding a third `McpTransport` case would also work, but bakes one app's
transport into the framework's wire enum; the factory hook composes better and
`McpClient` being a public trait suggests that was the intent.
