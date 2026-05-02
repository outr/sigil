package sigil.provider

import fabric.rw.*
import fabric.io.JsonParser
import sigil.tool.core.RespondTool
import sigil.tool.model.JsonStringFieldExtractor
import sigil.tool.{Tool, ToolInput, ToolInputValidator}

import scala.collection.mutable

/**
 * Provider-agnostic accumulator for streaming tool calls.
 *
 * Buffers incoming JSON argument fragments per tool call (indexed as they
 * arrive from the upstream stream) and emits:
 *   - [[ProviderEvent.ToolCallStart]] when [[start]] is called for a new index
 *   - [[ProviderEvent.ContentBlockStart]] / [[ProviderEvent.ContentBlockDelta]]
 *     for the `respond` tool, while its `content` markdown string decodes
 *     incrementally — subscribers render character-by-character as the
 *     tool args stream
 *   - [[ProviderEvent.ToolCallComplete]] for each accumulated call when
 *     [[complete]] is invoked, with fully-parsed, typed inputs
 *
 * The accumulator is constructed with the set of tools available on the
 * originating request. On completion, each call's tool name is used to look
 * up the matching tool and deserialize the accumulated JSON args using that
 * tool's specific [[sigil.tool.Tool.inputRW]] — dispatch by name, not by a
 * discriminator injected into the args.
 *
 * Each provider's stream parser is responsible for translating upstream events
 * into calls to [[start]], [[appendArgs]], and at stream end [[complete]].
 */
final class ToolCallAccumulator(tools: Vector[Tool] = Vector.empty) {
  private val calls = mutable.LinkedHashMap.empty[Int, CallState]
  // Keyed by the wire-level tool name string so provider events (which
  // carry `toolName: String`) can look up without converting.
  private val toolsByName: Map[String, Tool] = tools.map(t => t.schema.name.value -> t).toMap

  /**
   * Declare a new tool call at the given stream index. Emits `ToolCallStart`.
   * If the index is already known, this is a no-op and returns empty.
   */
  def start(index: Int, callId: CallId, toolName: String): Vector[ProviderEvent] =
    if (calls.contains(index)) Vector.empty
    else {
      val processor =
        if (toolName == RespondTool.schema.name.value)
          Some(new RespondStreamProcessor(callId))
        else None
      calls(index) = CallState(callId, toolName, new StringBuilder, processor)
      Vector(ProviderEvent.ToolCallStart(callId, toolName))
    }

  /**
   * Append a JSON argument fragment to the tool call at the given index. For
   * calls wired with a streaming processor (currently: `respond`), returns any
   * `ContentBlock*` events produced by the incremental parse. Otherwise
   * returns empty — fragments accumulate silently until [[complete]].
   */
  def appendArgs(index: Int, fragment: String): Vector[ProviderEvent] =
    if (fragment.isEmpty) Vector.empty
    else
      calls.get(index) match {
        case Some(s) =>
          s.buf.append(fragment)
          s.processor.fold(Vector.empty[ProviderEvent])(_.feed(fragment))
        case None => Vector.empty
      }

  /**
   * Called at stream termination (e.g., when `finish_reason` indicates a tool
   * call completed). Emits a trailing flush of streamed content for any
   * processors, then a `ToolCallComplete` for each accumulated call with
   * fully-parsed, typed arguments. If the tool is unknown or args fail to
   * parse, an `Error` event is emitted instead.
   */
  def complete(): Vector[ProviderEvent] = {
    val streamFlush = calls.values.toVector.flatMap(_.processor.fold(Vector.empty[ProviderEvent])(_.finish()))
    val completes = calls.values.toVector.flatMap { s =>
      toolsByName.get(s.toolName) match {
        case Some(tool) =>
          try {
            val json = JsonParser(s.buf.toString)
            val violations = ToolInputValidator.validate(json, tool.inputRW.definition)
            if (violations.nonEmpty) {
              Vector(ProviderEvent.Error(
                s"Args for tool ${s.toolName} violated schema constraints: ${violations.mkString("; ")}"
              ))
            } else {
              val input: ToolInput = tool.inputRW.write(json)
              Vector(ProviderEvent.ToolCallComplete(s.callId, input))
            }
          } catch {
            case t: Throwable =>
              // Bug #72 — fabric's `RW.write` can throw with a JVM-
              // internal anonymous-class name as `getMessage` (e.g.
              // `sigil/script/UpdateScriptToolInput$$anon$3`) rather
              // than a structured "field X expected Y" diagnostic.
              // Pre-fix that opaque message was passed verbatim to
              // the agent, which then had nothing actionable to do.
              //
              // Post-fix produces a three-part diagnostic:
              //   1. exception class + message — categorizes the
              //      failure even when the message itself is JVM-
              //      internal.
              //   2. schema shape summary — `required: [name, count]`
              //      / `optional: [description]` so the agent can
              //      compare its emitted JSON against the actual
              //      expected shape. Especially useful for "agent
              //      forgot a required field" (the most common
              //      cause), which [[ToolInputValidator]] doesn't
              //      catch by design (line 39: "missing-required is
              //      the parser's job").
              //   3. constraint-violation hint — when the validator
              //      DOES find pattern/length/numeric issues that
              //      fired alongside the fabric throw.
              val errorClass = t.getClass.getSimpleName
              val errorMessage = Option(t.getMessage).filter(_.nonEmpty).getOrElse("(no message)")
              val schemaSummary = ToolCallAccumulator.summarizeSchema(tool.inputRW.definition)
              val structuralHint =
                try {
                  val violations = ToolInputValidator.validate(JsonParser(s.buf.toString), tool.inputRW.definition)
                  if (violations.isEmpty) ""
                  else s". Constraint violations: ${violations.mkString("; ")}"
                } catch { case _: Throwable => "" }
              Vector(ProviderEvent.Error(
                s"Failed to parse args for tool ${s.toolName}: " +
                  s"$errorClass: $errorMessage$structuralHint. " +
                  s"Expected shape: $schemaSummary"
              ))
          }
        case None =>
          Vector(ProviderEvent.Error(s"Unknown tool: ${s.toolName}"))
      }
    }
    streamFlush ++ completes
  }

