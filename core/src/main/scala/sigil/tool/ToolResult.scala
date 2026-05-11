package sigil.tool

/**
 * Result envelope for tools that need to signal logical failure
 * (file not found, symbol not in index, search ran but no matches
 * with infrastructure missing, validator rejection, …) without
 * resorting to exceptions or empty success.
 *
 * Mirrors MCP's `CallToolResult` (`{content, isError}`) which Sigil
 * already consumes when calling external MCP servers — see
 * `mcp/src/main/scala/sigil/mcp/McpTool.scala`. Authoring tools used
 * to be asymmetric: success types or thrown exceptions only. Now
 * Sigil-as-author speaks the same shape as Sigil-as-client.
 *
 * Sealed sum so callers pattern-match exhaustively. Tools opt into
 * the envelope by overriding [[TypedOutputTool.executeTypedResult]];
 * existing tools that only override the legacy `executeTyped` get a
 * default wrap (success → Success; thrown error → Failure with the
 * exception message + JSON-serialised input as args).
 *
 * `Failure.hint` is the high-leverage field — it teaches the agent
 * what to do next ("if you wanted to read a file, call read_file
 * via find_capability(...)") instead of just reporting the dead
 * end. Use it when the failure has a recoverable shape; leave it
 * `None` when the failure is terminal.
 *
 * `Failure.args` is the failing input rendered as JSON; the agent's
 * next iteration sees what it sent paired with why the call didn't
 * land. Optional — tools producing Failure from a non-input source
 * (precondition check, infrastructure state) leave it empty.
 */
sealed trait ToolResult[+O]

object ToolResult {

  /** The tool ran successfully and produced a typed payload. The
    * framework converts `value` to fabric `Json` via the tool's
    * `outputRW` at emission time — no [[fabric.rw.RW]] context
    * bound on `Out` here because the envelope itself doesn't
    * round-trip; only the rendered wire shape (the `ToolResults`
    * event's `typed: Option[Json]`) does. */
  final case class Success[O](value: O) extends ToolResult[O]

  /** The tool didn't produce a useful payload — the call hit a
    * recoverable shape (logical mismatch, missing precondition,
    * invalid input the framework's grammar didn't catch). */
  final case class Failure(message: String,
                           hint: Option[String] = None,
                           args: Option[String] = None) extends ToolResult[Nothing]

  /** Convenience constructor: wrap a value in `Success`. */
  def success[O](value: O): ToolResult[O] = Success(value)

  /** Convenience constructor: build a `Failure` with optional hint
    * and args. The type parameter is `Nothing`-widened so callers
    * don't have to spell the output type at the failure site. */
  def failure(message: String,
              hint: Option[String] = None,
              args: Option[String] = None): ToolResult[Nothing] =
    Failure(message, hint, args)
}
