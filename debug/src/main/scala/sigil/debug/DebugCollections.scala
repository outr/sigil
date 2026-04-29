package sigil.debug

import sigil.db.SigilDB

/**
 * lightdb collection mix-in that adds the `debugAdapters` store to
 * a [[SigilDB]] subclass. Apps that pull in `sigil-debug` declare
 * their concrete DB as
 * `class MyAppDB(...) extends SigilDB(...) with DebugCollections`
 * (combined with [[sigil.tooling.ToolingCollections]] when both
 * modules are in use), then refine `type DB = MyAppDB` on their
 * Sigil instance via [[DebugSigil]].
 */
trait DebugCollections { self: SigilDB =>
  val debugAdapters: S[DebugAdapterConfig, DebugAdapterConfig.type] = store(DebugAdapterConfig)()
}
