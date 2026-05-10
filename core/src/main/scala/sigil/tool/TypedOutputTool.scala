package sigil.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.event.{Event, MessageRole, ToolOutcome, ToolResults}
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.signal.EventState

import scala.reflect.ClassTag

/**
 * Authoring sugar for tools that emit a **typed** result. The
 * tool's `Output` type carries the structured data; the framework
 * wraps it into a [[ToolResults]] event with the typed value
 * available as `typed: Option[fabric.Json]` and as a typed schema in
 * `outputDefinition`.
 *
 * Compose-friendly: another tool's `executeTyped` can call
 * `MyTool.invoke(input, ctx)` directly and get `Task[Output]` back
 * — no JSON parsing, no regex over rendered text. The framework
 * still publishes the inner `ToolInvoke` / `ToolResults` events so
 * the audit trail is preserved.
 *
 * Bulk-result tools (grep, glob, bash, etc.) that may produce
 * arbitrarily large output use [[sigil.tool.output.PaginatedTool]]
 * instead — paginated tree storage with `next_page` /
 * `query_tool_output` navigation. `TypedOutputTool` is for tools
 * whose typed result is bounded by construction (typically a
 * single record or a small fixed-shape struct). When a
 * `TypedOutputTool`'s rendered JSON exceeds
 * `Sigil.inlineContentThreshold` the framework truncates inline
 * with a hint — no externalization, no pointer indirection.
 */
abstract class TypedOutputTool[In <: ToolInput, Out](
  override val name: ToolName,
  override val description: String,
  override val examples: List[ToolExample] = Nil,
  override val modes: Set[Id[Mode]] = Set.empty,
  override val space: SpaceId = GlobalSpace,
  override val keywords: Set[String] = Set.empty,
  override val createdBy: Option[ParticipantId] = None
)(using ct: ClassTag[In], inputRwEv: RW[In], outputRwEv: RW[Out]) extends Tool {

  override val inputRW: RW[In] = inputRwEv

  /** RW for the typed Output shape. Used by the framework's
    * emission path to render the structured value to fabric Json
    * (for `ToolResults.typed`) and by `outputDefinition` for the
    * `find_capability` schema surface. */
  val outputRW: RW[Out] = outputRwEv

  override def outputDefinition: Option[fabric.define.Definition] = Some(outputRW.definition)

  /** Tool authors implement this. Returns `Task[Out]` — the
    * framework wraps the typed result into a `ToolResults` emission
    * with auto-externalization when oversized. */
  protected def executeTyped(input: In, context: TurnContext): Task[Out]

  /** Public composition entry. Other tools' `executeTyped` bodies
    * call this to invoke a typed tool and receive its typed result
    * directly — no JSON parsing required. The orchestrator's normal
    * dispatch path also uses this internally via [[execute]]. */
  def invoke(input: In, context: TurnContext): Task[Out] = executeTyped(input, context)

  /** Inline summary text rendered into the agent's context when the
    * typed payload exceeds `inlineContentThreshold` and gets
    * externalized. Default: truncate the JSON rendering at 200
    * chars. Tools with richer summary semantics (e.g. compile
    * results "12 errors, 3 warnings") override. */
  protected def summarize(output: Out, jsonRendered: String): String = {
    val limit = 200
    if (jsonRendered.length <= limit) jsonRendered
    else jsonRendered.take(limit) + " …"
  }

  /** Glue — implements the [[Tool]] trait's `Stream[Event]` contract
    * by running `executeTyped` and wrapping the typed result into a
    * `ToolResults` event. Inline-truncates with a hint when the
    * rendered JSON exceeds `Sigil.inlineContentThreshold` — tools
    * whose output may legitimately be huge should use
    * [[sigil.tool.output.PaginatedTool]] instead. */
  final override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
    if (!ct.runtimeClass.isInstance(input)) Stream.empty
    else {
      val typedInput = input.asInstanceOf[In]
      Stream.force(
        executeTyped(typedInput, context).map { output =>
          val typedJson = outputRW.read(output)
          val rendered  = JsonFormatter.Compact(typedJson)
          val threshold = context.sigil.inlineContentThreshold
          if (rendered.length.toLong <= threshold) emitInline(typedJson, context)
          else emitTruncated(output, rendered, threshold, context)
        }
      )
    }
  }

  private def emitInline(typedJson: fabric.Json, context: TurnContext): Stream[Event] =
    Stream.emit[Event](ToolResults(
      schemas        = Nil,
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      outcome        = ToolOutcome.Success,
      typed          = Some(typedJson),
      state          = EventState.Complete,
      role           = MessageRole.Tool
    ))

  /** When the rendered JSON exceeds the inline threshold, emit a
    * truncated `summary` text alongside the typed JSON (still
    * present) so the agent reads a concrete hint and can refine
    * its inputs. Tools that need full-size streaming should
    * migrate to [[sigil.tool.output.PaginatedTool]] — that path
    * paginates rather than truncates. */
  private def emitTruncated(output: Out, rendered: String, threshold: Long, context: TurnContext): Stream[Event] = {
    val typedJson = outputRW.read(output)
    val hint =
      s"${name.value}: result is ${rendered.length} bytes (threshold $threshold). " +
        "Truncated inline. Refine inputs to narrow the output, or — for tools that naturally " +
        "produce large bulk output — migrate the tool to PaginatedTool so the result paginates."
    Stream.emit[Event](ToolResults(
      schemas        = Nil,
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      outcome        = ToolOutcome.Success,
      typed          = Some(typedJson),
      summary        = Some(summarize(output, rendered) + "\n\n" + hint),
      state          = EventState.Complete,
      role           = MessageRole.Tool
    ))
  }
}
