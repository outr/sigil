package sigil.script

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolName, TypedTool}

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
                        bindings: TurnContext => Map[String, Any] = _ => Map.empty,
                        override val name: ToolName = ToolName("execute_script"),
                        override val description: String = ExecuteScriptTool.DefaultDescription)
  extends TypedTool[ScriptInput](
    name = name,
    description = description,
    examples = List(
      ToolExample(
        "Compute a derived value",
        ScriptInput(code = "val x = 1 + 2; x * 10")
      )
    )
  ) {

  /** Append the executor's advertised surface (Bug #54) so the LLM
    * knows which library identifiers are pre-imported. Without this
    * the model writes Scala-2 idioms that don't exist in the Scala 3
    * REPL classpath. */
  override def descriptionFor(mode: _root_.sigil.provider.Mode,
                              sigilInstance: _root_.sigil.Sigil): String =
    executor.advertisedSurface match {
      case Some(surface) => s"${description}\n\n$surface"
      case None          => description
    }

  override protected def executeTyped(input: ScriptInput, context: TurnContext): Stream[Event] = {
    val started = System.currentTimeMillis()
    // Bug #67 — wrap the whole construction in an outer `Task.defer`
    // and `.handleError` it so synchronous throws during evaluation
    // of `bindings(context)` (or anywhere in the executor's call-site
    // arg path) become a Task error and surface as a populated
    // `ScriptResult.error`. Without this wrap, a sync throw escapes
    // the inner `.handleError`, the orchestrator sees no
    // `MessageRole.Tool` event for the call_id, and `Provider`'s
    // dangling-tool-call fallback delivers the unhelpful
    // `"(no result recorded)"` placeholder to the agent's next turn.
    Stream.force(
      Task.defer {
        executor.execute(input.code, bindings(context))
          .map { output =>
            val ev = ScriptResult(
              participantId = context.caller,
              conversationId = context.conversation.id,
              topicId = context.conversation.currentTopicId,
              output = Some(output),
              durationMs = System.currentTimeMillis() - started
            )
            Stream.emit[Event](ev)
          }
          .handleError { t =>
            Task.pure(Stream.emit[Event](errorResult(context, started, t)))
          }
      }.handleError { t =>
        Task.pure(Stream.emit[Event](errorResult(context, started, t)))
      }
    )
  }

  private def errorResult(context: TurnContext, started: Long, t: Throwable): ScriptResult =
    ScriptResult(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      // Bug #67 — include the abbreviated stack trace so wrapped
      // exceptions (`RuntimeException` carrying an
      // `InvocationTargetException` carrying a `NoSuchMethodError`,
      // common with reflective script execution) carry their root
      // cause through to the agent. Trim to the first 8 lines —
      // enough framing for the model to reason about; not the full
      // ~80-line JVM stack.
      error = Some(ExecuteScriptTool.formatThrowable(t)),
      durationMs = System.currentTimeMillis() - started
    )
}

object ExecuteScriptTool {
  /** Format a throwable as a short stack-trace string suitable for a
    * `ScriptResult.error` field. Trims to the first 8 lines so the
    * model has the framing + the script-relevant frames without the
    * ~80-line JVM stack. Bug #67. */
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
      |configured stores — are in scope by name.""".stripMargin
}
