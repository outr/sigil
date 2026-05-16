package sigil.tool

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{GlobalSpace, Sigil, SpaceId, TurnContext}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.Mode

/**
 * A capability available to agents at runtime. Persisted in
 * [[sigil.db.SigilDB.tools]] so static (app-defined) tools and dynamic
 * (user-created) tools share one collection, one query path, and one
 * polymorphic RW.
 *
 * Two authoring shapes:
 *   - **Static singleton**: typically authored via [[TypedTool]] for
 *     ergonomics; persisted via `RW.static`.
 *   - **Dynamic record**: `case class ScriptTool(...) extends Tool derives RW`
 *     constructed at runtime by app flows and persisted via
 *     `Sigil.createTool`.
 *
 * `inputRW` declares the [[ToolInput]] subclass this tool consumes.
 * The provider stack parses LLM-supplied tool-call arguments through
 * that RW directly — no raw JSON crosses the dispatch boundary.
 */
trait Tool extends RecordDocument[Tool] {
  // ---- abstract ----

  def name: ToolName
  def description: String
  def inputRW: RW[? <: ToolInput]
  def execute(input: ToolInput, context: TurnContext): rapid.Stream[Event]

  /** Whether this tool returns paginated output. **Required — no
    * default.** Every Tool author must explicitly declare which
    * output shape their tool implements (sigil bug #201).
    *
    *   - `false` — the tool guarantees its result is self-limited
    *     to a size the agent can consume in one shot
    *     ([[sigil.tool.core.FindCapabilityTool]]'s ranked shortlist,
    *     `lsp_goto_definition`'s single location,
    *     [[sigil.tool.core.RecordConsentTool]]'s ack).
    *   - `true` — the tool's output is potentially unbounded; the
    *     input schema MUST expose pagination fields (offset / limit /
    *     cursor / pageSize / pageToken) and the agent is responsible
    *     for re-calling with subsequent pages
    *     ([[sigil.tool.fs.GrepTool]], [[sigil.tool.fs.BashTool]],
    *     [[sigil.tool.output.PaginatedTool]] subclasses generally).
    *
    * There is no third option. Tools whose output might exceed the
    * agent's context window without pagination must add pagination;
    * the framework will NOT silently compress tool output
    * ([[sigil.conversation.compression.StandardBlockExtractor]] no
    * longer touches ToolResult frames).
    *
    * Surfaced in [[sigil.event.CapabilityMatch]] so the agent learns
    * from discovery whether a tool is one-shot or iterative. */
  def paginate: Boolean

  // ---- defaults ----

  /** Categorical discriminator for client-side filtering — see
    * [[ToolKind]]. Defaults to [[BuiltinKind]]; subtypes from opt-in
    * modules override (e.g. `ScriptTool.kind = ScriptKind`,
    * `McpTool.kind = McpKind`). Apps building "manage your tools"
    * UIs use [[sigil.signal.RequestToolList]] with a `kinds` filter
    * to scope which records the user sees. */
  def kind: ToolKind = BuiltinKind

  /** The set of [[Mode]] discriminators this tool is discoverable in.
    * Empty (the default) means **universally discoverable** — surfaces in
    * `find_capability` regardless of which mode the conversation is in.
    *
    * Tools that legitimately want mode-gated discovery (e.g. a
    * `WebBrowserMode`-only screenshot tool, or skill-bound tools that
    * make no sense outside their mode) opt in by listing the
    * mode discriminator(s) here. Most tools — filesystem, LSP, BSP,
    * memory, web fetch, MCP — leave it empty.
    *
    * The reference filter [[DiscoveryFilter.passesAffinity]] honors
    * the empty-as-universal contract. */
  def modes: Set[Id[Mode]] = Set.empty

  /** The single [[SpaceId]] this tool is visible under. Defaults to
    * [[GlobalSpace]] — visible to every caller. Tools scoped to a
    * tenant / user / project override with their own space. There is
    * no multi-space tool: copy the record to surface the same
    * capability under a different space. */
  def space: SpaceId = GlobalSpace
  def keywords: Set[String] = Set.empty
  def examples: List[ToolExample] = Nil
  def createdBy: Option[ParticipantId] = None
  def _id: Id[Tool] = Id(name.value)
  def created: Timestamp = Tool.Epoch
  def modified: Timestamp = Tool.Epoch

