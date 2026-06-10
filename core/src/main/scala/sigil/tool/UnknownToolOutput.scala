package sigil.tool

import fabric.Json
import fabric.rw.*

/**
 * Opaque, lossless carrier for a persisted [[ToolOutput]] whose
 * discriminator names a subtype that is no longer registered — a
 * subtype that was renamed, moved, or removed in a newer build.
 *
 * `ToolInvoke.output` is a polymorphic field on a durable event, and
 * the events store is read typed: a single row referencing an
 * unregistered `ToolOutput` discriminator otherwise throws on the typed
 * read and bricks the whole store at boot. [[sigil.upgrade.ToolOutputReconcileUpgrade]]
 * rewrites such rows to this shape at startup so the store stays
 * readable, preserving the original block verbatim in [[raw]] (so
 * nothing is destroyed and a consumer that later re-registers the real
 * type can recover it).
 *
 * `typeTag` is the original wire discriminator (e.g.
 * `"BrowserScreenshotOutput"`); `raw` is the original serialized block
 * exactly as it was persisted, `type` field and all. Renderers and
 * curators surface it as a "legacy/unknown result" placeholder. The
 * `raw` Json is deliberately untyped here — this is a storage boundary
 * preserving bytes no current Scala type can decode, the one legitimate
 * place for a raw block.
 */
case class UnknownToolOutput(typeTag: String, raw: Json) extends ToolOutput derives RW
