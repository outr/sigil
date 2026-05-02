package sigil.script

import fabric.rw.*
import rapid.Task
import sigil.{GlobalSpace, Sigil, SpaceId}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.tool.{JsonInput, Tool}

/**
 * Mixin trait apps stir into their [[Sigil]] subclass to enable
 * persistent script-backed tooling.
 *
 * Provides the runtime hook [[scriptExecutor]] that any persisted
 * [[ScriptTool]] resolves at execution time, and the policy hook
 * [[scriptToolSpace]] that decides which single [[SpaceId]] gets
 * assigned to a freshly created or updated script tool.
 *
 * The trait also adds polymorphic registrations for [[ScriptTool]]
 * (via [[Sigil.toolRegistrations]]) and [[ScriptResult]] (via
 * [[Sigil.eventRegistrations]]) so persisted records and emitted
 * events both round-trip through fabric without apps having to
 * remember either.
 *
 * **Space-allocation patterns** (apps override `scriptToolSpace`):
 *
 *   - **Always global** — `_ => Task.pure(GlobalSpace)`. Every
 *     created script tool is visible to everyone; ignore the agent's
 *     request.
 *   - **User-scoped** — `(chain, _) => Task.pure(MyUserSpace(chain.head))`.
 *     Pin the new tool to the calling user's space; ignore the
 *     agent's request.
 *   - **Caller-picks-from-accessible** — validate `requested ∈
 *     accessibleSpaces(chain)`; fail otherwise. Lets the agent place
 *     a tool into any space the chain is authorized for.
 *
 * The default below is the most permissive: honor the agent's
 * `requested` value if given, fall back to [[GlobalSpace]] otherwise.
 * Apps that want safer defaults override.
 */
trait ScriptSigil extends Sigil {

  /**
   * The runtime that any persisted [[ScriptTool]] resolves at execute
   * time. Defaults to a fresh [[ScalaScriptExecutor]] (REPL-backed,
   * full JVM access). Apps that need sandboxing, a different language,
   * or remote execution (via [[sigil.tool.proxy.ProxyTool]] against a
   * dedicated process) override.
   */
  def scriptExecutor: ScriptExecutor = new ScalaScriptExecutor

  /**
   * Decide the single [[SpaceId]] to assign to a freshly created or
   * updated script tool. `requested` carries the agent's hint as a
   * raw string identifier (LLMs don't speak typed `SpaceId` —
   * resolving it to a real space is app policy). When the agent
   * didn't ask for one, `requested` is `None`.
   *
   * The framework single-assignment rule applies: this returns one
   * [[SpaceId]] only — never `Set`, never `Option`. Apps that want
   * multi-space visibility for the same script create separate
   * records (the `CreateScriptToolTool` flow naturally drives this
   * if the agent calls it once per space).
   *
   * Default: ignore `requested`, return [[GlobalSpace]]. The default
   * is intentionally fail-closed against agent-supplied hints — apps
   * that want caller-picks-from-accessible behavior override and
   * implement their own string→SpaceId resolution and authz check.
   *
   * Pattern overrides:
   *
   *   - **Always global**: leave the default.
   *   - **User-scoped**: `(chain, _) => Task.pure(MyUserSpace(chain.head.value))` —
   *     ignore `requested`, derive from the chain.
   *   - **Caller-picks-from-accessible**: resolve `requested` against
   *     the app's known SpaceId catalog, then verify the resolved
   *     value is in `accessibleSpaces(chain)`; fail otherwise.
   */
  def scriptToolSpace(chain: List[ParticipantId],
                      requested: Option[String]): Task[SpaceId] =
    Task.pure(GlobalSpace)

  /**
   * Auto-register [[ScriptTool]]'s RW so persisted records round-trip
   * without apps having to remember it. Apps that override
   * `toolRegistrations` should `super.toolRegistrations ++ ...` to
   * keep the script registration intact.
   */
  override def toolRegistrations: List[RW[? <: sigil.tool.Tool]] =
    summon[RW[ScriptTool]] :: super.toolRegistrations

  /** Register [[ScriptKind]] so [[ScriptTool]] records' `kind` field
    * round-trips through fabric's polymorphic [[sigil.tool.ToolKind]]
    * discriminator. */
  override protected def toolKindRegistrations: List[RW[? <: sigil.tool.ToolKind]] =
    RW.static[sigil.tool.ToolKind](ScriptKind) :: super.toolKindRegistrations

  /**
   * Auto-register [[ScriptResult]]'s RW so the events emitted by
   * [[ExecuteScriptTool]] / persisted [[ScriptTool]]s round-trip
   * through fabric's polymorphic Signal/Event discriminator. Without
   * this, the first script execution fails at runtime when the
   * framework tries to broadcast the result. Apps that override
   * `eventRegistrations` should `super.eventRegistrations ++ ...`.
   */
  override protected def eventRegistrations: List[RW[? <: Event]] =
    summon[RW[ScriptResult]] :: super.eventRegistrations

  /**
   * Auto-register [[JsonInput]]'s RW so [[ToolInvoke]] events for
   * runtime-created [[ScriptTool]] calls (whose `inputRW` is
   * `RW[JsonInput]`) round-trip through fabric's polymorphic
   * `RW[ToolInput]` discriminator. Without this, the first
   * script-tool invocation crashes the agent loop at persistence
   * with `Type not found [JsonInput]` — the in-flight `ToolInvoke`
   * lands on the wire but the persistence step throws before any
   * settling delta can be emitted, so client chips render
   * "(input pending)" forever and the agent loop dies. Bug #53.
   */
  override def toolInputRegistrations: List[RW[? <: sigil.tool.ToolInput]] =
    summon[RW[JsonInput]] :: super.toolInputRegistrations

  /** Register [[ScriptAuthoringMode]] alongside any modes the app
    * defines. Without this the framework can't resolve `change_mode`
    * calls targeting `script-authoring`, and the script-authoring
    * tool family becomes unreachable. Apps that override `modes`
    * should `super.modes ++ ...`. */
  override protected def modes: List[Mode] = ScriptAuthoringMode :: super.modes

  /** Auto-register the script-authoring tools (introspection plus the
    * management surface) so `ToolPolicy.Active` references in
    * [[ScriptAuthoringMode]] resolve to live tool records on every
    * boot. The static-tool sync upgrade picks them up via the
    * `StaticToolSyncUpgrade` defined in [[Sigil.instance]]. Apps that
    * override `staticTools` should `super.staticTools ++ ...`. */
  override def staticTools: List[Tool] = super.staticTools ++ List(
    LibraryLookupTool,
    ClassSignaturesTool,
    ReadSourceTool,
    CreateScriptToolTool,
    UpdateScriptToolTool,
    DeleteScriptToolTool,
    ListScriptToolsTool
  )
}
