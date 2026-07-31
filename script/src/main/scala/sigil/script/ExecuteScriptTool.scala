package sigil.script

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  Tool,
  ToolExample,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

/**
 * [[sigil.tool.Tool]] that hands the model's `code` argument to the
 * configured [[ScriptExecutor]] and emits a [[ScriptResult]] event
 * carrying the output (or error). Apps register a single instance
 * per agent that should be able to write code:
 *
 * {{{
 *   val executor = new ScalaScriptExecutor
 *   val tool     = new ExecuteScriptTool(executor, bindings = ctx => Map(
 *     "balanceTool" -> myBalanceTool,
 *     "secrets"     -> mySecretStore
 *   ))
 * }}}
 *
 * `bindings` is a function of [[TurnContext]] so apps can scope
 * exposed values per-call (e.g., resolve user-specific tools, gate
 * access by chain). The default binds nothing.
 *
 * **Surface area on the wire.** Default visibility on the emitted
 * [[ScriptResult]] is `MessageVisibility.Agents` — script output
 * stays internal to the agent loop and doesn't leak to a user
 * subscriber. Apps that want to surface script output to the user
 * subclass and emit a [[sigil.event.Message]] alongside the result.
 *
 * **Security.** Apps SHOULD NOT register this tool with an agent
 * exposed to untrusted user input unless they front the executor
 * with a sandbox or run it remotely via
 * [[sigil.tool.proxy.ProxyTool]] against an isolated process. The
 * default [[ScalaScriptExecutor]] grants full JVM access.
 */
class ExecuteScriptTool(executor: ScriptExecutor,
                        bindings: ToolContext => Map[String, Any] = ScriptTools.defaultBindings,
                        override val name: ToolName = ToolName("execute_script"),
                        override val description: String = ExecuteScriptTool.DefaultDescription)
  extends Tool {
  type Input = ScriptInput
  type Output = ScriptToolOutput
  val io: ToolIO[ScriptInput, ScriptToolOutput] = ToolIO.derived[ScriptInput, ScriptToolOutput].withExamples(
    ToolExample(
      "Compute a derived value",
      ScriptInput(code = "val x = 1 + 2; x * 10", summary = "demo: small arithmetic")
    ),
    ToolExample(
      "Count matches across files (scan pass)",
      ScriptInput(
        code = "// scan code …",
        summary = "scan: count bug references in Scala files"
      )
    ),
    ToolExample(
      "Apply edits and report what was changed (edit pass)",
      ScriptInput(
        code = "// edit code …",
        summary = "edit: remove all matched references and report counts"
      )
    )
  )

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(
      keywords = Set("execute", "run", "evaluate", "eval", "script"),
      preferIfNoBetter = true
    )
  )

  override def descriptionFor(mode: _root_.sigil.provider.Mode, sigilInstance: _root_.sigil.Sigil): String =
    executor.advertisedSurface match {
      case Some(surface) => s"$description\n\n$surface"
      case None => description
    }

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ScriptInput, context: ToolContext): Task[ToolResult[ScriptToolOutput]] = {
    val started = System.currentTimeMillis()
    // Wrap the whole construction in an outer `Task.defer` and
    // `.handleError` it so synchronous throws during evaluation of
    // `bindings(context)` (or anywhere in the executor's call-site arg
    // path), a compile error, or a runtime throw become a recoverable
    // `ToolResult.failure` the agent can read and fix, rather than an
    // unrecoverable crash or a success that hides the error.
    Task.defer {
      executor.execute(input.code, bindings(context))
        .map { output =>
          ToolResult.Success(ScriptToolOutput(
            output = Some(output),
            durationMs = System.currentTimeMillis() - started
          ))
        }
        .handleError(t => Task.pure(errorResult(t)))
    }.handleError(t => Task.pure(errorResult(t)))
  }

  private def errorResult(t: Throwable): ToolResult[ScriptToolOutput] =
    // A compile error or a runtime throw is a real failure — surface
    // it as a recoverable `ToolResult.failure` so the framework's
    // failure disposition engages and a model can't mistake it for a
    // successful run. The abbreviated stack trace carries the root
    // cause (wrapped exceptions — `RuntimeException` → `InvocationTargetException`
    // → `NoSuchMethodError`, common with reflective execution) so the
    // agent has enough to fix the script.
    ToolResult.failure(
      message = ExecuteScriptTool.formatThrowable(t),
      hint = Some("fix the script and re-run")
    )
}

object ExecuteScriptTool {

  /**
   * Format a throwable as a short stack-trace string suitable for a
   * `ScriptResult.error` field. Trims to the first 8 lines so the
   * model has the framing + the script-relevant frames without the
   * ~80-line JVM stack.
   */
  private[script] def formatThrowable(t: Throwable): String = {
    val sw = new java.io.StringWriter
    t.printStackTrace(new java.io.PrintWriter(sw))
    sw.toString.linesIterator.take(8).mkString("\n")
  }

  val DefaultDescription: String =
    """Execute the supplied source code in a host runtime and return the result.
      |
      |The agent decides what to evaluate; the framework runs it through the configured executor and
      |surfaces the stringified return value (or thrown error) as a tool result the agent sees on its
      |next turn. Use for collapsing a chain of N small reasoning steps into a single deterministic
      |computation: write the script that does the work, evaluate it once.
      |
      |`code` is the full source. `language` is an optional hint for routers that wire multiple
      |executors (default Scala). The host's bindings — typically the agent's other tools and any
      |configured stores — are in scope by name.
      |
      |`summary` is REQUIRED and should be a short, intent-specific description of THIS execution's
      |purpose (e.g. "scan: count files matching pattern", "edit: rewrite imports", "verify: re-check
      |after edit"). Distinct calls in the same turn should have distinct summaries so the chip / log
      |stream is readable without decoding the code itself. Keep it under ~80 chars.""".stripMargin
}
