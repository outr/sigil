package sigil.tool

import fabric.define.Definition
import fabric.rw.*

/**
 * A tool's wire-shape contract as one indivisible value: input/output
 * codecs, the input [[Definition]] the schema is emitted from, the
 * derived [[WireSurface]], and the typed examples. Constructed only
 * through the factories on the companion, each of which is a
 * fail-fast gate:
 *
 *   - [[ToolIO.derived]] — the standard path; the definition is
 *     provably `inputRW.definition` and the schema-ergonomics lint
 *     runs at construction.
 *   - [[ToolIO.dynamic]] / [[ToolIO.dynamicAs]] — runtime schemas
 *     over [[JsonInput]] (MCP / script / client / unknown tools):
 *     honest about being JSON, no pretense of static typing.
 *   - [[ToolIO.withSchema]] — a hand-built definition over a typed
 *     input (dynamic enums); the constructor round-trips a probe
 *     value through both the definition and the RW and throws on
 *     disagreement, so decoupling is a checked decision instead of a
 *     silent hazard.
 *
 * [[withExamples]] validates each example at construction — render
 * through the RW, decode back through the definition's surface — so
 * an example that no longer matches its schema fails the tool's
 * instantiation, not a refusal body at runtime.
 */
final class ToolIO[I <: ToolInput, O <: ToolOutput] private (val inputRW: RW[I],
                                                             val outputRW: RW[O],
                                                             val definition: Definition,
                                                             val examples: List[ToolExample[I]]) {

  /** Single derivation surface for the schema the LLM sees, the
    * refusal example payload, pre-decode normalisation, and the
    * end-to-end decode. Biased to the first authored example. */
  lazy val surface: WireSurface[I] = WireSurface.fromDefinition(definition, inputRW, examples.headOption.map(_.input))

  /** Attach worked examples, validating each at construction. A
    * failing example throws [[ToolIOException]] naming the example. */
  def withExamples(es: ToolExample[I]*): ToolIO[I, O] = {
    val probe = WireSurface.fromDefinition(definition, inputRW)
    val violations = es.toList.flatMap { e =>
      val rendered =
        try Right(inputRW.read(e.input))
        catch { case t: Throwable => Left(s"example '${e.description}' failed to render: ${t.getMessage}") }
      rendered match {
        case Left(v) => List(v)
        case Right(json) =>
          probe.decode(json) match {
            case Left(err) => List(s"example '${e.description}' does not round-trip through the schema: ${err.render}")
            case Right(_)  => Nil
          }
      }
    }
    if (violations.nonEmpty) throw new ToolIOException(ToolIO.contextName(definition), violations)
    new ToolIO(inputRW, outputRW, definition, es.toList)
  }
}

object ToolIO {

  /** Standard derivation — the definition is `inputRW.definition` by
    * construction. Runs the schema-ergonomics lint ([[SchemaErgonomics]])
    * unconditionally: a required union field with a payload-requiring
    * variant fails the tool's instantiation. */
  def derived[I <: ToolInput, O <: ToolOutput](using irw: RW[I], orw: RW[O]): ToolIO[I, O] = {
    val definition = irw.definition
    lint(definition)
    new ToolIO(irw, orw, definition, Nil)
  }

  /** Runtime schema over [[JsonInput]] with a prose result — the
    * common dynamic-tool shape. */
  def dynamic(definition: Definition): ToolIO[JsonInput, TextToolOutput] =
    dynamicAs[TextToolOutput](definition)

  /** Runtime schema over [[JsonInput]] with a caller-chosen output
    * type (e.g. the polymorphic [[ToolOutput]] for MCP results). No
    * ergonomics lint — the schema is server- or record-provided and
    * surfaced as-is. */
  def dynamicAs[O <: ToolOutput](definition: Definition)(using orw: RW[O]): ToolIO[JsonInput, O] =
    new ToolIO(summon[RW[JsonInput]], orw, definition, Nil)

  /** Hand-built or deliberately-kept definition over a typed input
    * (dynamic enum schemas; rich unions that are a considered,
    * app-extensible surface). Round-trips a probe value synthesized
    * from the definition — optional fields INCLUDED, so a disagreement
    * confined to an optional field is caught — through the surface's
    * decode (normalise → validate → materialise via the RW) and throws
    * on disagreement.
    * The ergonomics lint does NOT run here — choosing this
    * constructor IS the recorded decision to keep the schema shape. */
  def withSchema[I <: ToolInput, O <: ToolOutput](definition: Definition)(using irw: RW[I], orw: RW[O]): ToolIO[I, O] = {
    val probeJson = WireSurface.synthesizeProbe(definition)
    WireSurface.fromDefinition(definition, irw).decode(probeJson) match {
      case Left(err) =>
        throw new ToolIOException(
          contextName(definition),
          List(s"hand-built definition and RW disagree — a probe value synthesized from the definition failed to decode: ${err.render}")
        )
      case Right(_) => ()
    }
    new ToolIO(irw, orw, definition, Nil)
  }

  private def lint(definition: Definition): Unit = {
    val findings = SchemaErgonomics.unfillableUnionFindings(WireSurface.emitSchema(definition))
    if (findings.nonEmpty) throw new ToolIOException(contextName(definition), findings)
  }

  private def contextName(definition: Definition): String =
    definition.className.getOrElse("(anonymous input)")
}
