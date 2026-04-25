package sigil.provider

import fabric.rw.*
import fabric.io.JsonParser
import sigil.tool.core.RespondTool
import sigil.tool.model.{JsonStringFieldExtractor, MultipartStreamParser, ToolStreamEvent}
import sigil.tool.{Tool, ToolInput}

import scala.collection.mutable

/**
 * Provider-agnostic accumulator for streaming tool calls.
 *
 * Buffers incoming JSON argument fragments per tool call (indexed as they
 * arrive from the upstream stream) and emits:
 *   - [[ProviderEvent.ToolCallStart]] when [[start]] is called for a new index
 *   - [[ProviderEvent.ContentBlockStart]] / [[ProviderEvent.ContentBlockDelta]]
 *     for the respond tool, while its `content` string decodes incrementally
 *     through the multipart format
 *   - [[ProviderEvent.ToolCallComplete]] for each accumulated call when
 *     [[complete]] is invoked, with fully-parsed, typed inputs
 *
 * The accumulator is constructed with the set of tools available on the
 * originating request. On completion, each call's tool name is used to look
 * up the matching tool and deserialize the accumulated JSON args using that
 * tool's specific [[Tool.inputRW]] — dispatch by name, not by a discriminator
 * injected into the args.
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
            val input: ToolInput = tool.inputRW.write(json)
            Vector(ProviderEvent.ToolCallComplete(s.callId, input))
          } catch {
            case t: Throwable =>
              Vector(ProviderEvent.Error(s"Failed to parse args for tool ${s.toolName}: ${t.getMessage}"))
          }
        case None =>
          Vector(ProviderEvent.Error(s"Unknown tool: ${s.toolName}"))
      }
    }
    streamFlush ++ completes
  }

  private case class CallState(callId: CallId, toolName: String, buf: StringBuilder, processor: Option[RespondStreamProcessor])

  final private class RespondStreamProcessor(callId: CallId) {
    private val extractor = new JsonStringFieldExtractor("content")
    private val parser = new MultipartStreamParser

    def feed(fragment: String): Vector[ProviderEvent] = {
      val text = extractor.append(fragment)
      if (text.isEmpty) Vector.empty else translate(parser.append(text))
    }

    def finish(): Vector[ProviderEvent] = translate(parser.finish())

    private def translate(events: Vector[ToolStreamEvent]): Vector[ProviderEvent] =
      events.map {
        case ToolStreamEvent.BlockStart(t, a) => ProviderEvent.ContentBlockStart(callId, t, a)
        case ToolStreamEvent.BlockDelta(t) => ProviderEvent.ContentBlockDelta(callId, t)
      }
  }
}
