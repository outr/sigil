package sigil.script

import fabric.rw.*
import sigil.TurnContext
import sigil.event.{Event, ToolResults}
import sigil.tool.{ToolInput, ToolName}

/**
 * Bound as `tools` in script scope. Invokes a host tool by name and
 * decodes its typed result.
 */
class ScriptTools(context: TurnContext) {

  def callTool[Out](name: String, input: ToolInput)(using RW[Out]): Out =
    summon[RW[Out]].write(callToolJson(name, input))

  def callToolJson(name: String, input: ToolInput): fabric.Json = {
    val tool = context.sigil.findTools.byName(ToolName(name)).sync().getOrElse(
      throw new RuntimeException(s"callTool: no tool registered as '$name'")
    )
    val events: List[Event] = tool.execute(input, context).toList.sync()
    events.collectFirst {
      case t: ToolResults if t.typed.isDefined => t.typed.get
    }.getOrElse(throw new RuntimeException(s"callTool: '$name' produced no typed result"))
  }

  def has(name: String): Boolean =
    context.sigil.findTools.byName(ToolName(name)).sync().isDefined
}

object ScriptTools {
  def defaultBindings(context: TurnContext): Map[String, Any] =
    Map("tools" -> new ScriptTools(context))
}
