package sigil.tool.client

import fabric.define.Definition
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{JsonInput, JsonSchemaToDefinition, TextToolOutput, Tool, ToolName, ToolResult}

/**
 * A UI-registered interaction tool — the in-memory [[Tool]] the
 * framework materializes from a [[ClientToolSpec]]. The LLM sees the
 * client's schema verbatim (via [[JsonSchemaToDefinition]]); the
 * parsed call arrives as [[JsonInput]]. Execution is the inversion of
 * a server tool: the durable [[sigil.event.ToolInvoke]] broadcast IS
 * the dispatch — the registering UI observes it on its signal stream
 * and performs the interaction.
 *
 *   - Fire-and-forget (`expectsResult = false`): the call settles
 *     immediately with an acknowledgment. UI navigation needs no
 *     round-trip; the agent moves on.
 *   - Round-trip (`expectsResult = true`): the call parks until the
 *     client answers with [[sigil.signal.ClientToolResult]] or
 *     [[sigil.Sigil.clientToolResultTimeoutMs]] elapses — timeout and
 *     disconnect settle a recoverable Failure, never a fabricated
 *     success.
 *
 * Never persisted: client tools live in the
 * [[ClientToolRegistry]] for exactly as long as their registration —
 * a durable record for a tool that can only execute while a client is
 * attached would put a dead entry in the roster.
 */
final class ClientTool(val spec: ClientToolSpec,
                       conversationId: lightdb.id.Id[sigil.conversation.Conversation],
                       registry: ClientToolRegistry) extends Tool {
  type Input  = JsonInput
  type Output = TextToolOutput
  val inputRW: RW[JsonInput] = summon[RW[JsonInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  override val name: ToolName = ToolName(spec.name)
  override val description: String = spec.description
  override val keywords: Set[String] = spec.keywords
  override def readOnly: Boolean = spec.readOnly
  override def destructive: Boolean = spec.destructive

  override def inputDefinition: Definition = JsonSchemaToDefinition(spec.inputSchema)

  override def executeResult(input: JsonInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    if (!registry.isLive(conversationId, spec.name))
      Task.pure(ToolResult.failure(
        s"`${spec.name}` is a UI tool whose client is no longer connected — the interface that " +
          "registered it has detached. Continue without it, or ask the user to reopen the app."))
    else if (!spec.expectsResult)
      Task.pure(ToolResult.Success(TextToolOutput(
        s"Dispatched `${spec.name}` to the connected UI. The interface acts on it directly; no result payload is returned.")))
    else
      registry.awaitResult(context.invokeId, context.sigil.clientToolResultTimeoutMs).map {
        case ClientToolRegistry.Answer(content, isError) =>
          if (isError) ToolResult.failure(s"The UI reported an error for `${spec.name}`: $content")
          else ToolResult.Success(TextToolOutput(content))
        case ClientToolRegistry.TimedOut =>
          ToolResult.failure(
            s"The UI did not answer `${spec.name}` within ${context.sigil.clientToolResultTimeoutMs}ms — " +
              "the client may be busy, backgrounded, or disconnected. Do not assume the interaction happened.")
      }
}
