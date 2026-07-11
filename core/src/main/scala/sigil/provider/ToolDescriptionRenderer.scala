package sigil.provider

import fabric.{Json, Obj}
import fabric.io.JsonFormatter
import sigil.Sigil
import sigil.tool.{Tool, ToolInput}
import sigil.tool.ToolInput.given

/**
 * Shared renderer for the wire-facing tool description every provider
 * sends in its tool/function schema. Starts from `Tool.wireDescription`
 * and, when the tool declares examples, appends an `Examples:` block
 * rendering each example's typed input as compact JSON with the
 * polymorphic `type` discriminator stripped (it isn't part of the
 * tool's argument schema).
 */
object ToolDescriptionRenderer {

  /**
   * Render the full wire description for `tool` under `mode`.
   */
  def render(tool: Tool, mode: Mode, sigil: Sigil): String = {
    val base = tool.wireDescription(mode, sigil)
    if (tool.examples.isEmpty) base
    else {
      val rendered = tool.examples.map { e =>
        val json = JsonFormatter.Compact(stripPolyDiscriminator(summon[fabric.rw.RW[ToolInput]].read(e.input)))
        s"- ${e.description}: $json"
      }.mkString("\n")
      s"$base\n\nExamples:\n$rendered"
    }
  }

  private def stripPolyDiscriminator(json: Json): Json = json match {
    case o: Obj => Obj(o.value - "type")
    case other => other
  }
}
