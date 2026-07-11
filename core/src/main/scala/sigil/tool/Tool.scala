package sigil.tool

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.{GlobalSpace, Sigil, SpaceId, TurnContext}
import sigil.event.{Event, MessageRole, ToolOutcome}
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.signal.{EventState, Signal, ToolDelta}

/**
 * A capability available to agents at runtime. Persisted in
 * [[sigil.db.SigilDB.tools]] so static (app-defined) tools and dynamic
 * (user-created) tools share one collection, one query path, and one
 * polymorphic RW.
 *
 * **Authoring contract.** A tool declares a typed [[Input]] and a typed
 * [[Output]] (abstract type members) and implements `executeOutput` (or,
 * for explicit success-vs-logical-failure control, `executeResult`).
 * `execute` — the `Stream[Event]` surface the orchestrator drives — is
 * `final`: the framework runs the tool's resolution and builds exactly
 * one paired result event from it. A tool cannot emit a free-form,
 * possibly-result-less event stream; the call/result pairing invariant
 * holds by construction.
 *
 * Durable events that are not the tool's result (a `change_mode`'s
 * `ModeChange`, a `respond`'s user-visible Message) are emitted via
 * `ctx.emit` during execution — see [[TurnContext]].
 *
 * `Tool` itself is monomorphic — the typing is carried by the `Input` /
 * `Output` type members, not type parameters — so `RecordDocument[Tool]`,
 * the polymorphic `RW[Tool]`, and the DB collection are all unparameterised.
 */
trait Tool extends RecordDocument[Tool] {
  // ---- abstract ----

  def name: ToolName
  def description: String

  /**
   * The typed argument shape this tool consumes.
   */
  type Input <: ToolInput

  /**
   * The typed result payload this tool produces.
   */
  type Output <: ToolOutput

  def inputRW: RW[Input]
  def outputRW: RW[Output]

  /**
   * Simple authoring entry — return the typed [[Output]]. A thrown
   * error (`Task.error`) is caught by the framework and surfaced as a
   * recoverable [[ToolResult.Failure]]. Override this OR [[executeResult]].
   */
  def executeOutput(input: Input, context: ToolContext): Task[Output] =
    Task.error(new NotImplementedError(
      s"Tool '${name.value}' must override `executeOutput` or `executeResult`."
    ))

  /**
   * Explicit authoring entry — full control over success vs. logical
   * failure (file not found, validator rejection, missing precondition).
   * Defaults to wrapping [[executeOutput]] in [[ToolResult.Success]].
   *
   * The returned `Task` is **total**: it resolves to a [[ToolResult]],
   * or it errors (a crash) and the framework maps that to an
   * unrecoverable failure. Either way the framework constructs exactly
   * one paired result event — "tool emitted no result" is unrepresentable.
   */
  def executeResult(input: Input, context: ToolContext): Task[ToolResult[Output]] =
    executeOutput(input, context).map(ToolResult.success)

  /**
   * When `true`, the framework never truncates or files this tool's
   * output on overflow — it is emitted inline verbatim. The tool
   * guarantees it has sized its own output (e.g. `find_capability`
   * trims its roster to the model's context window; its result must
   * arrive intact, never chopped mid-entry or spilled to a file the
   * agent then has to read back). Default `false` — ordinary tools
   * get the file-backed overflow path.
   */
  def boundsOutputItself: Boolean = false

  // ---- framework glue (final) ----

  /**
   * The `Stream[Signal]` surface the orchestrator drives. **Final** —
   * tools author via [[executeResult]] / [[executeOutput]] instead.
   *
   * Runs the tool's resolution (mapping any crash to a failure), then
   * emits the tool's ancillary events (those the body recorded via
   * `ctx.emit`) followed by exactly one [[ToolDelta]] that settles
   * the originating `ToolInvoke` — folding the typed output, outcome
   * (Success or Failure), and final state in one update. Ancillary-
   * first ordering keeps the durable log causally consistent (a
   * `change_mode`'s `ModeChange` precedes the settling delta; a
   * `respond`'s reply `Message` precedes its).
   *
   * Sigil #265 — pre-fix the result was a separate `ToolResults`
   * event linked back to the invoke by `origin`, which had to be
   * paired by every downstream consumer (#259/#260/#261/#263 were
   * all "the pair drifted" symptoms). The settling delta unifies the
   * tool transaction into a single stateful invoke.
   */
  final def execute(input: ToolInput,
                    turn: TurnContext,
                    invokeId: Id[Event],
                    invokedName: ToolName = name,
                    currentMessageId: Option[Id[Event]] = None): Stream[Signal] = {
    val ctx = ToolContext(turn, invokeId, invokedName, currentMessageId)
    Stream.force(
      runResolution(input, ctx).flatMap { res =>
        buildResultDelta(res, ctx).map { delta =>
          Stream.emits[Signal](ctx.emittedEvents :+ delta)
        }
      }
    )
  }

