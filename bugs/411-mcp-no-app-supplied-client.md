# ❌ #411 — No way for an app to supply its own McpClient, so MCP can't reach a server behind a client socket

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
local subprocess, or make an outbound HTTP request to a reachable URL. That
excludes the common hosted shape where the MCP server runs on an end user's
machine: it is behind NAT or a firewall, there is no inbound route to it, and the
only reason the app can talk to that machine at all is that the machine dialled
out first and is holding a socket open.

Concretely, in Voidcraft: agents run on a central server while filesystem and
shell tools execute on the user's paired client over a WebSocket the client
opened. Metals runs on the user's machine and exposes an MCP endpoint at a
loopback address there. `MetalsManager` already registers Metals by URL
(`metals/.../MetalsManager.scala:447`), and `McpTransport.HttpSse` is exactly the
right *shape* — but the URL names a host the server cannot route to. The only
usable path is to tunnel JSON-RPC frames through the socket that already exists,
and there is no seam to plug that in.

Note this is not an MCP-protocol limitation. MCP is transport-agnostic
JSON-RPC; the constraint is purely that `clientFor` picks the implementation
from a closed set.

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
