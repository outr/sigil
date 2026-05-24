package sigil.browser.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import fabric.{arr, num, obj, str}
import rapid.Task
import sigil.browser.BrowserScript
import sigil.browser.WebBrowserMode
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}
import sigil.GlobalSpace
import sigil.tool.ToolContext

/** List every [[BrowserScript]] the caller's `accessibleSpaces`
  * authorizes them to see (plus any in [[GlobalSpace]]). Returns a
  * compact JSON array — name, description, step count, space, jar
  * binding. */
case object ListBrowserScriptsTool extends Tool {
  type Input  = ListBrowserScriptsInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[ListBrowserScriptsInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("list_browser_scripts")
  val description =
    "List browser-script tools accessible to the caller (filtered by space scoping)."
  override val modes = Set(WebBrowserMode.id)
  override val keywords = Set("list", "browser", "scripts", "browse", "find")

  override def executeResult(input: ListBrowserScriptsInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    ctx.sigil.accessibleSpaces(ctx.chain).flatMap { accessible =>
      ctx.sigil.withDB(_.tools.transaction { tx =>
        tx.query.toList.map { tools =>
          val scripts = tools.collect {
            case s: BrowserScript if s.space == GlobalSpace || accessible.contains(s.space) => s
          }
          val payload = arr(scripts.map(s => obj(
            "name"        -> str(s.name.value),
            "description" -> str(s.description),
            "stepCount"   -> num(s.steps.size),
            "space"       -> str(s.space.value),
            "cookieJarId" -> s.cookieJarId.map(j => str(j.value)).getOrElse(fabric.Null),
            "keywords"    -> arr(s.keywords.toList.map(str)*)
          ))*)
          ToolResult.Success(TextToolOutput(JsonFormatter.Compact(payload)))
        }
      })
    }
}
