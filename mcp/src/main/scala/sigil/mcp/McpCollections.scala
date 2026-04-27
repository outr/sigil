package sigil.mcp

import sigil.db.SigilDB

/**
 * lightdb collection mix-in that adds the `mcpServers` store to a
 * [[SigilDB]] subclass. Apps that pull in `sigil-mcp` declare their
 * concrete DB as
 * `class MyAppDB(...) extends SigilDB(...) with McpCollections`,
 * then refine `type DB = MyAppDB` on their Sigil instance via
 * [[McpSigil]].
 */
trait McpCollections { self: SigilDB =>
  val mcpServers: S[McpServerConfig, McpServerConfig.type] = store(McpServerConfig)()
}
