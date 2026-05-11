package sigil.tool

/**
 * Raised by [[TypedOutputTool.invoke]] when the called tool emits
 * [[ToolResult.Failure]]. Lets one tool's body call another tool
 * via `invoke` and either pattern-match on `handleError` to recover,
 * or let the failure propagate to its own caller (which sees the
 * same exception and either recovers or surfaces it).
 *
 * Carries the originating tool's name + the structured failure
 * fields so the propagated error is debuggable and machine-readable.
 * Distinct from a thrown exception inside an `executeTyped` body —
 * those auto-convert to `ToolResult.Failure(message = e.getMessage)`
 * via [[TypedOutputTool.executeTypedResult]]'s default wrap. This
 * exception is the *re-raised* form of an already-structured Failure.
 */
final class ToolFailureException(val toolName: ToolName,
                                 val failureMessage: String,
                                 val hint: Option[String],
                                 val args: Option[String])
  extends RuntimeException({
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    parts += s"${toolName.value} failed: $failureMessage"
    hint.foreach(h => parts += s"Hint: $h")
    args.foreach(a => parts += s"Args: $a")
    parts.mkString(" — ")
  })
