package sigil.browser.tool

import fabric.io.JsonFormatter
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.browser.{BrowserScript, BrowserSigil, CookieJar}
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{DefinitionToSchema, JsonSchemaToDefinition, ToolExample, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Persist a new [[BrowserScript]] under the framework's policy-
 * resolved [[sigil.SpaceId]]. The agent supplies the script's
 * surface and step list; this tool resolves the space via
 * [[BrowserSigil.browserScriptSpace]], builds the record, and writes
 * it to `SigilDB.tools` so future turns can `find_capability` and
 * invoke it like any other tool.
 *
 * **Suggestion cascade** — emits a [[ToolResults]] whose `schemas`
 * include `update_browser_script`, `delete_browser_script`, and the
 * just-created script's own schema, so the agent can demo / tweak
 * the script on the next turn (one-turn decay).
 */
case object CreateBrowserScriptTool extends TypedTool[CreateBrowserScriptInput](
  name = ToolName("create_browser_script"),
  description =
    """Persist a new browser-script tool the agent (or another agent in scope) can later invoke
      |through `find_capability`. The script's `steps` run against the per-conversation browser
      |controller — same surface as the primitive `browser_*` tools — so any sequence the agent can
      |demonstrate manually can be saved as a replayable script.
      |
      |`name` must be unique. `parameters` is a JSON Schema; leave empty to accept no args.
      |`steps` is a list of typed action records; string fields support `${arg.path}` and
      |`${outputs.<name>}` placeholders. `cookieJarId` references a previously-saved CookieJar so
      |replays restore logged-in state. `space` is an optional string hint asking the framework to
      |pin the tool under a specific space — the active Sigil's `browserScriptSpace` policy
      |decides whether to honor it.""".stripMargin,
  examples = Nil,
  keywords = Set("create", "browser", "script", "automate", "record", "save", "replay")
) {

  override protected def executeTyped(input: CreateBrowserScriptInput,
                                      ctx: TurnContext): Stream[Event] = ctx.sigil match {
    case bs: BrowserSigil =>
      Stream.force(
        bs.browserScriptSpace(ctx.chain, input.space).flatMap { resolvedSpace =>
          val script = BrowserScript(
            name        = ToolName(input.name),
            description = input.description,
            parameters  = JsonSchemaToDefinition(input.parameters),
            steps       = input.steps,
            space       = resolvedSpace,
            cookieJarId = input.cookieJarId.map(s => Id[CookieJar](s)),
            keywords    = input.keywords,
            createdBy   = Some(ctx.caller)
          )
          ctx.sigil.createTool(script).map { stored =>
            // Bug #69 — single Message(Tool) carrying the
            // confirmation, schema, and invocation hint. Replaces the
            // [ack, ToolResults] cascade whose ToolResults event
            // landed orphan-framed.
            val schemaJson = JsonFormatter.Default(DefinitionToSchema(stored.schema.input))
            val text = new StringBuilder
            text.append(s"Persisted browser script '${stored.name.value}' under space '${resolvedSpace.value}' ")
            text.append(s"(${input.steps.size} steps).\n\n")
            text.append("To invoke on a subsequent turn, emit a tool_call with:\n")
            text.append(s"  name: ${stored.name.value}\n")
            text.append(s"  arguments matching this schema:\n")
            text.append(schemaJson).append("\n\n")
            text.append("Authoring follow-ups: update_browser_script, delete_browser_script.\n")
            val ack = Message(
              participantId  = ctx.caller,
              conversationId = ctx.conversation.id,
              topicId        = ctx.conversation.currentTopicId,
              content        = Vector(ResponseContent.Text(text.toString)),
              state          = EventState.Complete,
              role           = MessageRole.Tool,
              visibility     = MessageVisibility.Agents
            )
            Stream.emit[Event](ack)
          }
        }.handleError(t => Task.pure(Stream.emit[Event](errorReply(ctx,
          s"Failed to create browser script: ${t.getMessage}"))))
      )
    case _ =>
      Stream.emit(errorReply(ctx, "Sigil instance does not mix in BrowserSigil; cannot create browser scripts."))
  }

  private def errorReply(ctx: TurnContext, text: String): Event = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(text)),
    state          = EventState.Complete,
    role           = MessageRole.Tool,
    visibility     = MessageVisibility.Agents
  )
}
