package sigil.provider

import fabric.rw.*
import fabric.io.JsonParser
import sigil.tool.{Tool, ToolInput}

import scala.collection.mutable

/**
 * Provider-agnostic accumulator for streaming tool calls.
 *
 * Buffers incoming JSON argument fragments per tool call (indexed as they
 * arrive from the upstream stream) and emits:
 *   - [[ProviderEvent.ToolCallStart]] when [[start]] is called for a new index
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
final class ToolCallAccumulator(tools: Vector[Tool[? <: ToolInput]] = Vector.empty) {
  private val calls = mutable.LinkedHashMap.empty[Int, (CallId, String, StringBuilder)]
  private val toolsByName: Map[String, Tool[? <: ToolInput]] = tools.map(t => t.schema.name -> t).toMap

  /**
   * Declare a new tool call at the given stream index. Emits `ToolCallStart`.
   * If the index is already known, this is a no-op and returns empty.
   */
  def start(index: Int, callId: CallId, toolName: String): Vector[ProviderEvent] =
    if (calls.contains(index)) Vector.empty
    else {
      calls(index) = (callId, toolName, new StringBuilder)
      Vector(ProviderEvent.ToolCallStart(callId, toolName))
    }

  /**
   * Append a JSON argument fragment to the tool call at the given index.
   * No events are produced — fragments accumulate silently until `complete`.
   */
  def appendArgs(index: Int, fragment: String): Unit =
    if (fragment.nonEmpty) calls.get(index).foreach { case (_, _, buf) => buf.append(fragment) }

  /**
   * Called at stream termination (e.g., when `finish_reason` indicates a tool
   * call completed). Emits a `ToolCallComplete` for each accumulated call with
   * fully-parsed, typed arguments. If the tool is unknown or args fail to
   * parse, an `Error` event is emitted instead.
   */
  def complete(): Vector[ProviderEvent] =
    calls.values.toVector.flatMap { case (cid, name, buf) =>
      toolsByName.get(name) match {
        case Some(tool) =>
          try {
            val json = JsonParser(buf.toString)
            val input: ToolInput = tool.inputRW.write(json)
            Vector(ProviderEvent.ToolCallComplete(cid, input))
          } catch {
            case t: Throwable =>
              Vector(ProviderEvent.Error(s"Failed to parse args for tool $name: ${t.getMessage}"))
          }
        case None =>
          Vector(ProviderEvent.Error(s"Unknown tool: $name"))
      }
    }
}
