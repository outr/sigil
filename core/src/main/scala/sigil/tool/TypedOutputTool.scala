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

  /** Legacy entry point. Returns `Task[Out]`; the framework wraps
    * the typed result into a `ToolResults` emission. Throws via
    * `Task.error(...)` for unrecoverable failures; the default
    * [[executeTypedResult]] catches these and converts to
    * [[ToolResult.Failure]] with the exception's message and the
    * JSON-rendered input as `args` — Metals' `withErrorHandling`
    * equivalent.
    *
    * New tools that want explicit control over success-vs-logical-
    * failure (file not found, symbol not in index, validator
    * rejection) override [[executeTypedResult]] instead and emit
    * `ToolResult.Failure` directly with a hint pointing at the
    * recovery path. */
  protected def executeTyped(input: In, context: TurnContext): Task[Out] =
    Task.error(new NotImplementedError(
      s"Tool '${name.value}' must override `executeTypedResult` OR `executeTyped`."
    ))

  /** Preferred entry point — returns a [[ToolResult]] envelope so
    * the tool can signal logical failure with a structured hint
    * instead of throwing. Default wraps [[executeTyped]]: success
    * → `Success(value)`, exception → `Failure(message, args)` with
    * the failing input rendered as JSON.
    *
    * New tools override this directly when they want to:
    *   - Emit `Failure` with a `hint` that teaches the agent the
    *     recovery path (Metals' "Error: Symbol not found" + hint
    *     pattern).
    *   - Validate inputs and reject obvious misuse (lsp_did_change
    *     called with a 3-char "query string" instead of full file
    *     contents) before touching downstream resources.
    *   - Distinguish "search ran successfully, found nothing"
    *     (Success with empty payload + summary) from "search
    *     infrastructure missing" (Failure with hint). */
  protected def executeTypedResult(input: In, context: TurnContext): Task[ToolResult[Out]] =
    executeTyped(input, context)
      .map(out => ToolResult.success(out))
      .handleError { err =>
        val argsJson =
          try Some(JsonFormatter.Compact(inputRwEv.read(input)))
          catch { case _: Throwable => None }
        Task.pure(ToolResult.failure(
          message = Option(err.getMessage).getOrElse(err.getClass.getSimpleName),
          args    = argsJson
        ))
      }

  /** Public composition entry. Other tools' executeTyped bodies
    * call this to invoke a typed tool and receive its typed result
    * directly — no JSON parsing required. The orchestrator's normal
    * dispatch path also uses this internally via [[execute]].
    *
    * Returns the bare `Out` (unwrapping `ToolResult.Success`); when
    * the called tool emits `ToolResult.Failure`, this raises a
    * `ToolFailureException` so the calling tool's body can
    * `handleError` if it wants to recover or let the failure
    * propagate to its own caller. */
  def invoke(input: In, context: TurnContext): Task[Out] =
    executeTypedResult(input, context).flatMap {
      case ToolResult.Success(value)       => Task.pure(value)
      case ToolResult.Failure(msg, hint, args) =>
        Task.error(new ToolFailureException(name, msg, hint, args))
    }

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

  /** Glue — implements the [[Tool]] trait's `Stream[Event]` contract.
    * Calls [[executeTypedResult]] (the envelope-aware entry point);
    * on `Success`, wraps the typed result into a `ToolResults` event
    * (inline-truncating when rendered JSON exceeds
    * `Sigil.inlineContentThreshold`); on `Failure`, emits a Tool-role
    * `Message` carrying `ResponseContent.Failure` with the failure's
    * message + hint + args so the agent reads a structured failure
    * on its next iteration. */
  final override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
    if (!ct.runtimeClass.isInstance(input)) Stream.empty
    else {
      val typedInput = input.asInstanceOf[In]
      Stream.force(
        executeTypedResult(typedInput, context).map {
          case ToolResult.Success(output) =>
            val typedJson = outputRW.read(output)
            val rendered  = JsonFormatter.Compact(typedJson)
            val threshold = context.sigil.inlineContentThreshold
            if (rendered.length.toLong <= threshold) emitInline(typedJson, context)
            else emitTruncated(output, rendered, threshold, context)
          case ToolResult.Failure(message, hint, args) =>
            emitFailure(message, hint, args, context)
        }
      )
    }
  }

  /** Emit a Tool-role `Message` carrying `ResponseContent.Failure` —
    * the standard wire shape for a recoverable tool failure. The
    * agent's next iteration reads the failure block in the same
    * frame slot a typed Success would occupy, with the structured
    * `(message, hint, args)` triplet preserved on the wire. */
  private def emitFailure(message: String,
                          hint: Option[String],
                          args: Option[String],
                          context: TurnContext): Stream[Event] = {
    val body = (List(message) ++ hint.toList.map(h => s"\n\nHint: $h") ++
      args.toList.map(a => s"\n\nFailing args: $a")).mkString
    Stream.emit[Event](_root_.sigil.event.Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      role           = MessageRole.Tool,
      content        = Vector(_root_.sigil.tool.model.ResponseContent.Failure(reason = body, recoverable = true)),
      state          = EventState.Complete,
      visibility     = _root_.sigil.event.MessageVisibility.Agents
    ))
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