  /**
   * Run [[executeResult]] against a defensively-cast input, mapping any
   * throwable (including a `ClassCastException` from a mismatched input)
   * to a recoverable [[ToolResult.Failure]]. Total — never errors.
   */
  private def runResolution(input: ToolInput, context: ToolContext): Task[ToolResult[Output]] =
    Task(input.asInstanceOf[Input])
      .flatMap(typed => executeResult(typed, context))
      .handleError { err =>
        Task.pure(ToolResult.failure(
          message = Option(err.getMessage).getOrElse(err.getClass.getSimpleName),
          args = renderInputArgs(input)
        ))
      }

  /**
   * Build the settling [[ToolDelta]] from a resolution — folds output,
   * outcome, and `state = Complete` onto the originating `ToolInvoke`
   * in one update. Sigil #265.
   */
  private def buildResultDelta(result: ToolResult[Output], context: ToolContext): Task[ToolDelta] = {
    val invokeId = context.invokeId
    result match {
      case ToolResult.Success(value) =>
        // Measure + externalize the UNWRAPPED text for a TextToolOutput (#305):
        // the inner text IS the result, so the overflow file holds clean content
        // (e.g. newline-separated paths) a later grep/read_file consumes, not a
        // one-line `{"text":"…"}` envelope that re-overflows and reads badly
        // (#370). Structured outputs externalize their compact JSON as before.
        val rendered = value match {
          case t: TextToolOutput => t.text
          // Sigil #404 — a structured output that opts into a clean-text
          // render (`modelText`) measures + overflows on THAT text, so the
          // overflow file holds the same verbatim content the model would read
          // inline (edit anchors stay faithful).
          case o => o.modelText.getOrElse(JsonFormatter.Compact(outputRW.read(o)))
        }
        val threshold = context.sigil.inlineContentThreshold
        // On overflow, the full result is written to a file and the bounded
        // head (with the recovery path) becomes BOTH the summary AND the
        // invoke's `output`. Leaving the full `value` on `output` defeated the
        // overflow: `FrameBuilder` renders `output.text` in full, so the whole
        // result still bloated the prompt (a 106KB grep settled inline despite
        // the file write). `boundsOutputItself` tools deliver verbatim.
        val resolved: Task[(Option[String], ToolOutput)] =
          if (boundsOutputItself || !context.overflowLargeResults || rendered.length.toLong <= threshold)
            Task.pure((None, value))
          else buildOverflowSummary(value, rendered, threshold, context).map(s => (Some(s), TextToolOutput(s)))
        resolved.map { case (summaryOpt, outputValue) =>
          ToolDelta(
            target = invokeId,
            conversationId = context.conversation.id,
            state = Some(EventState.Complete),
            summary = summaryOpt,
            output = Some(outputValue),
            outcome = Some(ToolOutcome.Success)
          )
        }
      case ToolResult.Failure(message, hint, args) =>
        val body =
          (List(message) ++ hint.toList.map(h => s"\n\nHint: $h") ++
            args.toList.map(a => s"\n\nFailing args: $a")).mkString
        Task.pure(ToolDelta(
          target = invokeId,
          conversationId = context.conversation.id,
          state = Some(EventState.Complete),
          summary = Some(body),
          // No real `output` — outcome carries the failure. The
          // invoke's `output` field stays `ToolOutput.Pending`.
          outcome = Some(ToolOutcome.Failure(body, recoverable = true))
        ))
    }
  }

