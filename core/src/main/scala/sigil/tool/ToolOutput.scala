package sigil.tool

import fabric.rw.*

/**
 * Marker for a tool's typed output payload — the `Output` half of a
 * [[Tool]]'s contract, parallel to [[ToolInput]] on the input side.
 *
 * Sigil #265 — `ToolOutput` is an open `PolyType` (apps register their
 * concrete subtypes via `Sigil.toolOutputRegistrations`) so the
 * framework can carry the typed result on the durable
 * [[sigil.event.ToolInvoke]] event itself, folded in via
 * [[sigil.signal.ToolDelta]] when the tool settles. Replaces the
 * pre-#265 paired-event model (separate `ToolResults` event linked
 * back to its invoke by `origin`) — there is now one stateful event
 * per tool call, and the framework no longer has to keep two events
 * in sync.
 *
 * Framework-shipped cases:
 *   - [[Pending]]  — the call is in flight, no output yet (the
 *                    initial state of every `ToolInvoke`).
 *   - [[Progress]] — interim status report from a long-running tool
 *                    (replaces the transient `ToolProgress` Notice).
 *
 * Concrete result types are app-defined: a tool author writes
 * `case class FooOutput(...) extends ToolOutput derives RW` and
 * registers the `RW` via `Sigil.toolOutputRegistrations`. The
 * framework ships `sigil.tool.TextToolOutput` for the common
 * markdown-text case.
 */
trait ToolOutput

object ToolOutput extends PolyType[ToolOutput]()(using scala.reflect.ClassTag(classOf[ToolOutput])) {

  /** Initial state of every [[sigil.event.ToolInvoke]] — the call
    * has been issued but the tool's `execute` hasn't yet settled it.
    * A [[sigil.signal.ToolDelta]] with `output = Some(...)` replaces
    * this with the real output (or a [[Progress]] interim). */
  case object Pending extends ToolOutput

  /** Interim progress update emitted by a long-running tool via
    * `ToolContext.progress(message, percent?)`. Folded into the live
    * invoke by a [[sigil.signal.ToolDelta]]; consumers see the chip's
    * content advance through successive Progress values before the
    * final concrete `ToolOutput` lands.
    *
    * `percent` is `Some(0.0..1.0)` when the tool can express a real
    * completion fraction; `None` for unbounded "still working" pulses. */
  case class Progress(message: String, percent: Option[Double] = None) extends ToolOutput derives RW

  /** RWs the framework registers with the polymorphic discriminator.
    * App-defined `ToolOutput` subtypes are added via
    * `Sigil.toolOutputRegistrations`. */
  val frameworkOutputRWs: List[RW[? <: ToolOutput]] = List(
    RW.static(Pending),
    summon[RW[Progress]]
  )
}