  /** The schema's input definition. Defaults to `inputRW.definition`;
    * tools that need a dynamic schema (e.g. an enum populated from
    * runtime config) override this. */
  def inputDefinition: fabric.define.Definition = inputRW.definition

  /** The schema's output definition — present for tools that declare
    * a typed `Output` shape via [[TypedOutputTool]], `None` for legacy
    * tools that emit free-form Tool-role Messages. Surfaced in
    * `find_capability` results so agents can reason about the result
    * shape before calling. */
  def outputDefinition: Option[fabric.define.Definition] = None

  /** Pre-execution gates the orchestrator runs before
    * [[execute]]. Each [[ToolPrecondition]] returns either
    * [[ToolPreconditionResult.Satisfied]] (proceed) or
    * [[ToolPreconditionResult.Unsatisfied]] (skip execution; emit a
    * `Role.Tool` Message describing what needs to happen first, the
    * agent reads it on its next turn). Default empty — no gating.
    *
    * Examples:
    *   - A Slack-posting tool gates on an active OAuth token.
    *   - A code-execution tool gates on a sandbox being warm.
    *   - A tool with a paid quota gates on the caller having budget.
    *
    * Preconditions are descriptive only — they identify the gap; they
    * don't fix it. Apps wire concrete setup tools and surface their
    * names via `suggestedFix` so the agent has an explicit next call. */
  def preconditions: List[ToolPrecondition] = Nil

  /** Whether this tool requires the caller's chain to have at least
    * one accessible memory [[sigil.SpaceId]] to be useful. When `true`,
    * the framework filters the tool out of the agent's roster (and out
    * of `find_capability` results) for chains where
    * [[sigil.Sigil.accessibleSpaces]] returns empty — the tool would
    * have no place to write to / read from anyway, and surfacing it
    * would just waste tokens.
    *
    * Memory-related tools set this true (`save_memory`,
    * `unpin_memory`, `list_memories(pinned=true)`, etc.). Tools whose
    * usefulness doesn't depend on space wiring leave this false. */
  def requiresAccessibleSpaces: Boolean = false

  /** How long this tool's `ToolResults` frames should remain in the
    * curated turn input.
    *
    *   - `None` (default) — keep forever; the result is durable and
    *     stays in the model's context as the conversation evolves.
    *   - `Some(0)` — the result is ephemeral; the curator may elide
    *     the call/result pair from the next turn's prompt because
    *     the result has been folded into a more compact representation
    *     (a participant projection, a `System` frame, the system
    *     prompt's "Suggested tools" / "Current mode" sections, etc.).
    *     Used by `find_capability` and `change_mode` so their
    *     verbose results don't accumulate in context.
    *   - `Some(n)` for `n > 0` — reserved for future "keep for n more
    *     agent turns" semantics. The standard curator currently
    *     treats any positive value the same as `None`; apps wanting
    *     turn-count-aware TTL extend the curator.
    *
    * The TTL is a declaration of intent — the curator's policy
    * decides exactly when to elide. [[StandardContextCurator]] honors
    * `Some(0)` by default. */
  def resultTtl: Option[Int] = None

  /** When `true`, the framework refuses to dispatch this tool until
    * a [[sigil.event.ToolApproval]] record exists for `(toolName,
    * conversationId)` in `db.events`. The agent records consent via
    * [[sigil.tool.core.RecordConsentTool]] after observing the
    * user's reply — typically through a `respond_options` round-
    * trip the agent designs to fit the conversation. Sigil bug #83.
    *
    * First-call-per-conversation semantics: a single approved
    * record covers subsequent calls in the same conversation.
    * `approved = false` is sticky — refusal sticks until the agent
    * records a fresh approval.
    *
    * Apps opt in per-tool — most tools don't need this. Setup-
    * shaped, destructive, expensive, or external-effecting tools
    * usually do (file imports, mass deletes, payments, third-party
    * API calls). Default `false` preserves the no-gate fast path. */
  def requiresUserConsent: Boolean = false

