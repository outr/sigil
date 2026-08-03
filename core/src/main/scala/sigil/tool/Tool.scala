package sigil.tool

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.{GlobalSpace, Sigil, SpaceId, TurnContext}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.signal.Signal

/**
 * A capability available to agents at runtime. Persisted in
 * [[sigil.db.SigilDB.tools]] so static (app-defined) tools and dynamic
 * (user-created) tools share one collection, one query path, and one
 * polymorphic RW.
 *
 * **Authoring contract.** A tool declares a typed [[Input]] and a typed
 * [[Output]] (abstract type members), carries its wire shape as one
 * [[ToolIO]] value, and implements exactly one body via [[resolve]] —
 * [[Resolution.Simple]] (return the typed output; a throw becomes a
 * recoverable failure) or [[Resolution.Explicit]] (logical failures ride
 * the [[ToolResult]] envelope in-band). `execute` — the `Stream[Signal]`
 * surface the orchestrator drives — is `final` and delegates wholly to
 * [[ToolExecutor]], the single pipeline that runs gates, unwraps the
 * resolution, drains emitted events, bounds output, and builds exactly
 * one paired result event. A tool cannot emit a free-form,
 * possibly-result-less event stream; the call/result pairing invariant
 * holds by construction.
 *
 * Durable events that are not the tool's result (a `change_mode`'s
 * `ModeChange`, a `respond`'s user-visible Message) are emitted via
 * `ctx.emit` during execution — see [[ToolContext]].
 *
 * `Tool` itself is monomorphic — the typing is carried by the `Input` /
 * `Output` type members, not type parameters — so `RecordDocument[Tool]`,
 * the polymorphic `RW[Tool]`, and the DB collection are all unparameterised.
 */
trait Tool extends RecordDocument[Tool] {
  // ---- abstract ----

  /** Identity + capabilities, validated at creation by
    * [[ToolSpec.apply]]. The single required metadata member — every
    * other metadata accessor derives from it. Declared `def` so an
    * implementation whose spec depends on a value initialized in its
    * own body (a decorator forwarding `underlying.spec`) can supply a
    * `lazy val`; the common `val spec = ToolSpec(...)` is unaffected. */
  def spec: ToolSpec

  /** The typed argument shape this tool consumes. */
  type Input <: ToolInput

  /** The typed result payload this tool produces. */
  type Output <: ToolOutput

  /** Wire-shape contract: input/output codecs, the input definition,
    * the derived [[WireSurface]], and the typed examples — one
    * indivisible value built by a [[ToolIO]] factory
    * (`ToolIO.derived` / `.dynamic` / `.withSchema`), each a
    * fail-fast construction gate. */
  def io: ToolIO[Input, Output]

  /** The tool's body — exactly one shape, unwrapped only by
    * [[ToolExecutor]]. */
  protected def resolve: Resolution[Input, Output]

  /** Framework bridge to the protected [[resolve]] so the executor
    * (the only legitimate unwrapper) can read it. */
  private[sigil] final def resolution: Resolution[Input, Output] = resolve

  // ---- spec-derived identity ----
  // `name` / `description` / `keywords` / `space` / `modes` are
  // non-final ONLY so persisted dynamic records (ScriptTool) whose
  // constructor parameters carry these fields can round-trip through
  // fabric with the stored field names; such records build `spec`
  // from the same values, so the two never diverge.

  def name: ToolName = spec.name
  def description: String = spec.description
  def keywords: Set[String] = spec.discovery.keywords

  /** The single [[SpaceId]] this tool is visible under. [[GlobalSpace]]
    * means visible to every caller; there is no multi-space tool —
    * copy the record to surface the same capability under a
    * different space. */
  def space: SpaceId = spec.discovery.space

  /** The set of [[Mode]] discriminators this tool is discoverable in.
    * Empty means universally discoverable — see
    * [[DiscoveryFilter.passesAffinity]]. */
  def modes: Set[Id[Mode]] = spec.discovery.modes

  // ---- io-derived accessors ----

  final def inputRW: RW[Input] = io.inputRW
  final def outputRW: RW[Output] = io.outputRW

  /** The schema's input definition — [[ToolIO.definition]], provably
    * `inputRW.definition` for derived IO and construction-checked for
    * `withSchema` / dynamic IO. */
  final def inputDefinition: fabric.define.Definition = io.definition

  /** The schema's output definition — the declared typed [[Output]]
    * shape. Surfaced in `find_capability` results so agents (and UIs)
    * can reason about the result shape before calling. */
  final def outputDefinition: Option[fabric.define.Definition] = Some(io.outputRW.definition)

  /** Worked examples, validated at [[ToolIO.withExamples]] construction.
    * Non-final ONLY so persisted dynamic records whose constructor
    * parameters carry an `examples` field can round-trip through
    * fabric with the stored field name. */
  def examples: List[ToolExample[Input]] = io.examples

  /** Consolidated wire-surface derivation — single source for the schema
    * the LLM sees, the example payload refusals show, the pre-decode
    * coercion normalisation pass, and the end-to-end decode. */
  final def wireSurface: WireSurface[Input] = io.surface

