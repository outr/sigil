package sigil.provider

import fabric.Obj
import fabric.io.JsonParser

import scala.collection.mutable

/**
 * Provider-agnostic accumulator for streaming tool calls.
 *
 * Buffers incoming JSON argument fragments per tool call (indexed as they
 * arrive from the upstream stream) and emits:
 *   - [[ProviderEvent.ToolCallStart]] when [[start]] is called for a new index
 *   - [[ProviderEvent.ToolCallComplete]] for each accumulated call when
 *     [[complete]] is invoked, with the fully-parsed inputs
 *
 * Each provider's stream parser is responsible for translating upstream events
 * into calls to [[start]], [[appendArgs]], and at stream end [[complete]].
 */
final class ToolCallAccumulator {
  private val calls = mutable.LinkedHashMap.empty[Int, (CallId, String, StringBuilder)]

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
   * call completed). Emits a `ToolCallComplete` for each accumulated call
   * with fully-parsed arguments.
   */
  def complete(): Vector[ProviderEvent] =
    calls.values.toVector.map { case (cid, _, buf) =>
      val parsed =
        try JsonParser(buf.toString).asObj
        catch { case _: Throwable => Obj.empty }
      ProviderEvent.ToolCallComplete(cid, parsed)
    }
}