  /** Optional toolchain identifier — when the conversation has the
    * named toolchain active (per [[sigil.Sigil.activeToolchains]]),
    * `find_capability`'s ranker boosts this tool's score by
    * [[sigil.Sigil.toolchainBoost]]. Empty (the default) means no
    * contextual boost. Sigil bug #85.
    *
    * Examples: `Some("lsp")` for LSP-backed tools (lsp_definitions,
    * lsp_diagnostics, …), `Some("bsp")` for build-server tools
    * (bsp_compile, bsp_test, …). Apps wire their own toolchain
    * names — `Some("ts-server")`, `Some("pyright")`, etc. — and
    * surface them via [[sigil.Sigil.activeToolchains]] when the
    * underlying runtime is attached to a conversation.
    *
    * The boost is what makes inspection-shaped queries land on
    * Metals' lsp_diagnostics ahead of generic ripgrep when Metals
    * is running for the conversation's workspace. */
  def toolchain: Option[String] = None

  /** When `true`, [[sigil.Sigil.findCapabilities]]'s ranker
    * subtracts [[sigil.Sigil.preferIfNoBetterPenalty]] from this
    * tool's score so it sits below domain-specific tools when both
    * match the query. Generic primitives (`grep`, `glob`, `bash`,
    * `read_file`, `execute_script`) opt in — the agent should pick
    * them only when nothing more specific applies. Sigil bug #86.
    *
    * Stays findable: the penalty is small enough that a
    * generic-only match still ranks higher than no match. When no
    * domain-specific tool is in the result set (e.g. the project
    * has no LSP backend running), generic tools are still the
    * top result.
    *
    * Default `false` preserves rank for tools whose primary purpose
    * is what they do — `respond`, `change_mode`, `start_metals`,
    * etc. — those don't need the penalty. */
  def preferIfNoBetter: Boolean = false

  /** **MCP-style annotation.** True when calling this tool has no
    * side effects beyond the local conversation log — safe to call
    * speculatively. `grep`, `glob`, `read_file`, `lsp_diagnostics`
    * are read-only; `respond`, `bash`, `edit_file` are not.
    *
    * Surfaced to the agent in [[wireDescription]] and to UI clients
    * via the tool record. Apps that want to filter risky tools
    * during exploratory iterations read this flag. Default `false`
    * — annotation is opt-in per tool. */
  def readOnly: Boolean = false

  /** **MCP-style annotation.** True when calling this tool affects
    * user-visible state irreversibly. The `respond_*` family is
    * destructive (publishes a Message, ends the turn); `bash` and
    * `edit_file` are destructive (mutates external state); LSP
    * notification tools (`lsp_did_change`, `lsp_did_open`,
    * `lsp_did_close`) are destructive (overwrites the LSP's
    * in-memory copy of the document — corruptible by misuse).
    *
    * When `true`, [[wireDescription]] prefixes the description with
    * `**ENDS YOUR TURN.**` (for `respond_*` family) or a
    * `**DESTRUCTIVE.**` lead so the LLM reads terminality first.
    * Default `false`. */
  def destructive: Boolean = false

  /** **MCP-style annotation.** True when calling this tool twice
    * with identical args produces the same result. `read_file` on
    * an unchanging file is idempotent; `bash` (non-pure commands)
    * is not. Mainly informational; UI clients use it to surface
    * "safe to retry" hints. Default `false`. */
  def idempotent: Boolean = false

  /** **MCP-style annotation.** True when this tool interacts with
    * state outside Sigil's control — filesystem, network, LSP
    * server, external API. `read_file` is open-world (filesystem
    * can change); `consult` is open-world (network call); `respond`
    * is not (purely intra-conversation). Default `false`. */
  def openWorld: Boolean = false

  /** The description the LLM sees on the wire, given runtime context.
    * Default returns [[descriptionFor]] with a destructive prefix
    * baked in when [[destructive]] is `true` — so the LLM reads
    * terminality first regardless of the tool author's description
    * body. Apps overriding [[descriptionFor]] still get the prefix
    * for free; apps overriding [[wireDescription]] take full control
    * (rare). */
  def wireDescription(mode: Mode, sigil: Sigil): String = {
    val body = descriptionFor(mode, sigil)
    if (destructive) destructivePrefix + body
    else body
  }