  /**
   * A success result that overflows [[Sigil.inlineContentThreshold]] is
   * written to a file under the conversation's [[FileSystemContext]]
   * (`.sigil/output/<convId>/<tool>-<callId>.txt`); the returned summary
   * is a bounded head + the path + stats, so the agent recovers the rest
   * with the filesystem tools it already has (`grep` / `read_file`) rather
   * than a bespoke reference handle. Because the write goes through the
   * same context those tools use, the file lands where they run (local or
   * ProxyTool-remote). Falls back to inline truncate-and-tell when no
   * workspace is bound or the write fails. Sigil #345/#346.
   */
  private def buildOverflowSummary(value: Output, rendered: String, threshold: Long, context: ToolContext): Task[String] = {
    val head = summarize(value, rendered)
    val lines = rendered.count(_ == '\n') + 1
    val truncateAndTell =
      head + "\n\n" +
        s"[${name.value}: result is ${rendered.length} bytes / $lines lines (over the $threshold-byte inline limit), " +
        "truncated. Narrow your inputs to see the rest.]"
    context.sigil.fileSystemContextFor(context.conversation.id).flatMap {
      case Some(fs) =>
        val relPath = s".sigil/output/${context.conversation.id.value}/${name.value}-${context.invokeId.value}.txt"
        fs.writeFile(relPath, rendered).map { bytes =>
          head + "\n\n" +
            s"[${name.value}: full result is $lines lines / $bytes bytes — written to $relPath. " +
            "Use grep or read_file on that path to see the rest.]"
        }.handleError(_ => Task.pure(truncateAndTell))
      case None => Task.pure(truncateAndTell)
    }
  }

  /**
   * Inline summary text rendered when the typed payload exceeds
   * `inlineContentThreshold`. Default: truncate the JSON at 200 chars.
   * Tools with richer summary semantics override.
   */
  protected def summarize(output: Output, jsonRendered: String): String = {
    // Prefer the unwrapped text for text outputs (#305) — the inner text IS
    // the result; previewing the `{"text":"…"}` JSON envelope wraps the bounded
    // head, making it inconsistent with non-overflow results and wasting the
    // envelope on the prompt.
    val source = output match {
      case t: TextToolOutput => t.text
      case o => o.modelText.getOrElse(jsonRendered) // #404 — clean-text opt-in
    }
    if (source.length <= 200) source else source.take(200) + " …"
  }

  /**
   * Render the failing input to compact JSON for a [[ToolResult.Failure]]'s
   * `args`. Best-effort — never a hard failure of the error path.
   */
  private def renderInputArgs(input: ToolInput): Option[String] =
    try Some(JsonFormatter.Compact(inputRW.read(input.asInstanceOf[Input])))
    catch { case _: Throwable => None }

  /**
   * Public composition entry. Another tool's `executeResult` body calls
   * this to invoke a tool and receive its typed [[Output]] directly —
   * no JSON parsing. A [[ToolResult.Failure]] raises a
   * [[ToolFailureException]] so the caller can `handleError` or let it
   * propagate.
   */
  def invoke(input: Input, context: ToolContext): Task[Output] =
    executeResult(input, context).flatMap {
      case ToolResult.Success(value) => Task.pure(value)
      case ToolResult.Failure(msg, hint, args) => Task.error(new ToolFailureException(name, msg, hint, args))
    }

  // ---- defaults ----

  /**
   * Categorical discriminator for client-side filtering — see
   * [[ToolKind]]. Defaults to [[BuiltinKind]]; subtypes from opt-in
   * modules override (e.g. `ScriptTool.kind = ScriptKind`,
   * `McpTool.kind = McpKind`). Apps building "manage your tools"
   * UIs use [[sigil.signal.RequestToolList]] with a `kinds` filter
   * to scope which records the user sees.
   */
  def kind: ToolKind = BuiltinKind

  /**
   * The set of [[Mode]] discriminators this tool is discoverable in.
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
   * the empty-as-universal contract.
   */
  def modes: Set[Id[Mode]] = Set.empty

