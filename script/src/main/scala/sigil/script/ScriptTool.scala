package sigil.script

import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.{Stream, Task}
import sigil.{SpaceId, TurnContext}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.{ConversationMode, Mode}
import sigil.tool.{JsonInput, Tool, ToolExample, ToolInput, ToolName}

/**
 * Persisted tool whose execution path is a stored script, created at
 * runtime by an agent (typically via [[CreateScriptToolTool]]) and
 * written to `SigilDB.tools` via `Sigil.createTool`. The
 * [[ScriptSigil.scriptExecutor]] on the active Sigil supplies the
 * runtime; bindings expose the typed JSON input (`args`) and the
 * caller's [[TurnContext]] (`context`) into the script's scope.
 *
 * `parameters` is the JSON-Schema-equivalent [[Definition]] for the
 * args the agent must pass. It's surfaced verbatim as
 * `inputDefinition` so providers grammar-constrain to it and
 * `find_capability` returns a usable schema.
 *
 * `space` follows the framework single-assignment rule — exactly one
 * [[SpaceId]], no `Set`, no `Option`. To make a script tool available
 * under another space, copy the record (apps wire that via the
 * `scriptToolSpace` hook on [[ScriptSigil]]).
 */
case class ScriptTool(name: ToolName,
                      description: String,
                      code: String,
                      parameters: Definition,
                      override val space: SpaceId,
                      override val keywords: Set[String] = Set.empty,
                      override val modes: Set[Id[Mode]] = Set(ConversationMode.id),
                      override val examples: List[ToolExample] = Nil,
                      override val createdBy: Option[ParticipantId] = None,
                      override val created: Timestamp = Timestamp(Nowish()),
                      override val modified: Timestamp = Timestamp(Nowish())) extends Tool derives RW {

  /** Stable id derived from `(name, space)` so `Sigil.createTool`'s
    * upsert overwrites in place when an agent re-creates a tool with
    * the same name in the same space. Lives in the body (not the
    * ctor) because Scala 3 doesn't let default values reference
    * earlier params of the same parameter list. Round-trips cleanly:
    * fabric's case-class RW serializes only ctor params; on load the
    * body computes the same id from the persisted `(name, space)`. */
  override val _id: Id[Tool] = ScriptTool.id(name, space)

  override def kind: sigil.tool.ToolKind = ScriptKind

  override val inputRW: RW[JsonInput] = summon[RW[JsonInput]]

  override def inputDefinition: Definition = parameters

  override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
    val args = input match {
      case j: JsonInput => j.json
      case other        => summon[RW[ToolInput]].read(other)
    }
    context.sigil match {
      case s: ScriptSigil => runOnExecutor(s.scriptExecutor, args, context)
      case _              => Stream.emit[Event](errorResult(
        context,
        durationMs = 0L,
        message = "Sigil instance does not mix in ScriptSigil; cannot execute script tool."
      ))
    }
  }

  private def runOnExecutor(executor: ScriptExecutor,
                            args: fabric.Json,
                            context: TurnContext): Stream[Event] = {
    val bindings = Map[String, Any]("args" -> args, "context" -> context)
    val started  = System.currentTimeMillis()
    // Bug #67 — wrap the construction in `Task.defer` so synchronous
    // throws during executor.execute argument evaluation surface as a
    // ScriptResult error rather than escaping to the orchestrator's
    // dangling-tool-call fallback. Mirror of ExecuteScriptTool's fix.
    Stream.force(
      Task.defer {
        executor.execute(code, bindings)
          .map { output =>
            Stream.emit[Event](ScriptResult(
              participantId  = context.caller,
              conversationId = context.conversation.id,
              topicId        = context.conversation.currentTopicId,
              output         = Some(output),
              durationMs     = System.currentTimeMillis() - started
            ))
          }
          .handleError { t =>
            Task.pure(Stream.emit[Event](errorResult(context, started, t)))
          }
      }.handleError { t =>
        Task.pure(Stream.emit[Event](errorResult(context, started, t)))
      }
    )
  }

  private def errorResult(context: TurnContext, started: Long, t: Throwable): ScriptResult =
    ScriptResult(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      // Bug #67 — include the abbreviated stack trace, not just
      // `getMessage`. Wrapped exceptions (RuntimeException carrying
      // an InvocationTargetException carrying a NoSuchMethodError,
      // common in reflective script paths) need the root cause to
      // be useful for the agent.
      error          = Some(ExecuteScriptTool.formatThrowable(t)),
      durationMs     = System.currentTimeMillis() - started
    )

  private def errorResult(context: TurnContext, durationMs: Long, message: String): ScriptResult =
    ScriptResult(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      error          = Some(message),
      durationMs     = durationMs
    )
}

object ScriptTool {

  /** Stable record id derived from `(name, space)` so `Sigil.createTool`'s
    * upsert actually overwrites in place when an agent re-creates a tool
    * with the same name in the same space. Random ids would let
    * collisions persist as duplicate rows; that contradicted
    * [[CreateScriptToolTool]]'s docstring contract ("same name
    * overwrites") and left agents with no way to disambiguate. */
  def id(name: ToolName, space: SpaceId): Id[Tool] =
    Id[Tool](s"script::${space.value}::${name.value}")
}
