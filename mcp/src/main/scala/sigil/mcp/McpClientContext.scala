package sigil.mcp

import fabric.Json
import rapid.Task

/**
 * Everything [[McpManager]] hands a client at construction time, passed
 * to [[McpSigil.mcpClientFor]] so an app-supplied [[McpClient]] starts
 * with the same collaborators the built-in transports get.
 *
 * A client built from the config alone silently loses two capabilities:
 * it cannot answer the server's `sampling/createMessage` requests, and
 * its caches go stale because the manager never hears
 * `notifications/{tools,resources,prompts}/list_changed`. Both arrive
 * here so a custom client is a peer of the shipped ones rather than a
 * degraded copy.
 *
 * @param config               the server this client connects to.
 * @param samplingHandler      resolved for this server by
 *                             [[McpSigil.samplingHandlerFor]]; invoke it
 *                             for an inbound `sampling/createMessage`.
 * @param notificationListener call with the method name (and raw params)
 *                             of every server notification received —
 *                             the manager invalidates the matching cache
 *                             slice.
 */
case class McpClientContext(config: McpServerConfig,
                            samplingHandler: SamplingHandler,
                            notificationListener: (String, Json) => Task[Unit])