  private case class CallState(callId: CallId,
                                toolName: String,
                                buf: StringBuilder,
                                processor: Option[RespondStreamProcessor])

  /**
   * Streams the `respond` tool's `content` field text out of in-flight JSON
   * args. The orchestrator interprets the resulting `ContentBlockStart` +
   * `ContentBlockDelta` events as a single Markdown block — no multipart
   * format decoding here; that lives in
   * [[sigil.tool.model.MarkdownContentParser]] and runs at
   * `ToolCallComplete` time when the full content string is available.
   */
  final private class RespondStreamProcessor(callId: CallId) {
    private val extractor = new JsonStringFieldExtractor("content")
    private var blockStarted = false

    def feed(fragment: String): Vector[ProviderEvent] = {
      val text = extractor.append(fragment)
      if (text.isEmpty) Vector.empty
      else {
        val out = Vector.newBuilder[ProviderEvent]
        if (!blockStarted) {
          blockStarted = true
          out += ProviderEvent.ContentBlockStart(callId, "Markdown", None)
        }
        out += ProviderEvent.ContentBlockDelta(callId, text)
        out.result()
      }
    }

    def finish(): Vector[ProviderEvent] = Vector.empty
  }
}

object ToolCallAccumulator {

  /** Compact human-readable summary of a fabric [[Definition]] —
    * field names + types — for use in tool-arg error diagnostics
    * (bug #72). Optional and required fields are listed separately
    * so the agent can quickly cross-check its emitted JSON against
    * the expected shape. Falls back to a minimal description for
    * non-object root definitions.
    *
    * Examples:
    *   `{ required: [name: string, count: integer], optional: [description: string] }`
    *   `(any JSON value)` — for `DefType.Json` roots
    *   `<string>` — for primitive-typed roots
    *
    * Intentionally lossy — the agent already has the full schema in
    * the tool definition that came down on the wire. The summary's
    * job is to remind, not duplicate. */
  private[provider] def summarizeSchema(definition: fabric.define.Definition): String = {
    import fabric.define.DefType
    definition.defType match {
      case DefType.Obj(fields) =>
        val required = fields.iterator.collect {
          case (k, d) if !d.isOpt => s"$k: ${typeName(d.defType)}"
        }.toList
        val optional = fields.iterator.collect {
          case (k, d) if d.isOpt => s"$k: ${typeName(d.defType)}"
        }.toList
        val parts = List(
          if (required.nonEmpty) Some(s"required: [${required.mkString(", ")}]") else None,
          if (optional.nonEmpty) Some(s"optional: [${optional.mkString(", ")}]") else None
        ).flatten
        if (parts.isEmpty) "{}" else parts.mkString("{ ", "; ", " }")
      case DefType.Json => "(any JSON value)"
      case other         => s"<${typeName(other)}>"
    }
  }

  /** One-word type name for the schema summary. Recurses into
    * `Opt` and `Arr`; abbreviates `Obj` / `Poly` to `object` /
    * `oneOf` since spelling out nested shapes inside a one-line
    * summary defeats the purpose. */
  private def typeName(defType: fabric.define.DefType): String = {
    import fabric.define.DefType
    defType match {
      case DefType.Str         => "string"
      case DefType.Int         => "integer"
      case DefType.Dec         => "number"
      case DefType.Bool        => "boolean"
      case DefType.Null        => "null"
      case DefType.Json        => "any"
      case DefType.Arr(t)      => s"array<${typeName(t.defType)}>"
      case DefType.Opt(t)      => typeName(t.defType)
      case DefType.Obj(_)      => "object"
      case DefType.Poly(_, _)  => "oneOf"
    }
  }
}