  /** When `true`, the framework never truncates or files this tool's
    * output on overflow — it is emitted inline verbatim. Derived from
    * [[OutputBounds.SelfBounded]]: the tool guarantees it has sized
    * its own output (e.g. `find_capability` trims its roster to the
    * model's context window; its result must arrive intact). */
  final def boundsOutputItself: Boolean = spec.profile.output == OutputBounds.SelfBounded

  // ---- framework glue (final) ----

  /** The `Stream[Signal]` surface the orchestrator drives. **Final** —
    * tools author via [[resolve]] instead. Delegates wholly to
    * [[ToolExecutor]] with the full gate pipeline
    * ([[GateContext.Gated]]): consent → preconditions → resolution →
    * emit-buffer drain → output bounding → exactly one settling
    * [[sigil.signal.ToolDelta]] that folds the typed output, outcome,
    * and final state onto the originating `ToolInvoke`. */
  final def execute(input: ToolInput,
                    turn: TurnContext,
                    invokeId: Id[Event],
                    invokedName: ToolName = name,
                    currentMessageId: Option[Id[Event]] = None): Stream[Signal] =
    ToolExecutor.execute(this, input, turn, invokeId, invokedName, currentMessageId, GateContext.Gated)

  /** Public composition entry. Another tool's body calls this to invoke
    * a tool and receive its typed [[Output]] directly — no JSON
    * parsing. Routes through [[ToolExecutor]] with
    * [[GateContext.PreGated]]: the consent gate is skipped deliberately
    * (and recorded), preconditions still run. A [[ToolResult.Failure]]
    * (or an unsatisfied precondition) raises [[ToolFailureException]]
    * so the caller can `handleError` or let it propagate. */
  final def invoke(input: Input, context: ToolContext): Task[Output] =
    ToolExecutor.invoke(this)(input, context)

  /** Inline summary text rendered when the typed payload exceeds
    * `inlineContentThreshold`. Default: truncate the rendered form at
    * 200 chars. Tools with richer summary semantics override. */
  def summarize(output: Output, jsonRendered: String): String = {
    // Prefer the unwrapped text for text outputs — the inner text IS
    // the result; previewing the `{"text":"…"}` JSON envelope wraps the
    // bounded head, making it inconsistent with non-overflow results.
    val source = output match {
      case t: TextToolOutput => t.text
      case o                 => o.modelText.getOrElse(jsonRendered)
    }
    if (source.length <= 200) source else source.take(200) + " …"
  }

  // ---- defaults ----

  /** Categorical discriminator for client-side filtering — see
    * [[ToolKind]]. Apps building "manage your tools" UIs use
    * [[sigil.signal.RequestToolList]] with a `kinds` filter to scope
    * which records the user sees. */
  final def kind: ToolKind = spec.discovery.kind

  def createdBy: Option[ParticipantId] = None
  def _id: Id[Tool] = Id(name.value)
  def created: Timestamp = Tool.Epoch
  def modified: Timestamp = Tool.Epoch

  /** Pre-execution gates [[ToolExecutor]] runs before the resolution.
    * Each [[ToolPrecondition]] returns either
    * [[ToolPreconditionResult.Satisfied]] (proceed) or
    * [[ToolPreconditionResult.Unsatisfied]] (skip execution; emit a
    * `Role.Tool` Message describing what needs to happen first, the
    * agent reads it on its next turn).
    *
    * Preconditions are descriptive only — they identify the gap; they
    * don't fix it. Apps wire concrete setup tools and surface their
    * names via `suggestedFix` so the agent has an explicit next call. */
  final def preconditions: List[ToolPrecondition] = spec.profile.gates.preconditions

  /** Whether this tool requires the caller's chain to have at least
    * one accessible memory [[sigil.SpaceId]] to be useful. When `true`,
    * the framework filters the tool out of the agent's roster (and out
    * of `find_capability` results) for chains where
    * [[sigil.Sigil.accessibleSpaces]] returns empty. */
  final def requiresAccessibleSpaces: Boolean = spec.profile.gates.requiresAccessibleSpaces

  /** When `true`, the framework refuses to dispatch this tool until
    * a [[sigil.event.ToolApproval]] record exists for `(toolName,
    * conversationId)` in `db.events`. The agent records consent via
    * [[sigil.tool.core.RecordConsentTool]] after observing the
    * user's reply. Derived from the profile's [[ConsentSpec]] — a
    * gated tool always carries its consent question.
    *
    * First-call-per-conversation semantics: a single approved
    * record covers subsequent calls in the same conversation.
    * `approved = false` is sticky — refusal sticks until the agent
    * records a fresh approval. */
  final def requiresUserConsent: Boolean = spec.profile.gates.consent.isDefined

  /** The consent question the agent asks before the first gated call,
    * when a consent gate is declared. */
  final def consentPrompt: Option[String] = spec.profile.gates.consent.map(_.prompt)

  /** Optional toolchain identifier — when the conversation has the
    * named toolchain active (per [[sigil.Sigil.activeToolchains]]),
    * `find_capability`'s ranker boosts this tool's score by
    * [[sigil.Sigil.toolchainBoost]]. */
  final def toolchain: Option[String] = spec.discovery.toolchain

