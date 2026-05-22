package sigil.tool

/**
 * Marker for a tool's typed output payload — the `Output` half of a
 * [[Tool]]'s contract, parallel to [[ToolInput]] on the input side.
 *
 * A concrete output type is an ordinary case class that `derives RW`;
 * the tool supplies its `RW` via [[Tool.outputRW]]. Unlike [[ToolInput]]
 * this is not a `PolyType` — tool output never round-trips through a
 * single polymorphic discriminator. Each tool owns its own `Output`
 * type, and the framework serialises it through that tool's `outputRW`
 * into the `ToolResults` event's `typed` Json.
 */
trait ToolOutput
