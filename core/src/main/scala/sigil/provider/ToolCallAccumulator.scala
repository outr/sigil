package sigil.provider

import fabric.rw.*
import fabric.io.JsonParser
import sigil.tool.core.RespondTool
import sigil.tool.model.JsonStringFieldExtractor
import sigil.tool.{DecodeError, RefusalPayload, Tool, ToolInput, WireSurface}

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
      callId = callIdOpt.orElse(pending.callId),
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
          // Sigil #260 — a tool call that streamed no argument JSON
          // leaves the buffer empty. A zero-parameter tool has
          // nothing to emit, and Anthropic sends the `tool_use` with
          // no `input_json_delta` events at all (OpenAI sends the
          // literal `"{}"`, which is why it escaped this). An empty
          // buffer parsed by `JsonParser` yields `Json.Null`, which
          // cannot decode into the typed input — a no-args call has
          // empty-OBJECT arguments, not null. Normalise it to `{}`.
          val argsText = s.buf.toString
          val rawJsonOpt: Either[Throwable, fabric.Json] =
            if (argsText.trim.isEmpty) Right(fabric.Obj.empty)
            else
              try Right(JsonParser(argsText))
              catch {
                case t: Throwable =>
                  // Sigil #408 — before hard-failing, try a bounded mechanical
                  // repair of common model JSON slips (doubled `{{`, trailing
                  // comma). Adopt it ONLY if the repaired text re-parses; the
                  // result then flows through the SAME decode branch below, so
                  // #171 (array-when-object) and full validation still apply and
                  // a still-invalid shape surfaces the enriched diagnostic. Keep
                  // the original error otherwise, so the diagnostic reflects what
                  // the model actually sent.
                  ToolCallAccumulator.repairMalformedJson(argsText)
                    .flatMap(r =>
                      try Some(JsonParser(r))
                      catch { case _: Throwable => None })
                    .toRight(t)
              }

          rawJsonOpt match {
            case Left(parseErr) =>
              // JsonParser failed. Surface a typed diagnostic via the
              // same enriched-rule path the violation flow uses.
              val errorClass = parseErr.getClass.getSimpleName
              val errorMessage = Option(parseErr.getMessage).filter(_.nonEmpty).getOrElse("(no message)")
              val schemaSummary = ToolCallAccumulator.summarizeSchema(tool.inputRW.definition)
              val rawSnippet = argsText.take(500)
              val truncated = if (s.buf.length > 500) " (truncated)" else ""
              val rule = s"Failed to parse args for tool ${s.toolName}: " +
                s"$errorClass: $errorMessage. " +
                s"Expected shape: $schemaSummary."
              val sentArgs = Some(s"$rawSnippet$truncated")
              Vector(ProviderEvent.Error(RefusalPayload.enrichRule(tool, rule, sentArgs)))

            case Right(rawJson) =>
              // Sigil bug #171 — detect "model emitted a JSON array when
              // the schema requires an object" before invoking decode.
              // Throw `ProviderStreamException` so the next attempt routes
              // through `ProviderStrategy.errorClassifier`, symmetric with
              // `emptyBudgetBurnThrows` for DigitalOcean's degenerate
              // output mode (bug #161).
              val schemaWantsObj = tool.inputRW.definition.defType match {
                case _: fabric.define.DefType.Obj => true
                case _ => false
              }
              if (rawJson.isArr && schemaWantsObj) {
                throw new ProviderStreamException(
                  providerKey = providerKey,
                  code = 200,
                  typ = "malformed_tool_args",
                  message_ = s"Model emitted a JSON array as `${s.toolName}` arguments " +
                    s"(${s.buf.length} chars) when the schema requires an object. " +
                    "Typically an upstream instruction-following degeneration: " +
                    "strict-mode wire flag is honored by some backends and silently " +
                    "ignored by others. ErrorClassifier may route the next attempt " +
                    "to a different candidate via ProviderStrategy."
                )
              }

              // Sigil #277 phase — decode through the consolidated
              // `WireSurface[Input]`. The surface runs normalize →
              // validate → materialise in one pass, collects every
              // violation, and the resulting `DecodeError.render`
              // string is what the agent reads in the enriched
              // refusal body. The validator's multi-field surfacing
              // (one round-trip, every failed field shown) is what
              // closes the historical "agent fumbles one field per
              // turn" loop.
              tool.wireSurface.decode(rawJson) match {
                case Right(input: ToolInput @unchecked) =>
                  Vector(ProviderEvent.ToolCallComplete(s.callId, input))
                case Left(error: DecodeError) =>
                  // When the validator pre-pass found no field-scoped
                  // constraint violations and the failure is purely a
                  // materialise-throw (type-shape mismatch, missing
                  // required field — those don't fire on the constraints
                  // walker), keep the historical "Failed to parse args"
                  // phrasing the agent loop recognises. Mixed cases
                  // (any field-scoped constraint violation present)
                  // route through the "violated schema constraints"
                  // path the multi-violation refusal expects.
                  val rawSnippet = argsText.take(500)
                  val truncated = if (argsText.length > 500) " (truncated)" else ""
                  val pureMaterialise = error.violations.forall(_.path.isEmpty)
                  val rule =
                    if (pureMaterialise)
                      s"Failed to parse args for tool ${s.toolName}: ${error.render}."
                    else
                      s"Args for tool ${s.toolName} violated schema constraints: ${error.render}"
                  val sentArgs =
                    if (pureMaterialise) Some(s"$rawSnippet$truncated")
                    else Some(RefusalPayload.renderSentArgs(argsText))
                  Vector(ProviderEvent.Error(RefusalPayload.enrichRule(tool, rule, sentArgs)))
              }
          }
        case None =>
          // Sigil #271 — don't short-circuit with a ProviderEvent.Error.
          // Emit a normal ToolCallComplete carrying the raw args as a
          // JsonInput so the orchestrator's dispatch path runs uniformly.
          // `UnknownTool` is substituted for the missing name downstream
          // (`Orchestrator` falls back via `toolsByName.getOrElse(name,
          // UnknownTool)`), and its `executeResult` emits a paired
          // Tool-role Failure Message — same shape as any other tool
          // failure, so the agent loop re-triggers and self-corrects
          // instead of aborting with AgentRunawayException.
          val rawJson = scala.util
            .Try(JsonParser(s.buf.toString))
            .getOrElse(fabric.Obj.empty)
          Vector(ProviderEvent.ToolCallComplete(s.callId, sigil.tool.JsonInput(rawJson)))
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

  /**
   * Pending header for a tool call whose id and name arrived in
   * separate chunks. Holds the partial fields plus any args that
   * arrived before the header completed. Promoted to a full
   * `CallState` once both `callId` and `toolName` are set. Sigil
   * audit H8.
   */
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

  /**
   * Sigil #408 — bounded, strictly-mechanical repair of common model JSON
   * slips, tried ONLY after a genuine parse failure and adopted ONLY if the
   * result re-parses (the caller re-decodes it through the normal path, so
   * #171 / validation still apply). Peer of the existing #171 (array-when-
   * object) / #398 (fenced-JSON) coercions.
   *
   * Two structural recoveries, both applied OUTSIDE string literals so string
   * content (e.g. a `{{item}}` template) is never touched:
   *   - collapse a run of consecutive `{` to one — `{{` is never valid JSON
   *     structurally (after `{` only a quoted key or `}` may follow), so
   *     back-to-back opens can only be a model slip;
   *   - drop a trailing comma that precedes a `}` / `]` close.
   *
   * `[[` is deliberately NOT collapsed — nested arrays make it legitimately
   * valid, so collapsing would corrupt real data. Returns the repaired string
   * only when it actually changed something; `None` otherwise (nothing to
   * gain from re-parsing an unchanged string). No semantic guessing.
   */
  def repairMalformedJson(text: String): Option[String] = {
    val sb = new StringBuilder(text.length)
    val n = text.length
    var i = 0
    var inString = false
    var changed = false
    while (i < n) {
      val c = text.charAt(i)
      if (inString) {
        sb.append(c)
        if (c == '\\' && i + 1 < n) { sb.append(text.charAt(i + 1)); i += 2 } // preserve escape pair
        else { if (c == '"') inString = false; i += 1 }
      } else c match {
        case '"' => inString = true; sb.append(c); i += 1
        case '{' =>
          sb.append('{'); i += 1
          while (i < n && text.charAt(i) == '{') { changed = true; i += 1 } // collapse the doubled-open slip
        case ',' =>
          var j = i + 1
          while (j < n && text.charAt(j).isWhitespace) j += 1
          if (j < n && (text.charAt(j) == '}' || text.charAt(j) == ']')) { changed = true; i += 1 } // drop trailing comma
          else { sb.append(','); i += 1 }
        case _ => sb.append(c); i += 1
      }
    }
    if (changed) Some(sb.toString) else None
  }

  /**
   * Compact human-readable summary of a fabric [[Definition]] —
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
   * job is to remind, not duplicate.
   */
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
      case other => s"<${typeName(other)}>"
    }
  }

  /**
   * One-word type name for the schema summary. Recurses into
   * `Opt` and `Arr`; abbreviates `Obj` / `Poly` to `object` /
   * `oneOf` since spelling out nested shapes inside a one-line
   * summary defeats the purpose.
   */
  private def typeName(defType: fabric.define.DefType): String = {
    import fabric.define.DefType
    defType match {
      case DefType.Str => "string"
      case DefType.Int => "integer"
      case DefType.Dec => "number"
      case DefType.Bool => "boolean"
      case DefType.Null => "null"
      case DefType.Json => "any"
      case DefType.Arr(t) => s"array<${typeName(t.defType)}>"
      case DefType.Opt(t) => typeName(t.defType)
      case DefType.Obj(_) => "object"
      case DefType.Poly(_, _) => "oneOf"
    }
  }
}
