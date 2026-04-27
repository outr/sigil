package sigil.script

import fabric.{Json, Obj}
import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[CreateScriptToolTool]] — declares a new
 * [[ScriptTool]] for the framework to persist.
 *
 *   - `name` becomes the tool's [[sigil.tool.ToolName]]; must be
 *     unique across the catalog (a name collision overwrites — same
 *     semantics as `Sigil.createTool` upserting by id).
 *   - `description` is the natural-language description shown to
 *     other agents during `find_capability`.
 *   - `code` is the script body the framework's
 *     [[ScriptSigil.scriptExecutor]] runs. The script sees `args:
 *     fabric.Json` and `context: sigil.TurnContext` in scope.
 *   - `parameters` is a JSON-Schema object describing the args the
 *     agent must pass when invoking this tool. The framework
 *     converts it via [[sigil.tool.JsonSchemaToDefinition]] so
 *     providers grammar-constrain the call. Default: `{}` — accepts
 *     any JSON.
 *   - `keywords` boost discovery; default empty.
 *   - `space` is the agent's hint for which [[sigil.SpaceId]] to
 *     pin the tool to. The framework's
 *     [[ScriptSigil.scriptToolSpace]] hook resolves the final
 *     single-`SpaceId` value and may ignore or validate this hint
 *     per the app's policy. Default `None` — let the hook decide.
 */
case class CreateScriptToolInput(name: String,
                                 description: String,
                                 code: String,
                                 parameters: Json = Obj.empty,
                                 keywords: Set[String] = Set.empty,
                                 space: Option[String] = None) extends ToolInput derives RW