  /**
   * The single [[SpaceId]] this tool is visible under. Defaults to
   * [[GlobalSpace]] — visible to every caller. Tools scoped to a
   * tenant / user / project override with their own space. There is
   * no multi-space tool: copy the record to surface the same
   * capability under a different space.
   */
  def space: SpaceId = GlobalSpace
  def keywords: Set[String] = Set.empty
  def examples: List[ToolExample] = Nil
  def createdBy: Option[ParticipantId] = None
  def _id: Id[Tool] = Id(name.value)
  def created: Timestamp = Tool.Epoch
  def modified: Timestamp = Tool.Epoch

  /**
   * The schema's input definition. Defaults to `inputRW.definition`;
   * tools that need a dynamic schema (e.g. an enum populated from
   * runtime config) override this.
   */
  def inputDefinition: fabric.define.Definition = inputRW.definition

  /**
   * The schema's output definition — the declared typed [[Output]]
   * shape. Surfaced in `find_capability` results so agents (and UIs)
   * can reason about the result shape before calling.
   */
  def outputDefinition: Option[fabric.define.Definition] = Some(outputRW.definition)

  /**
   * Pre-execution gates the orchestrator runs before
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
   * names via `suggestedFix` so the agent has an explicit next call.
   */
  def preconditions: List[ToolPrecondition] = Nil

  /**
   * Whether this tool requires the caller's chain to have at least
   * one accessible memory [[sigil.SpaceId]] to be useful. When `true`,
   * the framework filters the tool out of the agent's roster (and out
   * of `find_capability` results) for chains where
   * [[sigil.Sigil.accessibleSpaces]] returns empty — the tool would
   * have no place to write to / read from anyway, and surfacing it
   * would just waste tokens.
   *
   * Memory-related tools set this true (`save_memory`,
   * `unpin_memory`, `list_memories(pinned=true)`, etc.). Tools whose
   * usefulness doesn't depend on space wiring leave this false.
   */
  def requiresAccessibleSpaces: Boolean = false

  /**
   * How long this tool's settled result frames should remain in the
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
   * `Some(0)` by default.
   */
  def resultTtl: Option[Int] = None

  /**
   * When `true`, the framework refuses to dispatch this tool until
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
   * API calls). Default `false` preserves the no-gate fast path.
   */
  def requiresUserConsent: Boolean = false

  /**
   * Optional toolchain identifier — when the conversation has the
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
   * is running for the conversation's workspace.
   */
  def toolchain: Option[String] = None

  /**
   * When `true`, [[sigil.Sigil.findCapabilities]]'s ranker
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
   * etc. — those don't need the penalty.
   */
  def preferIfNoBetter: Boolean = false

  /**
   * **MCP-style annotation.** True when calling this tool has no
   * side effects beyond the local conversation log — safe to call
   * speculatively. `grep`, `glob`, `read_file`, `lsp_diagnostics`
   * are read-only; `respond`, `bash`, `edit_file` are not.
   *
   * Surfaced to the agent in [[wireDescription]] and to UI clients
   * via the tool record. Apps that want to filter risky tools
   * during exploratory iterations read this flag. Default `false`
   * — annotation is opt-in per tool.
   */
  def readOnly: Boolean = false

  /**
   * **MCP-style annotation.** True when calling this tool affects
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
   * Default `false`.
   */
  def destructive: Boolean = false

  /**
   * **MCP-style annotation.** True when calling this tool twice
   * with identical args produces the same result. `read_file` on
   * an unchanging file is idempotent; `bash` (non-pure commands)
   * is not. Mainly informational; UI clients use it to surface
   * "safe to retry" hints. Default `false`.
   */
  def idempotent: Boolean = false

  /**
   * **MCP-style annotation.** True when this tool interacts with
   * state outside Sigil's control — filesystem, network, LSP
   * server, external API. `read_file` is open-world (filesystem
   * can change); `consult` is open-world (network call); `respond`
   * is not (purely intra-conversation). Default `false`.
   */
  def openWorld: Boolean = false

