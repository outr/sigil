package sigil.script

import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.{Stream, Task, Unique}
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
                      override val modified: Timestamp = Timestamp(Nowish()),
                      override val _id: Id[Tool] = Id(Unique())) extends Tool derives RW {

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
    Stream.force(
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
          Task.pure(Stream.emit[Event](errorResult(
            context,
            durationMs = System.currentTimeMillis() - started,
            message    = s"${t.getClass.getSimpleName}: ${t.getMessage}"
          )))
        }
    )
  }

  private def errorResult(context: TurnContext, durationMs: Long, message: String): ScriptResult =
    ScriptResult(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      error          = Some(message),
      durationMs     = durationMs
    )
}
