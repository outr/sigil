package sigil.provider

import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, TurnInput}
import sigil.db.Model
import sigil.diagnostics.RequestProfiler
import sigil.participant.ParticipantId
import sigil.signal.WireRequestProfile
import sigil.tokenize.{HeuristicTokenizer, Tokenizer}
import sigil.tool.Tool
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.core.CoreTools.atomicContentToolNames
import sigil.render.MarkdownRenderer
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Pluggable LLM backend. Each provider serializes a uniform
 * [[ProviderCall]] to its own wire format (OpenAI chat-completions,
 * Anthropic messages, llama.cpp's OpenAI-compatible surface, etc.) and
 * parses the streaming response into [[ProviderEvent]]s.
 *
 * The framework's `apply` is `final` and handles all variant dispatch
 * + translation. Implementations only see `ProviderCall` and only
 * implement `call` (and `httpRequestFor` for the inspect-without-send
 * path used by tests).
 *
 * Shared translation logic — DB resolution of memory/summary ids,
 * system-prompt assembly, frame-to-message rendering — lives in this
 * trait, not in each provider. Adding a new provider means writing one
 * `call` implementation, not duplicating ~500 lines of conversation-
 * aware machinery.
 */
trait Provider {
  def `type`: ProviderType

  /** This provider's namespace key — matches the prefix on
    * `Model.canonicalSlug` and `Model._id`. Default derives from the
    * `type` enum value's lowercased name (`OpenAI` → `"openai"`).
    * Override only when a provider's models live under a different
    * namespace. */
  def providerKey: String = `type`.toString.toLowerCase

  /** DB / configuration access for the shared translation pass. Wired
    * by each provider implementation (typically as a constructor arg). */
  protected def sigil: Sigil

  /**
   * Models available in this provider's namespace, read synchronously
   * from [[sigil.cache.ModelRegistry]]. The registry is populated by
   * [[sigil.controller.OpenRouter.refreshModels]] (run automatically
   * on the background refresh interval, or manually by the app) —
   * long-running apps see fresh metadata as it lands without
   * reconstructing the provider, and the read is a single
   * `AtomicReference` deref so this is safe to call on every request.
   *
   * Local providers like [[sigil.provider.llamacpp.LlamaCppProvider]]
   * override with their own list (loaded from the running server,
   * not openrouter).
   */
  def models: List[Model] = sigil.cache.find(provider = Some(providerKey))

  /** Tokenizer used by the framework's budget-validation pass to
    * estimate request size before sending. Default is the
    * char-count [[sigil.tokenize.HeuristicTokenizer]]; concrete
    * providers override to wire their model's actual tokenizer
    * (e.g. `OpenAIProvider` returns
    * [[sigil.tokenize.JtokkitTokenizer.OpenAIChatGpt]]). */
  def tokenizer: Tokenizer = HeuristicTokenizer

  /** Proactive [[RateLimiter]] consulted before each outgoing request.
   * The framework's `apply` awaits [[RateLimiter.acquire]] before
   * dispatching to [[call]]. Apps wire concrete observers separately:
   * spice's `streamLines()` doesn't surface response headers, so the
   * framework can't auto-feed [[RateLimiter.observe]] from the
   * provider's response. Apps that want proactive pacing typically:
   *
   *   - Override `rateLimiter` to return [[RateLimiter.forKey(apiKey)]]
   *     (per-key shared instance).
   *   - Front the provider with their own HTTP layer that tees rate-
   *     limit headers into `observe()`.
   *
   * The default [[RateLimiter.NoOp]] is zero-cost. Distinct from
   * [[ProviderStrategy]]'s reactive cooldown — the strategy decides
   * what to do AFTER a failure; the rate limiter tries to stop the
   * failure from happening, IF the app feeds it data. */
  def rateLimiter: RateLimiter = RateLimiter.NoOp

  /** Maximum concurrent in-flight pre-flight passes this provider
    * dispatches. The backend's slot count for local providers
    * (llama.cpp's `total_slots`), `Int.MaxValue` (the default) for
    * cloud providers whose binding constraint is rate-limit (RPM /
    * TPM) rather than slot count.
    *
    * Bug #49 — the framework gates [[apply]]'s pre-flight pass
    * (which includes provider-specific HTTP work like
    * `/apply-template`, `/tokenize`) through this cap so agents
    * sharing a backend serialize advisory work instead of
    * multiplying retry-stall latency. The streaming
    * chat-completions phase itself runs ungated — it's a different
    * shape (the cap isn't sized for long-running streams; the
    * backend serializes its own slots).
    *
    * Live agent turns inherit pre-flight priority by virtue of
    * acquiring the gate — advisory off-band tools (e.g. an
    * arbitrary `/tokenize` call from a tool author) that want
    * gating wrap themselves with [[withCapacity]] explicitly. */
  def maxConcurrent: Int = Int.MaxValue

  /** Per-provider fair semaphore enforcing [[maxConcurrent]]. Lazy
    * so subclass `maxConcurrent` overrides take effect. Bug #49. */
  final lazy val capacityGate: java.util.concurrent.Semaphore =
    new java.util.concurrent.Semaphore(maxConcurrent, /* fair */ true)

  /** Wall-clock cap on `capacityGate.acquire()`. Bug #57 — the
    * original `acquire()` blocks the calling fiber's thread
    * indefinitely if a previous holder leaked the permit (task
    * never settled, fiber interrupted abnormally, etc.). For an
    * agent-loop hot path that produces zero HTTP traffic and zero
    * CPU when this happens, the symptom is "agent parked on
    * `thinking` forever." Bounding the wait surfaces the leak as
    * a [[CapacityAcquireTimeoutException]] the agent loop's error
    * handler can catch — fail loud rather than silent hang. */
  protected def capacityAcquireTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(60, "seconds")

  /** Run a [[Task]] with a capacity-gate permit acquired from this
    * provider. The permit releases on completion (success or
    * failure) via `guarantee`. Used by the framework to gate
    * [[apply]]'s pre-flight pass; available to providers that want
    * to gate their own advisory paths (e.g. wrapping a separate
    * `/tokenize` call from outside `apply`'s flow). Bug #49.
    *
    * Bug #57 — bounded `tryAcquire(timeout)` instead of unbounded
    * `acquire()` so a permit leak in another fiber surfaces as a
    * fail-fast `CapacityAcquireTimeoutException` rather than an
    * indefinite thread park. The 60s default is generous enough
    * that legit slow translates (large prompts, slow tokenizer
    * backend) don't false-trigger; tighten via override only if a
    * specific deployment knows its translates always finish faster. */
  protected def withCapacity[A](task: Task[A]): Task[A] =
    Task.defer {
      val timeoutMs = capacityAcquireTimeout.toMillis
      val available = capacityGate.availablePermits()
      // Bug #57 — log only when contended (no permit available
      // immediately) so the common uncontended path stays quiet,
      // but a parking acquire surfaces in logs for diagnosis.
      if (available <= 0) {
        scribe.info(s"Provider($providerKey) capacity gate contended (max=$maxConcurrent), waiting up to ${timeoutMs}ms")
      }
      val acquired = capacityGate.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      if (!acquired) {
        scribe.warn(s"Provider($providerKey) capacity gate timed out after ${timeoutMs}ms (max=$maxConcurrent) — possible permit leak")
        Task.error(new CapacityAcquireTimeoutException(maxConcurrent, capacityAcquireTimeout))
      } else {
        task.guarantee(Task(capacityGate.release()))
      }
    }

  // ---- public entry points (final) ----

  /**
   * Send a request and receive a stream of provider events. Final —
   * implementations must not override. Internally translates the request
   * into a uniform [[ProviderCall]] and dispatches to [[call]].
   *
   * The stream terminates with a `Done` event (or `Error`).
   */
  final def apply(request: ProviderRequest): Stream[ProviderEvent] = {
    // Bug #49 — gate the synchronous pre-flight pass via the
    // capacity semaphore. `translate` includes provider-specific
    // pre-flight HTTP work (`/apply-template`, `/tokenize`) that
    // accumulates real latency on local backends; serializing it
    // through the gate prevents the multi-second retry-stall
    // pattern when concurrent agent turns each kick off their own
    // advisory calls. The live chat-completions stream itself
    // runs ungated — it's the long-running phase the gate isn't
    // sized for, and the backend serializes its own slot use.
    //
    // Bug #50 — wrap the pre-flight pass in a framework-workflow
    // Notice pulse so client UIs can render an activity indicator
    // and apps can observe queued vs in-flight time. The
    // chat-completions stream is intentionally outside the wrapper
    // for the same reason it's outside the capacity gate: it's
    // covered by per-message Delta + final Done events that
    // already drive client rendering.
    val convId = request match {
      case c: ConversationRequest => Some(c.conversationId)
      case _                      => None
    }
    Stream.force(
      sigil.runAsFrameworkWorkflow(
        workflowType = "preflight",
        label = s"Rendering pre-flight for ${request.modelId.value}",
        conversationId = convId
      ) { control =>
        rateLimiter.acquire.flatMap { _ =>
          control.token.checkpoint.flatMap(_ => withCapacity(translate(request))).flatMap { providerCall =>
            control.step("Validating request size").map(_ => providerCall)
          }
        }
      }.map { providerCall =>
        preFlightGate(request, providerCall) match {
          case Right(safe)  => call(safe)
          case Left(reason) => Stream.force(Task.error(reason))
        }
      }
    )
  }

  /** Pre-flight budget validation. Estimates the rendered request via
    * the provider's [[tokenizer]] and compares against the model's
    * `contextLength`. If over, applies emergency shedding (tool-
    * roster trim → last-resort frame drop) until the request fits OR
    * raises [[RequestOverBudgetException]] when nothing more can be
    * safely cut (critical memories are inviolable).
    *
    * Returns `Right(call)` when the request fits (possibly after
    * shedding), `Left(exception)` when it can't be made to fit. */
  private def preFlightGate(request: ProviderRequest, providerCall: ProviderCall): Either[Throwable, ProviderCall] = {
    val limit = sigil.cache.find(request.modelId).map(_.contextLength.toInt).getOrElse(Int.MaxValue)
    if (limit == Int.MaxValue) Right(providerCall) // no model record — can't validate; trust the curator
    else {
      val initial = estimateRequest(providerCall)
      if (initial <= limit) Right(providerCall)
      else {
        val shed = emergencyShed(providerCall, limit, tokenizer, estimateRequest)
        if (estimateRequest(shed) <= limit) Right(shed)
        else Left(new RequestOverBudgetException(estimateRequest(shed), limit, request.modelId))
      }
    }
  }

  /** Estimate the wire-rendered token count for `call`. Bug #46 —
    * exposed as a `protected` hook so providers whose wire is built
    * by composing a chat template (every chat-completions-style
    * provider) can override with an exact backend-rendered count
    * (e.g. `LlamaCppProvider` calls `/apply-template` + `/tokenize`).
    *
    * Default: piecewise sum of system + per-message + roster. Correct
    * within ~7-15% for chat-template providers; the gap is the
    * template glue between messages that piecewise summing misses.
    * Providers with large context windows tolerate the gap; tight
    * `n_ctx` configs don't, and override accordingly. */
  protected def estimateRequest(call: ProviderCall): Int = {
    val tok = tokenizer
    tok.count(call.system) +
      call.messages.iterator.map(estimateMessage(_, tok)).sum +
      estimateRoster(call.tools, tok)
  }

  /** Best-effort token count for a single [[ProviderMessage]] as it
    * lands on the wire — covers User text + Assistant tool-call args
    * + ToolResult content + Reasoning summaries + per-message
    * role/envelope overhead.
    *
    * Bug #44 — counts the JSON-RPC wrapper around each Assistant
    * tool call (`{"id": "...", "type": "function", "function":
    * {"name": "...", "arguments": "..."}}`) and the role/content
    * envelope on every ToolResult, plus the Reasoning body
    * (previously `=> 0`). Tool-using conversations accumulate
    * dozens of these wrappers per turn; under-counting accumulates
    * to 1-3K of unaccounted wire tokens.
    *
    * Per-message envelope is `+4` (was `+3`) — OpenAI's chat format
    * adds ~4 tokens for the role + content envelope. */
  protected def estimateMessage(m: ProviderMessage, tok: Tokenizer): Int = m match {
    case ProviderMessage.System(c)            => tok.count(c) + 4
    case ProviderMessage.User(blocks)         => blocks.iterator.map {
      case MessageContent.Text(t)     => tok.count(t)
      case MessageContent.Image(_, _) => 85 // standard low-detail image overhead per OpenAI's docs
    }.sum + 4
    case ProviderMessage.Assistant(c, calls)  =>
      // Each tool call ships as a JSON-RPC wrapper:
      //   {"id":"...","type":"function","function":{"name":"...","arguments":"..."}}
      // Wrapper keys + braces + quotes + commas approximate +18 tokens
      // per call across providers (OpenAI chat-completions, Anthropic
      // messages, llama.cpp openai-compat).
      val callsCost = calls.iterator.map { tc =>
        tok.count(tc.id) + tok.count(tc.name) + tok.count(tc.argsJson) + 18
      }.sum
      tok.count(c) + callsCost + 4
    case ProviderMessage.ToolResult(callId, c) =>
      // Tool result envelope: `{"role":"tool","tool_call_id":"...","content":"..."}`
      // — the call_id linkage is small but real; +8 covers wrapper keys.
      tok.count(callId) + tok.count(c) + 8
    case ProviderMessage.Reasoning(_, summary, encryptedContent) =>
      // Bug #44 — reasoning blocks are non-trivial when kept (Anthropic
      // extended thinking, OpenAI o-series, gemma's thinking mode).
      // Encrypted content is opaque but ships verbatim, so its size
      // counts even if its content doesn't decode.
      val summaryTokens = tok.count(summary.mkString("\n"))
      val cotTokens = encryptedContent.fold(0)(tok.count)
      summaryTokens + cotTokens + 4
  }

  /** Token cost of the wire tool roster — name + description + the
    * rendered JSON parameter schema body that actually ships on the
    * wire. Bug #43 — the prior implementation approximated schema
    * cost as a fixed +30 per tool, which severely undercounted any
    * non-trivial input schema (often hundreds-to-thousands of
    * tokens per tool for realistic agents). With the schema body
    * undercounted, the pre-flight gate let requests through that
    * subsequently overflowed at the provider.
    *
    * `DefinitionToSchema` produces the canonical JSON schema each
    * provider then post-processes (strict-mode rewrites,
    * provider-specific keyword stripping). Provider-side variations
    * are second-order in size; counting the canonical schema gives
    * an estimate within tokenization-noise of the actually-sent
    * payload. Providers whose wire shape diverges materially can
    * override [[estimateToolBytes]] for higher fidelity. */
  protected def estimateRoster(tools: Vector[Tool], tok: Tokenizer): Int =
    tools.iterator.map(estimateToolBytes(_, tok)).sum

  /** Per-tool wire-shape estimate. Default counts name + description +
    * the JSON-formatted parameter schema. Override for providers with
    * extra per-tool metadata (Anthropic's `cache_control`, OpenAI's
    * `strict` flag, etc.) — the framework's default already counts
    * the schema body which is the dominant cost.
    *
    * Bug #47 — concatenates the per-tool wire bytes into ONE
    * tokenizer call instead of three (name / description / schema).
    * For providers whose tokenizer makes an HTTP round-trip
    * (`LlamaCppTokenizer`), this drops the per-tool HTTP cost from
    * 3 to 1 — material when the agent has a dozen tools. */
  protected def estimateToolBytes(tool: Tool, tok: Tokenizer): Int = {
    val name        = tool.schema.name.value
    val description = tool.descriptionFor(ConversationMode, sigil)
    val schemaJson  = fabric.io.JsonFormatter.Compact(
      _root_.sigil.tool.DefinitionToSchema(tool.schema.input)
    )
    // Wrapper overhead: `{"type":"function","name":"...","description":"...","parameters":{...}}`
    // — keys + braces + colons. ~10 tokens depending on tokenizer.
    val wrapper     = 12
    tok.count(s"$name\n$description\n$schemaJson") + wrapper
  }

  /** Emergency-shed: trim tool roster (cap descriptions or drop
    * un-essential tools) and drop oldest frames until the request
    * fits. Stops when nothing more can be safely cut — caller raises
    * [[RequestOverBudgetException]] in that case. Does NOT call the
    * LLM (compression already happened in the curator); pure
    * truncation. */
  private def emergencyShed(initial: ProviderCall,
                            limit: Int,
                            tok: Tokenizer,
                            estimateOf: ProviderCall => Int): ProviderCall = {
    var current = initial

    // Stage 4 — drop tool roster down to framework essentials only.
    // Critical for cases where a large tool catalog is the bulk of
    // overhead; baseline tools (respond / find_capability / stop /
    // change_mode) are retained so the agent can still function.
    val essentials = Set("respond", "find_capability", "stop", "change_mode", "no_response",
      "respond_options", "respond_field", "respond_failure", "activate_skill")
    if (estimateOf(current) > limit && current.tools.size > essentials.size) {
      val trimmed = current.tools.filter(t => essentials.contains(t.schema.name.value))
      current = current.copy(tools = trimmed)
    }

    // Stage 5 — drop oldest messages until fits. Critical memories
    // live in `system`, not `messages`, so dropping messages never
    // touches them.
    //
    // Bug #59 — the original loop called `estimateOf` on every
    // single one-message drop. For providers whose `estimateOf`
    // makes an HTTP round-trip against the full residual message
    // list (`LlamaCppProvider` hitting `/apply-template` +
    // `/tokenize`), shedding K messages cost K HTTP round-trips
    // and O(K²) bandwidth. After a bulk import, K = thousands and
    // the loop melted the backend.
    //
    // Replaced with a prefix-scan over a local heuristic
    // tokenizer's per-message estimate, then ONE `estimateOf`
    // confirmation, with the original per-step loop as the
    // convergence step for the residual.
    if (estimateOf(current) > limit && current.messages.nonEmpty) {
      current = bulkDropMessages(current, limit, tok, estimateOf)
      // Convergence step — at most a handful of iterations after
      // the bulk drop's heuristic-based jump.
      while (estimateOf(current) > limit && current.messages.nonEmpty) {
        current = current.copy(messages = current.messages.tail)
      }
    }

    current
  }

  /** Bulk-drop oldest messages from the call using a local
    * heuristic to compute the drop count, sized so the post-drop
    * message bytes fit under `limit` minus the system-prompt
    * overhead. Local-only — no HTTP round-trips even when the
    * provider's `tokenizer` would. Returns the trimmed call;
    * caller follows up with one `estimateOf` confirmation. Bug #59. */
  private def bulkDropMessages(call: ProviderCall,
                               limit: Int,
                               tok: Tokenizer,
                               estimateOf: ProviderCall => Int): ProviderCall = {
    val msgs    = call.messages
    val perMsg: Vector[Int] = msgs.map(m => _root_.sigil.tokenize.HeuristicTokenizer.count(renderMessageForHeuristic(m)))
    val msgSum: Int = perMsg.sum
    // Approximate the system-prompt + tool-roster overhead the
    // provider's own estimateOf will add on top of the messages.
    // Take the difference between the wire estimate and the
    // heuristic message-bytes — what's left is overhead we can't
    // shed by trimming messages.
    val totalEst = estimateOf(call)
    val overhead = math.max(0, totalEst - msgSum)
    // Conservative 5% margin so the post-drop confirm doesn't
    // trip the per-step convergence loop just because the
    // heuristic underestimated by a few tokens.
    val margin     = (limit * 0.05).toInt
    val msgBudget  = math.max(0, limit - overhead - margin)
    val needToShed = math.max(0, msgSum - msgBudget)
    if (needToShed <= 0) call
    else {
      // Walk perMsg from the front, summing, until the prefix
      // sum reaches `needToShed`. That's the number of oldest
      // messages we can drop without crossing the budget.
      val cum = perMsg.scanLeft(0)(_ + _)
      val idx = cum.indices.find(i => cum(i) >= needToShed).getOrElse(perMsg.size)
      val k   = math.min(idx, msgs.size)
      call.copy(messages = msgs.drop(k))
    }
  }

  /** Best-effort textual rendering of a [[ProviderMessage]] for
    * the local heuristic tokenizer's per-message estimate. Bug #59
    * — exact wire-byte fidelity isn't needed here since the
    * caller follows up with `estimateOf` confirmation; this only
    * has to be a stable proxy for relative message size. */
  private def renderMessageForHeuristic(m: ProviderMessage): String = m match {
    case ProviderMessage.System(c)            => c
    case ProviderMessage.User(blocks)         => blocks.iterator.map {
      case t: MessageContent.Text  => t.text
      case _                       => ""
    }.mkString("\n")
    case ProviderMessage.Assistant(c, calls)  =>
      val callsText = calls.iterator.map(tc => s"${tc.name}:${tc.argsJson}").mkString("\n")
      s"$c\n$callsText"
    case ProviderMessage.ToolResult(_, c)     => c
    case ProviderMessage.Reasoning(_, summary, encryptedContent) =>
      summary.mkString("\n") + encryptedContent.getOrElse("")
  }

  /**
   * Build the underlying [[spice.http.HttpRequest]] for a sigil request without
   * performing any network I/O. `apply` invokes the same translation
   * pass before calling `call`; tests can call this directly to inspect
   * the wire payload (typically by reading `httpRequest.content` and
   * asserting on the JSON body).
   *
   * Final — providers implement [[httpRequestFor]] instead.
   */
  final def requestConverter(request: ProviderRequest): Task[HttpRequest] =
    translate(request)
      .flatMap(httpRequestFor)
      // Invoke the wire interceptor here too so inspect-only paths
      // (tests, debug dumps) still produce wire logs — same coverage
      // as the live streaming path in `call`.
      .flatMap(sigil.wireInterceptor.before)

  // ---- protected: providers implement these ----

  /**
   * The provider's wire-level streaming call. Receives a fully-resolved,
   * format-neutral [[ProviderCall]]. Implementation: serialize to the
   * provider's request format, POST, parse the streaming response into
   * [[ProviderEvent]]s.
   */
  def call(input: ProviderCall): Stream[ProviderEvent]

  /**
   * Append one frame's wire shape to an existing encoded-context
   * buffer (bug #26). The buffer is opaque to the framework — each
   * provider owns its own representation. Default implementation
   * uses a newline-delimited transcript readable across providers
   * so the framework can debug / measure cache size without
   * provider-specific decoders.
   *
   * Returns `(updatedBuffer, tokensAdded)`; `tokensAdded` is
   * estimated via this provider's [[tokenizer]] over the rendered
   * frame's textual content.
   */
  def appendFrame(buffer: String,
                  frame: ContextFrame,
                  agentId: Option[ParticipantId]): (String, Long) = {
    val rendered = ContextFrameDigest.render(frame)
    val sep = if (buffer.isEmpty) "" else "\n"
    val updated = buffer + sep + rendered
    val tokensAdded = tokenizer.count(rendered).toLong
    (updated, tokensAdded)
  }

  /**
   * Build the wire-level [[spice.http.HttpRequest]] from a [[ProviderCall]] without
   * sending it. Used by the final [[requestConverter]] for inspect-only
   * test paths.
   */
  def httpRequestFor(input: ProviderCall): Task[HttpRequest]

  // ---- shared translation, private to the framework ----

  private def translate(req: ProviderRequest): Task[ProviderCall] = req match {
    case c: ConversationRequest => translateConversation(c)
    case s: OneShotRequest      => Task.pure(translateOneShot(s))
  }

  private def translateConversation(c: ConversationRequest): Task[ProviderCall] =
    resolveReferences(c.turnInput).flatMap { resolved =>
      val agentId = c.chain.lastOption
      val toolChoice =
        if (c.tools.isEmpty) ToolChoice.None else ToolChoice.Required
      // Adaptive max_tokens — when the paraphrase detector has
      // flagged a planning-without-acting loop on this turn (signal
      // lives in `turnInput.extraContext`), cap the per-call
      // generation budget so a degenerate model can't run all the
      // way to its default `maxOutputTokens` producing kilobytes of
      // repeated text. Damage bounded; the agent's next iteration
      // reads the loop diagnostic and can self-correct.
      val gen =
        if (c.turnInput.extraContext.exists { case (k, _) =>
              k.value == _root_.sigil.conversation.compression.ParaphraseLoopDetector.ContextKeyValue
            }) {
          val cap: Int = Provider.ParaphraseLoopMaxOutputTokensCap
          val tightened: Option[Int] = c.generationSettings.maxOutputTokens match {
            case Some(existing) => Some(math.min(existing, cap))
            case None           => Some(cap)
          }
          c.generationSettings.copy(maxOutputTokens = tightened)
        } else c.generationSettings
      val providerCall = ProviderCall(
        modelId = c.modelId,
        system = renderSystem(c, resolved),
        messages = renderFrames(c.turnInput.frames, agentId),
        tools = c.tools,
        builtInTools = c.builtInTools,
        toolChoice = toolChoice,
        generationSettings = gen,
        currentMode = c.currentMode
      )
      // Diagnostic profiling — gated on `Sigil.profileWireRequests`
      // (default on; apps override to false to skip). Runs the
      // tokenizer once per turn over every section of the about-to-
      // be-sent request and broadcasts the breakdown as a
      // `WireRequestProfile` Notice. Cheap (jtokkit milliseconds
      // for typical request sizes) — supports the always-visible
      // context-utilisation gauge downstream apps render without
      // further opt-in.
      val emit: Task[Unit] =
        if (sigil.profileWireRequests) {
          agentId match {
            case Some(pid) =>
              val profile = RequestProfiler.profile(c, resolved, tokenizer, sigil)
              sigil.publish(WireRequestProfile(c.conversationId, c.modelId, pid, profile))
            case None => Task.unit
          }
        } else Task.unit
      emit.map(_ => providerCall)
    }

  private def translateOneShot(s: OneShotRequest): ProviderCall = {
    val toolChoice =
      if (s.tools.isEmpty) ToolChoice.None else ToolChoice.Required
    val userMessage =
      if (s.userContent.nonEmpty) ProviderMessage.User(toMessageContent(s.userContent))
      else ProviderMessage.User(s.userPrompt)
    ProviderCall(
      modelId = s.modelId,
      system = s.systemPrompt,
      messages = Vector(userMessage),
      tools = s.tools,
      builtInTools = s.builtInTools,
      toolChoice = toolChoice,
      generationSettings = s.generationSettings
    )
  }

  /** Project the public [[ResponseContent]] vocabulary onto the
    * narrower wire-level [[MessageContent]] used in
    * [[ProviderMessage.User]]. `Text` and `Image` map directly;
    * structured variants (Code, Diff, Table, Heading, …) render to
    * a `Text` block via `toString` so the model still sees the
    * content even on text-only providers. Image blocks survive into
    * the wire layer; per-provider serialization there decides
    * whether to send or drop based on the target API's multimodal
    * support. */
  private def toMessageContent(content: Vector[ResponseContent]): Vector[MessageContent] =
    content.map {
      case ResponseContent.Text(t)             => MessageContent.Text(t)
      case ResponseContent.Image(url, alt)     => MessageContent.Image(url, alt)
      case ResponseContent.Markdown(t)         => MessageContent.Text(t)
      case ResponseContent.Code(c, lang)       => MessageContent.Text(s"```${lang.getOrElse("")}\n$c\n```")
      case other                                => MessageContent.Text(MarkdownRenderer.renderBlock(other))
    }

  /** Resolve the ids on `TurnInput.criticalMemories` / `.memories` /
    * `.summaries` to full records via the DB. Ids that don't resolve are
    * dropped silently. */
  private def resolveReferences(turn: TurnInput): Task[ResolvedReferences] = {
    for {
      crit <- Task.sequence(turn.criticalMemories.toList.map(id =>
                sigil.withDB(_.memories.transaction(_.get(id)))))
      regular <- Task.sequence(turn.memories.toList.map(id =>
                   sigil.withDB(_.memories.transaction(_.get(id)))))
      summaries <- Task.sequence(turn.summaries.toList.map(id =>
                     sigil.withDB(_.summaries.transaction(_.get(id)))))
    } yield ResolvedReferences(
      criticalMemories = crit.flatten.toVector,
      memories = regular.flatten.toVector,
      summaries = summaries.flatten.toVector
    )
  }

  /** Compose the system prompt body from every contextually relevant
    * field on a [[ConversationRequest]]. Each section is omitted
    * when its source is empty. Every Model-visible field on `TurnInput`
    * MUST appear here. The companion
    * [[spec.LlamaCppRequestCoverageSpec]] is the regression guard. */
  private def renderSystem(c: ConversationRequest,
                           resolved: ResolvedReferences): String = {
    val turn = c.turnInput
    val chain = c.chain
    val sb = new StringBuilder

    if (c.tools.nonEmpty) {
      sb.append(
        "You communicate exclusively through tool calls. Plain text output is never delivered to the user — " +
          "always pick a tool.\n\n"
      )
    }

    sb.append(s"Current mode: ${c.currentMode} — ${c.currentMode.description}\n")
    // Tools that need runtime context (e.g. `change_mode` enumerating
    // the available modes) override `Tool.descriptionFor` to fold
    // that context into their own description. The framework
    // prompt-builder stays free of per-tool special cases.
    sb.append(s"Current topic: \"${c.currentTopic.label}\" — ${c.currentTopic.summary}\n")
    if (c.previousTopics.nonEmpty) {
      sb.append("Previous topics in this conversation:\n")
      c.previousTopics.foreach(t => sb.append(s"  - \"${t.label}\" — ${t.summary}\n"))
    }

    // Instructions' TOOLS discovery block tells the model to call
    // `find_capability` first for actions outside its tool roster. If
    // that tool isn't actually available (e.g. the active mode uses
    // `ToolPolicy.None` or `Exclusive`), pointing the model at it
    // creates a dead loop — strip the block in that case. When
    // `find_capability` IS available but `respond` ISN'T (PureDiscovery
    // active), swap to the pure-discovery variant so the prompt
    // doesn't describe `respond` as immediately callable.
    val findCapabilityAvailable =
      c.tools.exists(_.schema.name.value == "find_capability")
    val respondAvailable =
      c.tools.exists(_.schema.name.value == "respond")
    val instr =
      if (!findCapabilityAvailable) c.instructions.renderWithoutTools
      else if (!respondAvailable) c.instructions.forPureDiscovery.render
      else c.instructions.render
    if (instr.nonEmpty) sb.append("\n").append(instr).append("\n")

    if (resolved.criticalMemories.nonEmpty) {
      sb.append("\n== Pinned directives ==\n")
      resolved.criticalMemories.foreach(m => sb.append(s"- ${memoryRenderText(m)}\n"))
    }

    if (resolved.summaries.nonEmpty) {
      sb.append("\n== Earlier in this conversation ==\n")
      resolved.summaries.foreach(s => sb.append(s.text).append("\n"))
    }

    if (resolved.memories.nonEmpty) {
      sb.append("\n== Memories ==\n")
      resolved.memories.foreach(m => sb.append(s"- ${memoryRenderText(m)}\n"))
    }

    if (turn.information.nonEmpty) {
      sb.append("\n== Referenced content (look up by id) ==\n")
      turn.information.foreach(i =>
        sb.append(s"- ${i.id.value} [${i.informationType.name}]: ${i.summary}\n"))
    }

    // Roles render the agent's identity into the system prompt. A single
    // role is shown linearly (one description block); multiple roles get a
    // "You serve the following roles:" preamble + per-role enumeration so
    // the model handles multi-role identity explicitly even when each
    // role's description was written self-contained.
    c.roles match {
      case Nil           => ()
      case List(single)  =>
        if (single.description.nonEmpty)
          sb.append("\n").append(single.description).append("\n")
      case multi         =>
        sb.append("\nYou serve the following roles:\n")
        multi.foreach { r =>
          sb.append(s"- ${r.name}")
          if (r.description.nonEmpty) sb.append(s" — ${r.description}")
          sb.append("\n")
        }
    }

    val skills = turn.aggregatedSkills(chain)
    val roleSkills = c.roles.flatMap(_.skill.toList)
    val allSkills = (skills ++ roleSkills).distinctBy(_.name)
    if (allSkills.nonEmpty) {
      sb.append("\n== Active skills ==\n")
      allSkills.foreach { s =>
        sb.append(s"- ${s.name}\n")
        if (s.content.nonEmpty) sb.append(s.content).append("\n")
      }
    }

    val recentTools = chain.flatMap(id => turn.projectionFor(id).recentTools).distinct
    if (recentTools.nonEmpty) {
      sb.append("\n== Recently used tools ==\n")
      recentTools.foreach(t => sb.append(s"- $t\n"))
    }

    val suggestedTools = chain.flatMap(id => turn.projectionFor(id).suggestedTools).distinct
    if (suggestedTools.nonEmpty) {
      sb.append("\n== Suggested tools ==\n")
      suggestedTools.foreach(t => sb.append(s"- $t\n"))
    }

    if (turn.extraContext.nonEmpty) {
      sb.append("\n== Conversation context ==\n")
      turn.extraContext.foreach { case (k, v) => sb.append(s"- ${k.value}: $v\n") }
    }

    val perParticipantExtras =
      chain.flatMap(id => turn.projectionFor(id).extraContext.map(id -> _))
    if (perParticipantExtras.nonEmpty) {
      sb.append("\n== Participant context ==\n")
      perParticipantExtras.foreach { case (pid, (k, v)) =>
        sb.append(s"- ${pid.value} ${k.value}: $v\n")
      }
    }

    // Bug #63 — when this turn was fired by `greetsOnJoin`'s
    // greeting flow, append a clear instruction so the model
    // doesn't have to guess from the empty trigger stream
    // whether this is a moment to introduce itself or to stay
    // silent. Without this hint, the model picks `respond` vs
    // `no_response` stochastically, breaking the user contract
    // implied by `greetsOnJoin = true`. The hint is rendered
    // last so it sits within the model's recency-biased
    // attention.
    if (c.isGreeting) {
      sb.append("\n== Greeting turn ==\n")
      sb.append("This is a fresh conversation. Call `respond` with a brief introduction — ")
      sb.append("state your role and offer to help. Do NOT call `no_response` or `find_capability` ")
      sb.append("on this turn; the user expects a greeting, not silence or discovery.\n")
    }

    sb.toString
  }

  /** What to render for a memory in the system prompt's `Critical
    * directives` / `Memories` sections. Prefers `summary` when set so
    * apps that author tight directives keep per-turn cost down; the
    * full `fact` is always recoverable via the `lookup` tool. */
  private def memoryRenderText(m: ContextMemory): String =
    if (m.summary.trim.nonEmpty) m.summary else m.fact

  /** Render a conversation's [[ContextFrame]]s into format-neutral
    * [[ProviderMessage]]s. Mapping rules:
    *
    *   - `Text` from the agent itself        → `Assistant`
    *   - `Text` from anyone else             → `User`
    *   - `ToolCall` from the agent for any
    *     tool *other than* `respond`         → `Assistant` with `toolCalls`
    *     The `respond` tool's call is filtered because the following
    *     `Text` frame IS the response — emitting both would yield a
    *     tool_call without a matching tool_result.
    *   - `ToolCall` from someone else        → skipped
    *   - `ToolResult`                        → `ToolResult` paired by callId
    *   - `System`                            → `ToolResult` if a tool call
    *     is open; otherwise `System`
    *
    * Only model-visible events become frames in the first place (see
    * [[sigil.conversation.FrameBuilder]]), so UI-only history never
    * reaches this renderer.
    */
  protected[provider] def renderFrames(frames: Vector[ContextFrame],
                           agentId: Option[ParticipantId]): Vector[ProviderMessage] = {
    val out = Vector.newBuilder[ProviderMessage]
    var pendingToolCallId: Option[String] = None

    // Bug #69 — merge consecutive `ContextFrame.ToolResult` entries
    // sharing the same `callId` into a single frame whose content is
    // the concatenation in emission order. The wire stays 1:1
    // (`function_call` ↔ `function_call_output`) which is what every
    // provider expects; tool authors who emit multiple Tool-role
    // events for one call get them folded into one wire-level result.
    val merged = mergeAdjacentToolResults(frames)

    merged.foreach {
      case ContextFrame.Text(content, participantId, _, _) =>
        if (agentId.contains(participantId)) out += ProviderMessage.Assistant(content)
        else out += ProviderMessage.User(content)

      case ContextFrame.ToolCall(toolName, _, _, participantId, _, _)
        if toolName == RespondTool.schema.name && agentId.contains(participantId) =>
      // Skip — the following Text frame IS the response.

      case ContextFrame.ToolCall(toolName, argsJson, callId, participantId, _, _) if agentId.contains(participantId) =>
        out += ProviderMessage.Assistant(
          content = "",
          toolCalls = List(ToolCallMessage(
            id = callId.value,
            name = toolName.value,
            argsJson = argsJson
          ))
        )
        // Atomic content tools (`respond_options`, `respond_field`,
        // `respond_card`, etc. — see `CoreTools.atomicContentToolNames`)
        // emit a `Standard`-role `Message` instead of a `Tool`-role
        // `ToolResults`, so no `ContextFrame.ToolResult` is ever
        // produced for the call. OpenAI's Responses API strictly
        // requires every `function_call` to be followed by a
        // `function_call_output` carrying the same `call_id` —
        // unsatisfied calls cause a 400 on the next request. Pair
        // each atomic call with an empty synthetic output so the
        // wire shape is satisfied; chat-side surface is unaffected
        // (the rendered Message is the user-facing artifact). RespondTool
        // is special-cased above (its function_call is dropped entirely
        // since the next Text frame IS the response).
        if (atomicContentToolNames.contains(toolName)) {
          out += ProviderMessage.ToolResult(toolCallId = callId.value, content = "")
        } else {
          pendingToolCallId = Some(callId.value)
        }

      case _: ContextFrame.ToolCall =>
      // ToolCall from someone else — skip (not rendered as a tool call for this agent).

      case ContextFrame.ToolResult(callId, content, _, _) =>
        out += ProviderMessage.ToolResult(toolCallId = callId.value, content = content)
        if (pendingToolCallId.contains(callId.value)) pendingToolCallId = None

      case ContextFrame.System(content, _, _) =>
        out += ProviderMessage.System(content)

      case ContextFrame.Reasoning(providerItemId, summary, encryptedContent, _, _, _) =>
        // Provider-internal reasoning state from a prior turn (bug #61).
        // Surfaced uniformly as a `ProviderMessage.Reasoning` entry; the
        // originating provider serializes it back onto the wire and other
        // providers drop it in their `renderInput`.
        out += ProviderMessage.Reasoning(providerItemId, summary, encryptedContent)
    }

    // Dangling tool_call without a result — defensive fallback. Should
    // never fire under the normal flow (every non-terminal tool emits
    // a `MessageRole.Tool` event that produces a paired `ToolResult` frame),
    // but providers reject bare `tool_calls` in the request, so we
    // ensure every dangling pending id has SOMETHING.
    //
    // Bug #67 — the placeholder text is intentionally diagnostic
    // rather than blandly true ("no result recorded" tells the agent
    // nothing about why). When this fallback fires it means the
    // tool's `executeTyped` returned a stream that didn't include any
    // `MessageRole.Tool`-shaped event for this call_id — typically a
    // sync throw escaping the tool's `handleError` (the bug shape
    // #67 fixes for the script tools). The text frames it as a
    // framework-level miss so the agent doesn't blame its own input.
    pendingToolCallId.foreach { callId =>
      out += ProviderMessage.ToolResult(
        toolCallId = callId,
        content =
          "(framework error: tool emitted no MessageRole.Tool event for this call_id; " +
            "this is a bug in the tool's executeTyped — usually a sync throw escaping " +
            "its handleError. Please report it.)"
      )
    }

    mergeAdjacentAssistantContent(out.result())
  }

  /** Bug #74 — merge consecutive content-only `ProviderMessage.Assistant`
    * entries into a single message whose content is the run joined with
    * `\n\n`. OpenAI-compatible providers (incl. llama.cpp) reject two
    * adjacent `role=assistant` content messages with HTTP 400 ("Cannot
    * have 2 or more assistant messages at the end of the list"); the
    * canonical multi-respond turn (`endsTurn = false` followed by a
    * settling `endsTurn = true` respond) produces exactly that shape.
    *
    * Only content-only assistants merge — tool-call assistant messages
    * pass through untouched (they're paired with their `tool` result
    * messages and provider wire formats accept them). */
  private def mergeAdjacentAssistantContent(messages: Vector[ProviderMessage]): Vector[ProviderMessage] = {
    val out = Vector.newBuilder[ProviderMessage]
    var pending: Option[ProviderMessage.Assistant] = None
    val joiner = "\n\n"

    def flush(): Unit = {
      pending.foreach(out += _)
      pending = None
    }

    messages.foreach {
      case a: ProviderMessage.Assistant if a.toolCalls.isEmpty =>
        pending match {
          case Some(prev) =>
            pending = Some(ProviderMessage.Assistant(
              content   = prev.content + joiner + a.content,
              toolCalls = Nil
            ))
          case None =>
            pending = Some(a)
        }
      case other =>
        flush()
        out += other
    }
    flush()
    out.result()
  }

  /** Walk the frame vector and merge runs of [[ContextFrame.ToolResult]]
    * frames sharing the same `callId` into a single frame whose
    * content is the run's contents joined with `\n\n`. Bug #69 — tool
    * authors who emit multiple Tool-role events for one call (the
    * old [[sigil.event.ToolResults]] suggestion-cascade pattern, the
    * primary-result-plus-followup shape, etc.) get a single wire
    * `function_call_output` instead of one paired result + N orphan
    * frames.
    *
    * Only **adjacent** ToolResult frames merge — interleaved frames
    * (a Text frame between two ToolResults sharing a callId) are kept
    * separate since the textual ordering is meaningful. In practice
    * orchestrator-stamped events from a single `executeTyped` arrive
    * contiguously, so adjacency tracks the actual "all from one tool
    * call" boundary. */
  private def mergeAdjacentToolResults(frames: Vector[ContextFrame]): Vector[ContextFrame] = {
    val out = Vector.newBuilder[ContextFrame]
    var pending: Option[ContextFrame.ToolResult] = None
    val joiner = "\n\n"

    def flush(): Unit = {
      pending.foreach(out += _)
      pending = None
    }

    frames.foreach {
      case curr @ ContextFrame.ToolResult(callId, content, _, _) =>
        pending match {
          case Some(prev) if prev.callId == callId =>
            // Same call_id — merge into the pending accumulator.
            // Keep the earliest sourceEventId / visibility (caller can
            // override via dedicated joiner if needed; default is
            // newline-separated concat).
            pending = Some(prev.copy(content = prev.content + joiner + content))
          case _ =>
            flush()
            pending = Some(curr)
        }
      case other =>
        flush()
        out += other
    }
    flush()
    out.result()
  }
}

object Provider {

  /** Adaptive `max_tokens` cap applied when the paraphrase loop
    * detector has flagged this turn — bounds the damage when a
    * degenerate model is about to retry the same content. Default
    * 500 is informed by the live wire-log scenario where
    * `qwen3.6-35b` produced ~200k chars of repeated output before
    * hitting `max_tokens = 4096`. Smaller cap means the next
    * iteration sees the failure quickly and can self-correct via
    * the Failure-block diagnostic the orchestrator emits. */
  val ParaphraseLoopMaxOutputTokensCap: Int = 500
}