  /**
   * Tools whose names the framework should append to the calling
   * conversation's per-participant `suggestedTools` overlay when
   * this tool runs. The overlay decays after one turn (the standard
   * `suggestedTools` lifecycle) — the suggestion surfaces under the
   * "Suggested tools" prompt section on the next agent turn, then
   * fades unless the agent reaches for it.
   *
   * The mechanism complements `find_capability`: a `grep` call
   * doesn't merely discover matches, it suggests that
   * `dispatch_workers` is the natural next move for "do something
   * with each match"; an `lsp_find_references` call suggests the
   * same after a usage search. The agent reads the suggestion in
   * the system prompt and can either pick it up or ignore it.
   *
   * Default empty — most tools don't lead naturally to a specific
   * follow-up. Generic primitives (`grep`, `glob`, `bash`) that
   * frequently lead into a per-result loop opt in.
   */
  def suggestedNextTools: List[ToolName] = Nil

  /**
   * Sigil #288 — names of top-level input fields whose string values
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
   * IS the conversation history.
   */
  def externalizableInputFields: Set[String] = Set.empty

  /**
   * The description the LLM sees on the wire, given runtime context.
   * Default returns [[descriptionFor]] with a destructive prefix
   * baked in when [[destructive]] is `true` — so the LLM reads
   * terminality first regardless of the tool author's description
   * body. Apps overriding [[descriptionFor]] still get the prefix
   * for free; apps overriding [[wireDescription]] take full control
   * (rare).
   */
  def wireDescription(mode: Mode, sigil: Sigil): String = {
    val body = descriptionFor(mode, sigil)
    if (destructive) destructivePrefix + body
    else body
  }

  /**
   * Prefix prepended to destructive tools' descriptions on the wire.
   * Override per tool family when a more specific framing fits
   * (e.g. `respond_*` could say `**ENDS YOUR TURN.**`); the default
   * generic prefix is `**DESTRUCTIVE.**` and signals irreversibility.
   */
  protected def destructivePrefix: String = "**DESTRUCTIVE.** "

  /**
   * The description the LLM sees, given runtime context (active
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
   * a bag-of-tools view still see something useful.
   */
  def descriptionFor(mode: Mode, sigil: Sigil): String = description

  /**
   * Render-ready schema — providers turn this into the LLM's tool list.
   */
  lazy val schema: ToolSchema = ToolSchema(
    id = Id[ToolSchema](_id.value),
    name = name,
    description = description,
    input = inputDefinition,
    examples = examples,
    output = outputDefinition
  )

  /**
   * Consolidated wire-surface derivation — single source for the schema
   * the LLM sees, the example payload refusals show, the pre-decode
   * coercion normalisation pass, and the end-to-end decode.
   *
   * Default builds the surface from [[inputDefinition]], [[inputRW]],
   * and [[examples]]; tools with custom derivation needs (an inputDefinition
   * that's dynamic per call, for instance) override to plug in a
   * specialised one.
   */
  lazy val wireSurface: WireSurface[Input] = WireSurface.fromTool(this)
}

object Tool extends PolyType[Tool]()(using scala.reflect.ClassTag(classOf[Tool])) with RecordDocumentModel[Tool] with JsonConversion[Tool] {

  /**
   * Sentinel epoch for static tool timestamps. Dynamic tools set their own.
   */
  val Epoch: Timestamp = Timestamp(0L)

  // Expose PolyType's RW as the rw RecordDocumentModel needs.
  implicit override val rw: RW[Tool] = polyRW

  val toolName: I[String] = field.index(_.name.value)
  val modeIds: I[Set[String]] = field.index(_.modes.map(_.value))
  val spaceId: I[String] = field.index(_.space.value)
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
    field.tokenized(
      "searchText",
      (t: Tool) => {
        val keywordBlock =
          if (t.keywords.isEmpty) ""
          else Iterator.fill(KeywordSearchBoost)(t.keywords.mkString(" ")).mkString(" ")
        s"${t.name.value} ${t.description} $keywordBlock"
      }
    )

  /**
   * Multiplier applied to a tool's `keywords` block within the
   * indexed [[searchText]]. Repeating the curated tokens N times
   * raises BM25's term-frequency contribution from `keywords` so the
   * ranker honors intent surface over description prose. 5× is the
   * default — high enough to flip cases like sigil bug #158, low
   * enough that a tool with no keywords still surfaces from a
   * description match.
   */
  val KeywordSearchBoost: Int = 5
}
