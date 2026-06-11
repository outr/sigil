package sigil.tool

import fabric.Json
import fabric.rw.*

/**
 * Opaque, lossless carrier for a persisted [[ToolInput]] whose
 * discriminator names a subtype that is no longer registered — a
 * subtype that was renamed, moved, or removed in a newer build (the
 * input-side mirror of [[UnknownToolOutput]], sigil #384).
 *
 * `ToolInvoke.input` is a polymorphic field on a durable event, and the
 * events store is read typed: a single row referencing an unregistered
 * `ToolInput` discriminator otherwise throws on the typed read and
 * bricks the whole store at boot — a hard startup failure, since the
 * read happens on the boot path. Removing a tool drops its `Input` from
 * the auto-derived `ToolInput` registry, so any historical `ToolInvoke`
 * for that tool is exactly this hazard.
 * [[sigil.upgrade.ToolOutputReconcileUpgrade]] rewrites such rows to this
 * shape at startup so the store stays readable, preserving the original
 * block verbatim in [[raw]] (so nothing is destroyed and a consumer that
 * later re-registers the real type can recover it).
 *
 * `typeTag` is the original wire discriminator (e.g.
 * `"BrowserScreenshotInput"`); `raw` is the original serialized block
 * exactly as it was persisted, `type` field and all. The `raw` Json is
 * deliberately untyped here — this is a storage boundary preserving bytes
 * no current Scala type can decode, the one legitimate place for a raw
 * block.
 */
case class UnknownToolInput(typeTag: String, raw: Json) extends ToolInput derives RW
