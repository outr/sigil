package sigil.tool.client

import fabric.define.Definition
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, JsonInput, JsonSchemaToDefinition, MutationTargeting, Resolution, TextToolOutput, Tool, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

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
final class ClientTool(val clientSpec: ClientToolSpec,
                       conversationId: lightdb.id.Id[sigil.conversation.Conversation],
                       registry: ClientToolRegistry) extends Tool {
  type Input  = JsonInput
  type Output = TextToolOutput

  val spec: ToolSpec = ClientTool.specFor(clientSpec)

  /** The client's registered schema over honest JSON args. */
  val io: ToolIO[JsonInput, TextToolOutput] = ToolIO.dynamic(JsonSchemaToDefinition(clientSpec.inputSchema))

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: JsonInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    if (!registry.isLive(conversationId, clientSpec.name))
      Task.pure(ToolResult.failure(
        s"`${clientSpec.name}` is a UI tool whose client is no longer connected — the interface that " +
          "registered it has detached. Continue without it, or ask the user to reopen the app."))
    else if (!clientSpec.expectsResult)
      Task.pure(ToolResult.Success(TextToolOutput(
        s"Dispatched `${clientSpec.name}` to the connected UI. The interface acts on it directly; no result payload is returned.")))
    else
      registry.awaitResult(context.invokeId, context.sigil.clientToolResultTimeoutMs).map {
        case ClientToolRegistry.Answer(content, isError) =>
          if (isError) ToolResult.failure(s"The UI reported an error for `${clientSpec.name}`: $content")
          else ToolResult.Success(TextToolOutput(content))
        case ClientToolRegistry.TimedOut =>
          ToolResult.failure(
            s"The UI did not answer `${clientSpec.name}` within ${context.sigil.clientToolResultTimeoutMs}ms — " +
              "the client may be busy, backgrounded, or disconnected. Do not assume the interaction happened.")
      }
}

object ClientTool {

  /** Derive the [[ToolSpec]] from a client registration. The name must
    * satisfy the provider grammar (registration fails loudly
    * otherwise); a blank description or empty keyword set is repaired
    * with a warn so a sloppy client can't leave the tool unfillable. */
  def specFor(clientSpec: ClientToolSpec): ToolSpec = {
    val name = ToolName.parse(clientSpec.name).fold(
      err => throw new IllegalArgumentException(s"Client tool registration rejected: $err"),
      identity
    )
    val description =
      if (clientSpec.description.isBlank) {
        scribe.warn(s"Client tool '${clientSpec.name}' registered with a blank description; substituting a stub.")
        s"UI interaction tool `${clientSpec.name}` registered by the connected client."
      } else clientSpec.description
    val keywords =
      if (clientSpec.keywords.nonEmpty) clientSpec.keywords
      else Set(clientSpec.name)
    val effect =
      if (clientSpec.destructive) Effect.Destructive(MutationTargeting.none, "DESTRUCTIVE.")
      else if (clientSpec.readOnly) Effect.ReadOnly(Freshness.Stable)
      else Effect.Mutating(MutationTargeting.none)
    ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = effect),
      discovery = DiscoverySpec(keywords = keywords)
    )
  }
}