  /** Prefix prepended to destructive tools' descriptions on the wire.
    * Override per tool family when a more specific framing fits
    * (e.g. `respond_*` could say `**ENDS YOUR TURN.**`); the default
    * generic prefix is `**DESTRUCTIVE.**` and signals irreversibility. */
  protected def destructivePrefix: String = "**DESTRUCTIVE.** "

  /** The description the LLM sees, given runtime context (active
    * mode + the live `Sigil`). Default returns the static
    * [[description]]; tools whose documentation depends on runtime
    * state override.
    *
    * Examples of overrides:
    *   - `change_mode` enumerates the available modes the agent
    *     can switch to (since they're app-registered).
    *   - A workflow tool could enumerate the workflows visible to
    *     the caller.
    *   - A lookup tool could list the catalog's known records.
    *
    * Providers call this when building the LLM's tool list —
    * descriptions are recomputed each turn, so apps don't need to
    * worry about caching staleness. The static [[description]] is
    * still used as the cached schema's description (for
    * `find_capability` listings, etc.) so consumers that only need
    * a bag-of-tools view still see something useful. */
  def descriptionFor(mode: Mode, sigil: Sigil): String = description

  /** Render-ready schema — providers turn this into the LLM's tool list. */
  lazy val schema: ToolSchema = ToolSchema(
    id = Id[ToolSchema](_id.value),
    name = name,
    description = description,
    input = inputDefinition,
    examples = examples,
    output = outputDefinition
  )
}

object Tool extends PolyType[Tool]()(using scala.reflect.ClassTag(classOf[Tool])) with RecordDocumentModel[Tool] with JsonConversion[Tool] {
  /** Sentinel epoch for static tool timestamps. Dynamic tools set their own. */
  val Epoch: Timestamp = Timestamp(0L)

  // Expose PolyType's RW as the rw RecordDocumentModel needs.
  implicit override val rw: RW[Tool] = polyRW

  val toolName: I[String]          = field.index(_.name.value)
  val modeIds: I[Set[String]]      = field.index(_.modes.map(_.value))
  val spaceId: I[String]           = field.index(_.space.value)
  val keywordIndex: I[Set[String]] = field.index(_.keywords)
  val createdByIndex: I[Option[String]] = field.index(_.createdBy.map(_.value))

  /**
   * Tokenized full-text index over the tool's name + description + curated
   * keywords. Backs `find_capability`'s BM25-scored search via
   * [[sigil.tool.DbToolFinder]] — Lucene tokenises the joined string,
   * the search query OR-combines per-keyword `TermQuery`s, and the
   * `BestMatch` sort returns documents in descending relevance order.
   *
   * `keywords` is repeated 5× in the indexed string so BM25's term-
   * frequency signal weights a tool author's curated intent surface
   * above incidental description prose. Without the boost, a long
   * description with accidentally-matching tokens can outscore a
   * tool whose keywords match the query exactly — see sigil bug
   * #158 for the concrete failure case (`change_mode` outranking
   * `pin_complexity` on a tier-pinning query).
   *
   * Apps can rebuild the searchable surface per tool by overriding any
   * of the source fields; the index recomputes on `tools.upsert`.
   */
  val searchText: lightdb.field.Field.Tokenized[Tool] =
    field.tokenized("searchText", (t: Tool) => {
      val keywordBlock =
        if (t.keywords.isEmpty) ""
        else Iterator.fill(KeywordSearchBoost)(t.keywords.mkString(" ")).mkString(" ")
      s"${t.name.value} ${t.description} $keywordBlock"
    })

  /** Multiplier applied to a tool's `keywords` block within the
    * indexed [[searchText]]. Repeating the curated tokens N times
    * raises BM25's term-frequency contribution from `keywords` so the
    * ranker honors intent surface over description prose. 5× is the
    * default — high enough to flip cases like sigil bug #158, low
    * enough that a tool with no keywords still surfaces from a
    * description match. */
  val KeywordSearchBoost: Int = 5
}
