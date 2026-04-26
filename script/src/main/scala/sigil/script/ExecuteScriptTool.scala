package sigil.script

import rapid.Stream
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

  override protected def executeTyped(input: ScriptInput, context: TurnContext): Stream[Event] = {
    val started = System.currentTimeMillis()
    Stream.force(
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
          rapid.Task {
            val ev = ScriptResult(
              participantId = context.caller,
              conversationId = context.conversation.id,
              topicId = context.conversation.currentTopicId,
              error = Some(s"${t.getClass.getSimpleName}: ${t.getMessage}"),
              durationMs = System.currentTimeMillis() - started
            )
            Stream.emit[Event](ev)
          }
        }
    )
  }
}

object ExecuteScriptTool {
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
