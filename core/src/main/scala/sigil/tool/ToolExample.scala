package sigil.tool

import fabric.define.{DefType, Definition}
import fabric.rw.*

/**
 * Worked example for a tool — surfaced in the tool's schema so the LLM
 * can see how the tool is meant to be used. Typed by the input, so an
 * example can only be attached (via [[ToolIO.withExamples]]) to a tool
 * whose `Input` it actually is; each attachment is validated against
 * the tool's schema at construction.
 */
case class ToolExample[+I <: ToolInput](description: String, input: I)

object ToolExample {
  given rw[I <: ToolInput](using irw: RW[I]): RW[ToolExample[I]] = RW.from(
    r = e => fabric.obj("description" -> fabric.str(e.description), "input" -> irw.read(e.input)),
    w = j => ToolExample(j("description").asString, irw.write(j("input"))),
    d = Definition(
      DefType.Obj(Map(
        "description" -> Definition(DefType.Str),
        "input" -> irw.definition
      )),
      className = Some("sigil.tool.ToolExample")
    )
  )
}
