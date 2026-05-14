package sigil.provider

import fabric.rw.*
import fabric.io.JsonParser
import sigil.tool.core.RespondTool
import sigil.tool.model.JsonStringFieldExtractor
import sigil.tool.{InputNormalizer, Tool, ToolInput, ToolInputValidator}

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
final class ToolCallAccumulator(tools: Vector[Tool] = Vector.empty,
                                providerKey: String = "unknown") {
  private val calls = mutable.LinkedHashMap.empty[Int, CallState]
  // Keyed by the wire-level tool name string so provider events (which
  // carry `toolName: String`) can look up without converting.
  private val toolsByName: Map[String, Tool] = tools.map(t => t.schema.name.value -> t).toMap

  /**
   * Declare a new tool call at the given stream index. Emits `ToolCallStart`.
   * If the index is already known, this is a no-op and returns empty.
   *
   * Convenience wrapper around [[observeHeader]] for the common case
   * where both `id` and `name` arrive in the same chunk. Most providers
   * stream the header this way (OpenAI / Anthropic), but some compat
   * backends (vLLM versions, SGLang variants) split id and name across
   * chunks — for those, use [[observeHeader]] directly.
   */
  def start(index: Int, callId: CallId, toolName: String): Vector[ProviderEvent] =
    observeHeader(index, Some(callId), Some(toolName))

  /**
   * Observe a tool-call header fragment. Either `id` or `name` (or
   * both) may be supplied. The accumulator buffers partial headers and
   * emits `ToolCallStart` exactly once, when both fields are known.
   * Sigil audit H8 — some OpenAI-compat backends (vLLM, SGLang) split
   * the header across SSE chunks: chunk 1 carries `{id: "call_x"}`,
   * chunk 2 carries `{function: {name: "foo"}}`. Pre-fix, both
   * chunks were silently dropped (the existing `start` required both
   * fields together), and subsequent `arguments` deltas accumulated
   * to no `CallState`. Net: the entire tool call vanished.
   *
   * `appendArgs` works even before the header is complete — args
   * buffer on the pending-header state and are folded into the
   * promoted `CallState` once both id + name arrive.
   */
  def observeHeader(index: Int, callIdOpt: Option[CallId], nameOpt: Option[String]): Vector[ProviderEvent] = {
    if (calls.contains(index)) return Vector.empty
    val pending = pendingHeaders.getOrElse(index, PendingHeader(None, None, new StringBuilder))
    val merged = pending.copy(
      callId   = callIdOpt.orElse(pending.callId),
      toolName = nameOpt.orElse(pending.toolName)
    )
    (merged.callId, merged.toolName) match {
      case (Some(cid), Some(name)) =>
        // Both fields now known — promote to active CallState.
        pendingHeaders.remove(index)
        val processor =
          if (name == RespondTool.schema.name.value) Some(new RespondStreamProcessor(cid))
          else None
        val buf = new StringBuilder
        if (merged.argsBuffer.nonEmpty) buf.append(merged.argsBuffer.toString)
        calls(index) = CallState(cid, name, buf, processor)
        val argFlush =
          if (merged.argsBuffer.isEmpty) Vector.empty[ProviderEvent]
          else processor.fold(Vector.empty[ProviderEvent])(_.feed(merged.argsBuffer.toString))
        Vector(ProviderEvent.ToolCallStart(cid, name): ProviderEvent) ++ argFlush
      case _ =>
        // Still partial — stash until the missing field arrives.
        pendingHeaders(index) = merged
        Vector.empty
    }
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
        case None =>
          // Sigil audit H8 — args may arrive before the header is
          // fully observed (split-header compat backends). Buffer on
          // the pending header; the buffer is flushed into the
          // promoted CallState when both id + name arrive.
          pendingHeaders.get(index) match {
            case Some(pending) =>
              pending.argsBuffer.append(fragment)
              Vector.empty
            case None =>
              // No header observed yet — bootstrap a pending entry so
              // a subsequent id-only or name-only header can fold these
              // args. Without this, args before any header chunk would
              // be lost.
              val p = PendingHeader(None, None, new StringBuilder(fragment))
              pendingHeaders(index) = p
              Vector.empty
          }
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
            val rawJson = JsonParser(s.buf.toString)
            // Bug #58 — coerce `""` → `Null` for `Option[String]`
            // fields before fabric's RW materialises the typed
            // input. Without this, models that emit `""` as their
            // schema-valid encoding of "no value" produce
            // `Some("")` on the Scala side, defeating the natural
            // `input.field.orElse(default)` idiom tool authors
            // write against. Required string fields pass through
            // unchanged — the coercion only applies to fields
            // whose Definition is `Opt(Str)`.
            val json = InputNormalizer.normalize(rawJson, tool.inputRW.definition)
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
              // Sigil bug #171 — detect the "model emitted a JSON
              // array when the schema requires an object" degenerate-
              // args signature (the Kimi-K2.5 / DeepInfra failure
              // mode where strict mode is silently ignored and the
              // model emits N copies of a respond-shaped object as an
              // array). Throw `ProviderStreamException` so the next
              // attempt routes through `ProviderStrategy.errorClassifier`
              // — symmetric with `emptyBudgetBurnThrows` for
              // DigitalOcean's degenerate-output mode (bug #161).
              //
              // The detection is best-effort: re-parse the buffer; if
              // the root is an Arr and the schema's root is an Obj,
              // raise. Anything else falls through to the generic
              // diagnostic below.
              val degenerate: Boolean = try {
                val reparsed = JsonParser(s.buf.toString)
                val rootIsArr = reparsed.isArr
                val schemaWantsObj = tool.inputRW.definition.defType match {
                  case _: fabric.define.DefType.Obj => true
                  case _                             => false
                }
                rootIsArr && schemaWantsObj
              } catch { case _: Throwable => false }
              if (degenerate) {
                throw new ProviderStreamException(
                  providerKey = providerKey,
                  code = 200,
                  typ  = "malformed_tool_args",
                  message_ = s"Model emitted a JSON array as `${s.toolName}` arguments " +
                    s"(${s.buf.length} chars) when the schema requires an object. " +
                    "Typically an upstream instruction-following degeneration: " +
                    "strict-mode wire flag is honored by some backends and silently " +
                    "ignored by others. ErrorClassifier may route the next attempt " +
                    "to a different candidate via ProviderStrategy."
                )
              }
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
    // Sigil audit H8 — any pending headers still partial at stream
    // close (one field never arrived) get a diagnostic error so we
    // surface the bug to the agent + scribe rather than silently
    // dropping. Should be vanishingly rare in practice; a real
    // compat-backend bug would manifest this way.
    val orphanPending: Vector[ProviderEvent] = pendingHeaders.values.toVector.map { p =>
      ProviderEvent.Error(
        s"Tool-call header arrived incomplete at stream close: " +
          s"callId=${p.callId.map(_.value).getOrElse("<missing>")} " +
          s"toolName=${p.toolName.getOrElse("<missing>")}. " +
          s"Compat-backend bug (provider split id and name across chunks but didn't ship both before close)."
      )
    }
    pendingHeaders.clear()
    streamFlush ++ completes ++ orphanPending
  }

  private case class CallState(callId: CallId,
                                toolName: String,
                                buf: StringBuilder,
                                processor: Option[RespondStreamProcessor])

  /** Pending header for a tool call whose id and name arrived in
    * separate chunks. Holds the partial fields plus any args that
    * arrived before the header completed. Promoted to a full
    * `CallState` once both `callId` and `toolName` are set. Sigil
    * audit H8. */
  private case class PendingHeader(callId: Option[CallId],
                                   toolName: Option[String],
                                   argsBuffer: StringBuilder)

  private val pendingHeaders: mutable.LinkedHashMap[Int, PendingHeader] =
    mutable.LinkedHashMap.empty

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