  /** When `true`, [[sigil.Sigil.findCapabilities]]'s ranker
    * subtracts [[sigil.Sigil.preferIfNoBetterPenalty]] from this
    * tool's score so it sits below domain-specific tools when both
    * match the query. Generic primitives (`grep`, `glob`, `bash`,
    * `read_file`, `execute_script`) opt in. */
  final def preferIfNoBetter: Boolean = spec.discovery.preferIfNoBetter

  /** True when calling this tool has no side effects beyond the local
    * conversation log — safe to call speculatively. Derived from
    * [[Effect.ReadOnly]]. */
  final def readOnly: Boolean = spec.profile.effect.isReadOnly

  /** The read [[Freshness]], when this tool is read-only. Drives the
    * orchestrator's turn-scoped read cache and the standard curator's
    * ephemeral-result elision (Volatile). */
  final def freshness: Option[Freshness] = spec.profile.effect.readFreshness

  /** True when this tool's execution may be DETACHED: if it is still
    * running when [[sigil.Sigil.toolDetachThresholdMs]] passes, the
    * executor settles the invoke with a tracking handle, lets the
    * turn finish, and keeps the work running as a background task.
    * Derived from [[Execution.Detachable]], whose declaration carries
    * the progress story that makes a detached run observable. */
  final def detachable: Boolean = spec.profile.execution.detachable

  /** For [[detachable]] tools: whether a detached task should survive
    * a user Stop on its conversation. `false` — Stop cancels the
    * conversation's detached tasks through the same cooperative
    * `ctx.checkpoint` seam the attached phase honors. */
  final def detachedKeepRunningOnStop: Boolean = spec.profile.execution.keepsRunningOnStop

  /** True when calling this tool affects user-visible state
    * irreversibly. Derived from [[Effect.Destructive]], whose
    * declaration carries the `consequence` warning
    * [[wireDescription]] renders — so the LLM reads terminality
    * first, and the warning cannot be forgotten. */
  final def destructive: Boolean = spec.profile.effect.isDestructive

  /** **Annotation.** True when this tool VERIFIES state rather than
    * changing it — compiles, test runs, diagnostics pulls. The
    * progress checkpoint reads this: repeated mutations of the same
    * target are legitimate iteration only when a verification-class
    * call separates the rounds (fix → compile → fix-again); the same
    * target re-edited across checkpoint windows with no verification
    * anywhere is churn, not progress. Default `false`. */
  def verification: Boolean = false

  /** The external target a mutating call touches, when the input
    * names one — derived from the effect's [[MutationTargeting]].
    * The progress checkpoint uses this to distinguish a bulk sweep
    * touching new targets every window (progress) from the same
    * target re-edited round after round (churn); the read cache uses
    * it for overlap invalidation. Total: a foreign input class reads
    * as unknown target, never an error. */
  private[sigil] final def mutationTargetOf(input: ToolInput): Option[MutationTarget] =
    spec.profile.effect.targeting.flatMap(_.targetOf(input))

  /** Tools whose names the framework should append to the calling
    * conversation's per-participant `suggestedTools` overlay when
    * this tool runs. The overlay decays after one turn — the
    * suggestion surfaces under the "Suggested tools" prompt section
    * on the next agent turn, then fades unless the agent reaches
    * for it. */
  final def suggestedNextTools: List[ToolName] = spec.discovery.suggestedNextTools

  /** Sigil #288 — names of top-level input fields whose string values
    * are eligible for externalization at wire-render time. When a
    * tool emits a `tool_use` whose value for one of these fields
    * exceeds [[sigil.Sigil.inlineToolUseContentThreshold]], the
    * framework replaces the value with a short placeholder in
    * subsequent turns' wire prompts. The durable event log keeps the
    * full input; the agent recovers via `search_conversation` if
    * needed.
    *
    * Default empty — tools opt in per-field. The typical pattern:
    * `write_theme_file` declares `Set("content")` so a 28 KB
    * Liquid blob doesn't re-ship on every iteration after the write
    * lands. `respond.content` deliberately stays inline — the prose
    * IS the conversation history. */
  def externalizableInputFields: Set[String] = Set.empty

  /** The description the LLM sees on the wire, given runtime context.
    * Default returns [[descriptionFor]] with the destructive warning
    * prefixed when the effect is [[Effect.Destructive]] — the
    * declared `consequence` renders as a bold headline
    * (`**<consequence>** …`) so the LLM reads terminality first
    * regardless of the tool author's description body. Apps
    * overriding [[descriptionFor]] still get the prefix for free;
    * apps overriding [[wireDescription]] take full control (rare). */
  def wireDescription(mode: Mode, sigil: Sigil): String = {
    val body = descriptionFor(mode, sigil)
    spec.profile.effect match {
      case Effect.Destructive(_, consequence) => s"**$consequence** $body"
      case _                                  => body
    }
  }

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
  final lazy val schema: ToolSchema = ToolSchema(
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
   * tool whose keywords match the query exactly.
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
    * ranker honors intent surface over description prose. */
  val KeywordSearchBoost: Int = 5
}
