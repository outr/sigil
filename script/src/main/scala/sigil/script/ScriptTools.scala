package sigil.script

import fabric.rw.*
import sigil.event.Event
import sigil.tool.ToolContext
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.{ToolInput, ToolName, ToolOutput}

/** Bound as `tools` in script scope. Invokes a host tool by name and
  * decodes its typed result. */
class ScriptTools(context: ToolContext) {

  def callTool[Out](name: String, input: ToolInput)(using RW[Out]): Out =
    summon[RW[Out]].write(callToolJson(name, input))

  def callToolJson(name: String, input: ToolInput): fabric.Json = {
    val tool = context.sigil.findTools.byName(ToolName(name)).sync().getOrElse(
      throw new RuntimeException(s"callTool: no tool registered as '$name'")
    )
    val signals: List[Signal] = tool.execute(input, context.turn, Event.id()).toList.sync()
    // Sigil #265 — the settling `ToolDelta` carries the typed output
    // payload directly; serialise via the polymorphic `ToolOutput` RW
    // so callers receive the same `Json` shape they used to get from
    // the (now defunct) `ToolResults.typed` field.
    signals.collectFirst {
      case d: ToolDelta if d.output.isDefined && !d.output.contains(ToolOutput.Pending) =>
        summon[RW[ToolOutput]].read(d.output.get)
    }.getOrElse(throw new RuntimeException(s"callTool: '$name' produced no typed result"))
  }

  def has(name: String): Boolean =
    context.sigil.findTools.byName(ToolName(name)).sync().isDefined
}

object ScriptTools {
  def defaultBindings(context: ToolContext): Map[String, Any] =
    Map("tools" -> new ScriptTools(context))
}
