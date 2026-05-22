package sigil.script

import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.Task
import sigil.{SpaceId, TurnContext}
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.tool.{JsonInput, Tool, ToolExample, ToolResult, ToolName}

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
                      // Empty default — newly-created script tools are
                      // discoverable from any mode. Apps that want
                      // per-mode gating override at construction.
                      override val modes: Set[Id[Mode]] = Set.empty,
                      override val examples: List[ToolExample] = Nil,
                      override val createdBy: Option[ParticipantId] = None,
                      override val created: Timestamp = Timestamp(Nowish()),
                      override val modified: Timestamp = Timestamp(Nowish())) extends Tool derives RW {
  override def paginate: Boolean = false


  /** Stable id derived from `(name, space)` so `Sigil.createTool`'s
    * upsert overwrites in place when an agent re-creates a tool with
    * the same name in the same space. Lives in the body (not the
    * ctor) because Scala 3 doesn't let default values reference
    * earlier params of the same parameter list. Round-trips cleanly:
    * fabric's case-class RW serializes only ctor params; on load the
    * body computes the same id from the persisted `(name, space)`. */
  override val _id: Id[Tool] = ScriptTool.id(name, space)

  override def kind: sigil.tool.ToolKind = ScriptKind

  type Input  = JsonInput
  type Output = ScriptToolOutput
  override val inputRW: RW[JsonInput] = summon[RW[JsonInput]]
  override val outputRW: RW[ScriptToolOutput] = summon[RW[ScriptToolOutput]]

  override def inputDefinition: Definition = parameters

  override def executeResult(input: JsonInput,
                             context: TurnContext): Task[ToolResult[ScriptToolOutput]] =
    context.sigil match {
      case s: ScriptSigil => runOnExecutor(s.scriptExecutor, input.json, context)
      case _              => Task.pure(ToolResult.Success(ScriptToolOutput(
        error = Some("Sigil instance does not mix in ScriptSigil; cannot execute script tool."),
        durationMs = 0L
      )))
    }

  private def runOnExecutor(executor: ScriptExecutor,
                            args: fabric.Json,
                            context: TurnContext): Task[ToolResult[ScriptToolOutput]] = {
    val bindings = ScriptTools.defaultBindings(context) ++ Map("args" -> args, "context" -> context)
    val started  = System.currentTimeMillis()
    // Bug #67 — wrap the construction in `Task.defer` so synchronous
    // throws during executor.execute argument evaluation surface as a
    // populated ScriptToolOutput.error. Mirror of ExecuteScriptTool's fix.
    Task.defer {
      executor.execute(code, bindings)
        .map { output =>
          ToolResult.Success(ScriptToolOutput(
            output     = Some(output),
            durationMs = System.currentTimeMillis() - started
          ))
        }
        .handleError(t => Task.pure(errorResult(started, t)))
    }.handleError(t => Task.pure(errorResult(started, t)))
  }

  private def errorResult(started: Long, t: Throwable): ToolResult[ScriptToolOutput] =
    ToolResult.Success(ScriptToolOutput(
      // Bug #67 — include the abbreviated stack trace, not just
      // `getMessage`. Wrapped exceptions (RuntimeException carrying
      // an InvocationTargetException carrying a NoSuchMethodError,
      // common in reflective script paths) need the root cause to
      // be useful for the agent.
      error      = Some(ExecuteScriptTool.formatThrowable(t)),
      durationMs = System.currentTimeMillis() - started
    ))
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
