package sigil.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.event.{Event, MessageRole, ToolOutcome, ToolResults}
import sigil.participant.ParticipantId
import sigil.provider.{ConversationMode, Mode}
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
 * Auto-externalization: when the rendered JSON exceeds
 * `Sigil.inlineContentThreshold`, the framework writes the payload
 * to `Category.ToolOutput` (Bug #9 phase 4) and emits a `ToolResults`
 * with `outputId` set + a synthesized inline summary. Tool authors
 * don't think about size; structured payloads under the threshold
 * ride inline, oversized ones externalize automatically.
 *
 * Coexists with [[TypedTool]] (untyped-output legacy) and the raw
 * [[Tool]] base. New tools with structured output use this; tools
 * whose result is fire-and-forget effect or rendered text stay on
 * the legacy shape.
 *
 * Tool authors override [[summarize]] for richer summaries when the
 * payload externalizes; the default truncates the JSON rendering.
 */
abstract class TypedOutputTool[In <: ToolInput, Out](
  override val name: ToolName,
  override val description: String,
  override val examples: List[ToolExample] = Nil,
  override val modes: Set[Id[Mode]] = Set(ConversationMode.id),
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
    * `ToolResults` event. Auto-externalizes when the rendered JSON
    * exceeds the host's `inlineContentThreshold`. */
  final override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
    if (!ct.runtimeClass.isInstance(input)) Stream.empty
    else {
      val typedInput = input.asInstanceOf[In]
      Stream.force(
        executeTyped(typedInput, context).flatMap { output =>
          val typedJson = outputRW.read(output)
          val rendered = JsonFormatter.Compact(typedJson)
          val threshold = context.sigil.inlineContentThreshold
          if (rendered.length.toLong <= threshold) Task.pure(emitInline(typedJson, context))
          else externalize(output, rendered, context)
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

  private def externalize(output: Out, rendered: String, context: TurnContext): Task[Stream[Event]] = {
    val bytes = rendered.getBytes("UTF-8")
    // Reuse the per-tool space for tenanting — same default as
    // ContentExternalizationTransform: GlobalSpace unless the app
    // overrides Sigil.externalizationSpace at the message level.
    // Tool output isn't a Message, so we route through the tool's
    // declared space (apps that need tighter scoping override the
    // tool's `space` field directly).
    val targetSpace: SpaceId = space
    context.sigil.storeToolOutput(
      space       = targetSpace,
      data        = bytes,
      contentType = "application/json"
    ).map { stored =>
      Stream.emit[Event](ToolResults(
        schemas        = Nil,
        participantId  = context.caller,
        conversationId = context.conversation.id,
        topicId        = context.conversation.currentTopicId,
        outcome        = ToolOutcome.Success,
        summary        = Some(summarize(output, rendered)),
        outputId       = Some(stored._id),
        typed          = None,
        state          = EventState.Complete,
        role           = MessageRole.Tool
      ))
    }
  }
}
