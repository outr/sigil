package sigil.mcp

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{GlobalSpace, SpaceId}
import sigil.db.Model

/**
 * Persisted configuration for an MCP server connection. Stored in
 * [[McpCollections.mcpServers]]; loaded lazily on first tool resolution
 * by [[McpManager]].
 *
 * @param name              human-readable identifier; surfaced in
 *                          management tools and used for log/error
 *                          attribution. The record's id is derived
 *                          from this so each name is unique.
 * @param transport         wire transport choice ([[McpTransport]]).
 * @param prefix            string prepended to every tool name advertised
 *                          by this server. Disambiguates collisions when
 *                          multiple servers expose the same tool name
 *                          (e.g. two `read_file` tools). Empty string for
 *                          no prefix.
 * @param space             single [[SpaceId]] scoping for every tool
 *                          this server advertises. Defaults to
 *                          [[sigil.GlobalSpace]] for "visible to
 *                          everyone"; pass an app-specific space to
 *                          confine tools to a tenant / user / project.
 *                          One server = one space; copy the config to
 *                          surface the same server under another space.
 * @param samplingModelId   when the MCP server requests an LLM sampling
 *                          completion via `sampling/createMessage`, the
 *                          framework's [[SamplingHandler]] uses this
 *                          model id to call back through the host's
 *                          provider. `None` means sampling requests
 *                          fail with an explanatory error.
 * @param roots             filesystem workspace roots advertised to the
 *                          server during the initialize handshake.
 *                          Filesystem-aware servers use these to scope
 *                          their operations.
 * @param refreshIntervalMs how often to refresh the cached tool list
 *                          while the connection is active. The connection
 *                          is also refreshed on every reconnect after
 *                          idle-timeout.
 * @param idleTimeoutMs     inactivity period before the connection is
 *                          closed and the subprocess torn down. The
 *                          next call lazily reconnects and re-discovers
 *                          tools.
 * @param metadata          app-owned key/value data the framework never
 *                          interprets. This is where an app marks a
 *                          config for its own [[McpClient]] (see
 *                          [[McpSigil.mcpClientFor]]) and stashes
 *                          whatever routing data that client needs —
 *                          which end user's socket to tunnel through,
 *                          for instance. Set server-side; the agent-
 *                          facing `add_mcp_server` tool does not expose
 *                          it.
 */
case class McpServerConfig(name: String,
                           transport: McpTransport,
                           prefix: Option[String] = None,
                           space: SpaceId = GlobalSpace,
                           samplingModelId: Option[Id[Model]] = None,
                           roots: List[String] = Nil,
                           refreshIntervalMs: Long = 30L * 60L * 1000L,
                           idleTimeoutMs: Long = 5L * 60L * 1000L,
                           /** Per-call execution timeout. A tool call that
                             * doesn't return within this bound fails with an
                             * [[McpCallTimeoutException]] instead of hanging the
                             * agent's turn (e.g. a build server wedged on a cold
                             * import). Generous by default so a legitimately slow
                             * import still completes; apps tune per server. `0`
                             * disables the timeout. */
                           callTimeoutMs: Long = 5L * 60L * 1000L,
                           metadata: Map[String, String] = Map.empty,
                           created: Timestamp = Timestamp(),
                           modified: Timestamp = Timestamp(),
                           _id: Id[McpServerConfig] = McpServerConfig.id())
  extends RecordDocument[McpServerConfig]

object McpServerConfig extends RecordDocumentModel[McpServerConfig] with JsonConversion[McpServerConfig] {
  implicit override def rw: RW[McpServerConfig] = RW.gen

  override def id(value: String = rapid.Unique()): Id[McpServerConfig] = Id(value)

  /** Stable id derived from server name — one record per name. */
  def idFor(name: String): Id[McpServerConfig] = Id(name)
}
