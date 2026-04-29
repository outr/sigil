package sigil.tooling

import sigil.db.SigilDB

/**
 * lightdb collection mix-in that adds the `lspServers` and
 * `bspBuilds` stores to a [[SigilDB]] subclass. Apps that pull in
 * `sigil-tooling` declare their concrete DB as
 * `class MyAppDB(...) extends SigilDB(...) with ToolingCollections`,
 * then refine `type DB = MyAppDB` on their Sigil instance via
 * [[ToolingSigil]].
 */
trait ToolingCollections { self: SigilDB =>
  val lspServers: S[LspServerConfig, LspServerConfig.type] = store(LspServerConfig)()
  val bspBuilds:  S[BspBuildConfig, BspBuildConfig.type]   = store(BspBuildConfig)()
}
