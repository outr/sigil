package sigil.browser.tool

import fabric.io.JsonFormatter
import fabric.{arr, num, obj, str}
import rapid.{Stream, Task}
import sigil.browser.BrowserScript
import sigil.browser.WebBrowserMode
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolName, TypedTool}
import sigil.{GlobalSpace, TurnContext}

/**
 * List every [[BrowserScript]] the caller's `accessibleSpaces`
 * authorizes them to see (plus any in [[GlobalSpace]]). Returns a
 * compact JSON array — name, description, step count, space, jar
 * binding.
 */
case object ListBrowserScriptsTool
  extends TypedTool[ListBrowserScriptsInput](
    name = ToolName("list_browser_scripts"),
    description =
      "List browser-script tools accessible to the caller (filtered by space scoping).",
    modes = Set(WebBrowserMode.id),
    keywords = Set("list", "browser", "scripts", "browse", "find")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: ListBrowserScriptsInput,
                                      ctx: TurnContext): Stream[Event] = Stream.force(
    ctx.sigil.accessibleSpaces(ctx.chain).flatMap { accessible =>
      ctx.sigil.withDB(_.tools.transaction { tx =>
        tx.query.toList.map { tools =>
          val scripts = tools.collect {
            case s: BrowserScript if s.space == GlobalSpace || accessible.contains(s.space) => s
          }
          val payload = arr(scripts.map(s =>
            obj(
              "name" -> str(s.name.value),
              "description" -> str(s.description),
              "stepCount" -> num(s.steps.size),
              "space" -> str(s.space.value),
              "cookieJarId" -> s.cookieJarId.map(j => str(j.value)).getOrElse(fabric.Null),
              "keywords" -> arr(s.keywords.toList.map(str)*)
            ))*)
          Stream.emit[Event](Message(
            participantId = ctx.caller,
            conversationId = ctx.conversation.id,
            topicId = ctx.conversation.currentTopicId,
            content = Vector(ResponseContent.Text(JsonFormatter.Compact(payload))),
            state = EventState.Complete,
            role = MessageRole.Tool,
            visibility = MessageVisibility.Agents
          ))
        }
      })
    }
  )
}
