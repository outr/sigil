package sigil.provider

import fabric.*
import fabric.io.JsonFormatter
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, ToolCallState, TurnInput}
import sigil.db.Model
import sigil.diagnostics.RequestProfiler
import sigil.participant.ParticipantId
import sigil.service.{Service, ServiceKind, ServiceState}
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
trait Provider extends Service with ModelResolver {
  def `type`: ProviderType

  /**
   * This provider's namespace key — matches the prefix on
   * `Model.canonicalSlug` and `Model._id`. Default derives from the
   * `type` enum value's lowercased name (`OpenAI` → `"openai"`).
   * Override only when a provider's models live under a different
   * namespace.
   */
  def providerKey: String = `type`.toString.toLowerCase

  // --- Service implementation ---

  /**
   * Stable [[sigil.service.Service.id]] derived from [[providerKey]].
   * Apps that run multiple providers of the same `type` (e.g. two
   * OpenAI keys) override to disambiguate (`provider.openai.dev` vs
   * `provider.openai.prod`); the default keys per `type`.
   */
  override def id: Id[Service] = Id[Service](s"provider.$providerKey")

  /**
   * Display name for the chip — the provider's `type` enum case.
   */
  override def name: String = `type`.toString

  /**
   * Providers serve models — they're [[ServiceKind.ModelServer]]
   * unless overridden.
   */
  override def kind: ServiceKind = ServiceKind.ModelServer

  /**
   * Derive a current health snapshot from observable signals:
   *
   *   - [[ServiceState.Degraded]] when the rate limiter advertises
   *     a measurable rate-limit pressure (apps that feed the
   *     limiter from response headers).
   *   - [[ServiceState.Degraded]] when [[capacityGate]] has zero
   *     permits available (every slot is in flight; new requests
   *     will queue).
   *   - [[ServiceState.Up]] otherwise.
   *
   * Providers with stronger telemetry (auth failure flag, recent
   * 5xx rate, last-error timestamp) override and compute richer
   * state. The default never enters [[ServiceState.Down]] /
   * [[ServiceState.Error]] — those require explicit knowledge the
   * trait can't infer.
   */
  override def currentState: ServiceState = {
    val capacityExhausted = capacityGate.availablePermits() <= 0
    if (capacityExhausted) ServiceState.Degraded("capacity-exhausted")
    else ServiceState.Up
  }

  /**
   * DB / configuration access for the shared translation pass. Wired
   * by each provider implementation (typically as a constructor arg).
   */
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

  /**
   * A single provider serves any model the registry holds — resolution
   * just pairs this provider with the record. Namespace dispatch across
   * *multiple* providers is [[ProviderRegistry]]'s job, which gates by
   * `providerKey` before delegating here, so this stays deliberately
   * lenient. `None` only when the id isn't registered at all.
   */
  override def resolve(modelId: Id[Model]): Option[ProviderModel] =
    sigil.cache.find(modelId).map(ProviderModel(this, _))

  /**
   * Tokenizer used by the framework's budget-validation pass to
   * estimate request size before sending. Default is the
   * char-count [[sigil.tokenize.HeuristicTokenizer]]; concrete
   * providers override to wire their model's actual tokenizer
   * (e.g. `OpenAIProvider` returns
   * [[sigil.tokenize.JtokkitTokenizer.OpenAIChatGpt]]).
   */
  def tokenizer: Tokenizer = HeuristicTokenizer

  /**
   * Proactive [[RateLimiter]] consulted before each outgoing request.
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
   * failure from happening, IF the app feeds it data.
   */
  def rateLimiter: RateLimiter = RateLimiter.NoOp

  /**
   * Maximum concurrent in-flight pre-flight passes this provider
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
   * gating wrap themselves with [[withCapacity]] explicitly.
   */
  def maxConcurrent: Int = Int.MaxValue

  /**
   * Per-provider fair semaphore enforcing [[maxConcurrent]]. Lazy
   * so subclass `maxConcurrent` overrides take effect. Bug #49.
   */
  final lazy val capacityGate: java.util.concurrent.Semaphore =
    new java.util.concurrent.Semaphore(maxConcurrent, /* fair */ true)

  /**
   * Wall-clock cap on `capacityGate.acquire()`. Bug #57 — the
   * original `acquire()` blocks the calling fiber's thread
   * indefinitely if a previous holder leaked the permit (task
   * never settled, fiber interrupted abnormally, etc.). For an
   * agent-loop hot path that produces zero HTTP traffic and zero
   * CPU when this happens, the symptom is "agent parked on
   * `thinking` forever." Bounding the wait surfaces the leak as
   * a [[CapacityAcquireTimeoutException]] the agent loop's error
   * handler can catch — fail loud rather than silent hang.
   */
  protected def capacityAcquireTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(60, "seconds")

  /**
   * Run a [[Task]] with a capacity-gate permit acquired from this
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
   * specific deployment knows its translates always finish faster.
   */
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

  // ---- live-stream slot gate ----

  /**
   * Whether the live chat-completions stream itself must hold a slot
   * permit for its full duration. Default `false` — matching Bug #49's
   * observation that the backend usually serializes its own slot use.
   *
   * A backend that queues excess requests SERVER-side while holding
   * the connections open (llama.cpp with N slots) turns that
   * observation into a failure mode: a batch tool fanning out hundreds
   * of calls leaves every stream idling on keepalives for minutes,
   * with sockets, idle-timeout exposure, and post-Stop waste to match.
   * With this flag `true`, requests past [[maxConcurrent]] wait
   * IN-PROCESS in FIFO order (fair semaphore) — no socket, no wire
   * exposure — and a queued waiter abandons the wait as soon as a Stop
   * lands for its conversation, before any request is issued.
   */
  def gateStreamingCalls: Boolean = false

  /**
   * Priority slot gate for live streams when [[gateStreamingCalls]]
   * is enabled. Deliberately separate from [[capacityGate]]: the
   * pre-flight gate's short leak-detection timeout (Bug #57) must not
   * be re-tuned around multi-minute stream holds, and pre-flight
   * advisory work shouldn't queue behind them.
   *
   * Interactive streams (agent-frame calls — `conversationId` set)
   * are granted freed permits ahead of queued batch calls (one-shot
   * consults), and the gate's batch-hold is how the silence
   * watchdog's starvation relief pauses fresh batch admissions while
   * an admitted stream is being starved server-side. Batch alone
   * still uses all permits. Per provider INSTANCE — apps that point
   * several instances at one backend account for the aggregate
   * themselves.
   */
  protected lazy val streamSlotGate: StreamSlotGate = new StreamSlotGate(maxConcurrent)

  /**
   * Ceiling on a queued stream-slot wait. Generous — a single-slot
   * backend legitimately drains a batch queue for many minutes; the
   * ceiling exists so a permit leak fails loudly instead of hanging
   * every future turn.
   */
  protected def streamSlotAcquireTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(30, "minutes")

  /**
   * Acquire a live-stream slot — FIFO within class, interactive ahead
   * of batch — abandoning the wait if a Stop lands for the call's
   * conversation (polled every 250ms inside the gate) or the ceiling
   * passes.
   */
  private def acquireStreamSlot(c: ProviderCall): Task[Unit] = Task.defer {
    if (streamSlotGate.availablePermits <= 0) {
      scribe.info(s"Provider($providerKey) stream slots busy (max=$maxConcurrent) — queueing")
    }
    val interactive = c.conversationId.isDefined
    def stopRequestedNow: Boolean = c.conversationId.exists(cid => sigil.stopRequested(cid, c.agentId))
    Task(streamSlotGate.acquire(interactive, () => stopRequestedNow, streamSlotAcquireTimeout.toMillis)).flatMap {
      case StreamSlotGate.Outcome.Acquired => Task.unit
      case StreamSlotGate.Outcome.TimedOut =>
        scribe.warn(s"Provider($providerKey) stream-slot wait timed out (max=$maxConcurrent) — possible permit leak")
        Task.error(new StreamSlotWaitAbortedException(providerKey, maxConcurrent, timedOut = true, streamSlotAcquireTimeout))
      case StreamSlotGate.Outcome.Stopped =>
        Task.error(new StreamSlotWaitAbortedException(providerKey, maxConcurrent, timedOut = false, streamSlotAcquireTimeout))
    }
  }

  /**
   * Route a wire call through the stream-slot gate when
   * [[gateStreamingCalls]] is enabled; plain [[call]] otherwise. The
   * permit is held for the stream's full life and released on every
   * termination path via `guarantee`. Retry backoff sleeps happen
   * OUTSIDE the permit — each attempt re-acquires — so a slot is
   * never parked on a `retry-after` wait.
   */
  protected def gatedCall(c: ProviderCall): Stream[ProviderEvent] =
    if (!gateStreamingCalls || maxConcurrent == Int.MaxValue) call(c)
    else Stream.force(
      acquireStreamSlot(c).map { _ =>
        try call(c).guarantee(Task(streamSlotGate.release()))
        catch { case t: Throwable => streamSlotGate.release(); throw t }
      }
    )

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
      case _ => None
    }
    Stream.force(
      sigil.runAsFrameworkWorkflow(
        workflowType = "preflight",
        label = s"Rendering pre-flight for ${request.modelId.value}",
        conversationId = convId
      ) { control =>
        rateLimiter.acquire.flatMap { _ =>
          control.token.checkpoint.flatMap(_ => withCapacity(translate(request)))
            .flatMap(normalizeStoredImages)
            .flatMap { providerCall =>
              control.step("Validating request size").map(_ => providerCall)
            }
        }
      }.map { providerCall =>
        preFlightGate(request, providerCall) match {
          case Right(safe) =>
            // Sigil #283 — rolling-window pacing: when the model
            // record carries `inputTokensPerMinute`, the framework
            // tracks recent usage in a 60s sliding window. A new
            // request that would push the window's running total
            // past `inputTokensPerMinute × safetyMargin` is held
            // (sleeps until the oldest entry ages out, then re-
            // checks) instead of being sent through to provoke a
            // 429. The pre-flight gate above already rejected the
            // single-oversized-request case as Fatal; this path
            // handles the cumulative-fan-out case where two requests
            // individually fit but combined exceed the per-minute
            // ceiling.
            // Sigil #395 — if this model is already known (this process) to
            // reject a forced tool_choice, demote to Auto BEFORE the wire call
            // so we don't re-pay the #387 400-then-downgrade round-trip on
            // every agent-loop iteration. The respond family is always in the
            // roster, so Auto still lets the agent act.
            val routed =
              if (safe.toolChoice.isForced && Provider.rejectsForcedToolChoice(safe.model._id))
                safe.copy(toolChoice = ToolChoice.Auto)
              else safe
            Stream.force(
              admitToWindow(request.modelId, estimateRequest(routed))
                .map(_ => callWithTransientRetry(routed))
            )
          case Left(reason) => Stream.force(Task.error(reason))
        }
      }
    )
  }

  /**
   * Sigil #283 — per-(provider, modelId) [[TokenWindowTracker]]
   * registry. Lazy: a model with `inputTokensPerMinute = None` never
   * allocates a tracker. Apps wiring cross-provider tracker sharing
   * (one upstream account fronted by two Provider instances) override
   * [[tokenWindowTracker]] to return a shared instance keyed on the
   * API key rather than the modelId.
   */
  private val tokenWindowTrackers: java.util.concurrent.ConcurrentHashMap[lightdb.id.Id[Model], TokenWindowTracker] =
    new java.util.concurrent.ConcurrentHashMap()

  /**
   * Resolve (or lazily construct) the [[TokenWindowTracker]] for
   * `modelId`. Returns `None` when the model record has no
   * `inputTokensPerMinute` — pacing is disabled.
   */
  protected def tokenWindowTracker(modelId: lightdb.id.Id[Model]): Option[TokenWindowTracker] =
    sigil.cache.find(modelId).flatMap(_.inputTokensPerMinute).map { ipm =>
      tokenWindowTrackers.computeIfAbsent(
        modelId,
        _ => new TokenWindowTracker(ipm, sigil.rateLimitSafetyMargin)
      )
    }

  private def admitToWindow(modelId: lightdb.id.Id[Model], estimatedTokens: Int): Task[Unit] =
    tokenWindowTracker(modelId) match {
      case Some(tracker) => tracker.admit(estimatedTokens)
      case None => Task.unit
    }

  // ---- batch (sigil #299) ----

  /**
   * Sigil #299 — whether this provider has a native bulk surface
   * worth routing batchable workloads through. `true` means
   * [[batch]] is overridden to use the upstream's
   * batch endpoint (OpenAI Batch / Anthropic Message Batches /
   * Gemini Batch — typically a ~50% cost discount with a 24-hour
   * SLA, effectively unlimited per-batch rate). `false` means
   * `batch` falls through to per-request `apply` calls — same
   * wall-clock as N parallel syncs, no discount.
   *
   * Consumers running offline bulk pipelines (RAG corpus rebuilds,
   * bulk classification, periodic re-summarization) read this to
   * decide whether the workload is worth chunking through `batch`
   * at all; interactive paths ignore it and call `apply` directly.
   *
   * Default `false` — opt-in per provider so the trait stays honest
   * about what's actually batchable on the wire.
   */
  def batchSupported: Boolean = false

  /**
   * Sigil #299 — bulk-submit a stream of [[OneShotRequest]]s against
   * the provider's native batch API. Responses stream back as each
   * underlying batch completes (out-of-order across batches;
   * in-order within a single batch's result file).
   *
   * Providers with a wire-level batch surface (OpenAI Batch,
   * Anthropic Message Batches, Gemini Batch) override to get the
   * ~50% cost reduction + higher throughput + async SLA. The
   * default sequential-fallback runs each request through `apply`
   * and collects the events into a [[OneShotResponse]] — correct
   * everywhere, optimal nowhere a native batch exists. Apps that
   * want to decide based on capability check [[batchSupported]]
   * before routing; calling `batch` on a non-batching provider is
   * not an error, just no discount.
   *
   * The input is a Stream so consumers can produce millions of
   * requests without holding them all in memory; the output is a
   * Stream so responses begin flowing as soon as the first
   * underlying batch completes. Native overrides chunk the input
   * internally per provider's per-batch size limit (OpenAI: 50K
   * requests / 200MB file).
   *
   * Failure semantics: per-request errors surface as `OneShotResponse`
   * with `error` populated (the rest of the stream keeps flowing).
   * Whole-batch upstream failures (network blip, batch-endpoint
   * 5xx) propagate as a stream error after best-effort cleanup of
   * the failed chunk's responses.
   */
  def batch(requests: Stream[OneShotRequest]): Stream[OneShotResponse] =
    requests.evalMap(applyOneShot)

  /**
   * Sigil #299 — single-request shape used by the default `batch`
   * fallback. Drains [[apply]]'s event stream into a
   * [[OneShotResponse]] by accumulating text deltas, usage, and
   * any stream-level error. Native-batch overrides bypass this
   * helper entirely — they go straight from JSONL line → typed
   * response without the streaming detour.
   */
  protected def applyOneShot(request: OneShotRequest): Task[OneShotResponse] = {
    val text = new StringBuilder
    val usageRef = new java.util.concurrent.atomic.AtomicReference[Option[TokenUsage]](None)
    val errorRef = new java.util.concurrent.atomic.AtomicReference[Option[OneShotResponse.Error]](None)
    apply(request).evalMap { ev =>
      Task {
        ev match {
          case ProviderEvent.TextDelta(t) => val _ = text.append(t)
          case ProviderEvent.ContentBlockDelta(_, t) => val _ = text.append(t)
          case ProviderEvent.Usage(u) => usageRef.set(Some(u))
          case ProviderEvent.Error(msg) =>
            errorRef.set(Some(OneShotResponse.Error(message = msg)))
          case _ => ()
        }
        ()
      }
    }.drain.map { _ =>
      val content: Vector[_root_.sigil.tool.model.ResponseContent] =
        if (text.isEmpty) Vector.empty
        else Vector(_root_.sigil.tool.model.ResponseContent.Text(text.toString))
      OneShotResponse(
        requestId = request.requestId,
        content = content,
        usage = usageRef.get(),
        error = errorRef.get()
      )
    }.handleError { t =>
      Task.pure(OneShotResponse(
        requestId = request.requestId,
        error = Some(OneShotResponse.Error(
          message = Option(t.getMessage).getOrElse(t.getClass.getSimpleName),
          recoverable = false
        ))
      ))
    }
  }

  /**
   * Sigil bug #211 — framework-level retry on `Retry`-classified
   * transient provider errors. The framework already classifies
   * network timeouts / 502 / 503 / rate-limits as `Retry`
   * (see [[ErrorClassifier.Default]]); this method ACTS on that
   * classification by re-attempting the wire call (up to
   * [[providerRetryAttempts]] times) before
   * propagating, so a single TLS handshake hiccup / OpenRouter
   * edge RST / brief rate-limit spike doesn't terminate the user's
   * turn.
   *
   * **Retry-only-on-empty-emission.** Each attempt drains the
   * call's stream via an evalTap-captured buffer. If the stream
   * completes successfully the buffered events replay through the
   * returned stream. If the stream errors:
   *
   *   - With zero events emitted AND the classifier returns
   *     `Retry` AND retries remain → wait `providerRetryDelay`
   *     and retry the call (re-drains a fresh stream).
   *   - With at least one event emitted → flush the buffered
   *     events as a stream prefix, then propagate the error.
   *     Mid-stream errors aren't retryable — downstream consumers
   *     (orchestrator's `onErrorFinalize`, the corruption-
   *     resistance `guarantee` block) need to see the partial
   *     state and the error to do orphan-settle cleanup.
   *   - Non-`Retry` error → propagate immediately (with any
   *     buffered events flushed first).
   *
   * Tradeoff: events are buffered for the duration of each
   * attempt, so streaming-text responses appear in one chunk
   * instead of progressively. Tool-call-only responses (the bug
   * repro case) are unaffected — they're a single batch anyway.
   *
   * Apps that prefer streaming over retry-correctness override
   * [[providerRetryAttempts]] to `0` to disable.
   */
  protected def providerRetryAttempts: Int = 3

  /**
   * Per-retry backoff. Transient transport flakes typically
   * resolve in < 1 s; longer waits delay the user without
   * changing the outcome.
   */
  protected def providerRetryDelay: scala.concurrent.duration.FiniteDuration = {
    import scala.concurrent.duration.*
    500.millis
  }

  /**
   * Classifier used to decide which thrown errors are
   * transient-and-retryable. Defaults to [[ErrorClassifier.Default]]
   * (matches the system-prompt instruction agents read for the
   * tool-call layer). Providers with stronger typing
   * (provider-specific exception types) override and compose via
   * `.orElse(ErrorClassifier.Default)`.
   */
  protected def providerErrorClassifier: ErrorClassifier = ErrorClassifier.Default

  private def callWithTransientRetry(safe: ProviderCall): Stream[ProviderEvent] = {
    val retries = providerRetryAttempts
    if (retries <= 0) gatedCall(safe)
    else {
      val classifier = providerErrorClassifier
      // Re-raise `t` from a stream that has already had its prefix emitted —
      // `evalMap`'s error fires on pull (not at materialization, which rapid's
      // `++` would), so downstream `onErrorFinalize` / `guarantee` see the
      // partial state before the failure.
      def fail(t: Throwable): Stream[ProviderEvent] =
        Stream.emit(()).evalMap[ProviderEvent](_ => Task.error[ProviderEvent](t))

      // Sigil #399 — stream events LIVE so MessageDeltas reach subscribers as
      // tokens arrive off the wire, while keeping retry-safety. The old shape
      // drained the WHOLE stream into a buffer before re-emitting (so every
      // content delta was withheld to end-of-turn). Instead, only the LEADING
      // non-meaningful events (Usage / ThinkingDelta / ReasoningItem) are
      // buffered — those are the events the design drops on retry (561-569) so a
      // failed attempt's reasoning chain doesn't duplicate. The moment a
      // MEANINGFUL event (or a terminal Done / Error) arrives, the buffer is
      // flushed and the stream goes fully live (`committed`). A retry / self-heal
      // engages only while nothing is committed — the retryable + self-heal
      // errors (429, 5xx, tool_choice / sampling 400s) all surface at stream
      // START, before any meaningful event, so a retry never duplicates observed
      // output; an error after `committed` propagates after the live prefix.
      def attempt(currentCall: ProviderCall,
                  remaining: Int,
                  ctx: Option[RetryContext],
                  downgradedToolChoice: Boolean,
                  strippedSampling: Boolean): Stream[ProviderEvent] = {
        val perAttempt = ctx.fold(currentCall)(rc => currentCall.copy(retryContext = Some(rc)))
        val committed = new java.util.concurrent.atomic.AtomicBoolean(false)
        val head = new java.util.concurrent.atomic.AtomicReference[List[ProviderEvent]](Nil)
        def flushTrigger(ev: ProviderEvent): Boolean =
          isMeaningfulProviderEvent(ev) ||
            (ev match {
              case _: ProviderEvent.Done | _: ProviderEvent.Error => true
              case _ => false
            })
        gatedCall(perAttempt)
          .flatMap { ev =>
            if (committed.get()) Stream.emit(ev)
            else if (flushTrigger(ev)) {
              committed.set(true)
              Stream.emits(head.getAndSet(Nil).reverse :+ ev)
            } else {
              head.updateAndGet(ev :: _)
              Stream.empty
            }
          }
          .handleErrorWith { t =>
            // Nothing committed yet — only leading non-meaningful events were
            // buffered, and they're discarded — so a retry / self-heal is safe.
            // Once committed, a retry would duplicate observed output: propagate
            // after the live prefix.
            if (committed.get()) fail(t)
            // Sigil #415 — a user Stop that lands mid-call (its stream-
            // cancel typically surfaces here as a transport error) or
            // mid-backoff must not be answered with a fresh wire call:
            // the already-fired cancel can't reach a request issued
            // after it. Propagate instead of retrying / self-healing;
            // the agent loop's stop handling ends the turn quietly.
            else if (currentCall.conversationId.exists(cid => sigil.stopRequested(cid, currentCall.agentId))) {
              scribe.info(
                s"Sigil #415 — suppressing provider retry for ${currentCall.model._id.value}: " +
                  "stop requested for the conversation")
              fail(t)
            } else {
              val cls = classifier.classify(t)
              // Sigil #387 — model-agnostic self-heal: a model that rejects
              // forced tool_choice (Fable 5 / Mythos 5 → HTTP 400 "tool_choice
              // forces tool use is not compatible with this model.") gets the
              // SAME call retried once with tool_choice downgraded to Auto. Both
              // Required and Specific collapse to Auto; the respond family is
              // always in the roster so Auto still lets the agent act. At most
              // one downgrade per call (`downgradedToolChoice` guard).
              if (
                !downgradedToolChoice && currentCall.toolChoice.isForced
                && Provider.isForcedToolChoiceRejection(t)
              ) {
                // Sigil #395 — remember the rejection so later calls (this turn's
                // remaining iterations and every future turn) demote up front
                // instead of re-paying this round-trip per iteration.
                Provider.recordForcedToolChoiceRejection(currentCall.model._id)
                scribe.warn(
                  s"Sigil #387 — model ${currentCall.model._id.value} rejected forced tool_choice " +
                    s"(${currentCall.toolChoice}); downgrading to Auto and retrying once"
                )
                attempt(
                  currentCall.copy(toolChoice = ToolChoice.Auto),
                  remaining,
                  ctx,
                  downgradedToolChoice = true,
                  strippedSampling)
              } else if (
                !strippedSampling
                && (currentCall.generationSettings.temperature.isDefined ||
                  currentCall.generationSettings.topP.isDefined)
                && Provider.isDeprecatedSamplingParam(t)
              ) {
                // Sigil #390 — cold-cache BACKSTOP for sampling params. The
                // primary mechanism is proactive: providers drop temperature/top_p
                // a model doesn't list in its catalog `supported_parameters` (via
                // `Sigil.supportsParameter`), so a cataloged model never sends
                // them. This self-heal only fires when the catalog is empty/cold
                // (e.g. an Anthropic-direct app before an OpenRouter refresh):
                // Claude 5 (Fable 5 / Mythos 5) → HTTP 400 "`temperature` is
                // deprecated for this model." Strips temperature AND topP (the
                // whole category, so the API doesn't 400 again on top_p). At most
                // one strip per call; composes with the tool_choice downgrade.
                scribe.warn(
                  s"Sigil #390 — model ${currentCall.model._id.value} rejected a deprecated sampling " +
                    s"parameter; stripping temperature/topP and retrying once"
                )
                val stripped = currentCall.generationSettings.copy(temperature = None, topP = None)
                attempt(
                  currentCall.copy(generationSettings = stripped),
                  remaining,
                  ctx,
                  downgradedToolChoice,
                  strippedSampling = true)
              } else if (remaining > 0 && cls == ErrorClassification.Retry) {
                val nextCtx = nextRetryContext(t)
                // Sigil #283 — honor the upstream's `retry-after` (lifted by the
                // provider into ProviderErrorMetadata.retryAfterMs) over the
                // static providerRetryDelay. A 429 that says "wait 8 seconds"
                // should wait 8 seconds, not 500ms and burn three more retries.
                val honoredDelay = retryAfterFrom(t).getOrElse(providerRetryDelay)
                scribe.warn(
                  s"Sigil bug #211 — retrying transient provider error " +
                    s"(${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}) " +
                    s"after ${honoredDelay.toMillis}ms; $remaining retries remaining"
                )
                Stream.force(Task.sleep(honoredDelay).map(_ =>
                  attempt(currentCall, remaining - 1, Some(nextCtx), downgradedToolChoice, strippedSampling)))
              } else {
                // Not retryable (or budget exhausted). Propagate after whatever
                // (non-meaningful) prefix already streamed.
                fail(t)
              }
            }
          }
      }
      attempt(safe, retries, None, downgradedToolChoice = false, strippedSampling = false)
    }
  }

  /**
   * Whether the event represents committed work the downstream
   * consumer may have started rendering. Reasoning is transient — the
   * consumer renders it as a "thinking..." placeholder, and a failed
   * attempt's buffered events are dropped entirely when retry fires
   * (the orchestrator only ever sees the final attempt's stream), so
   * a fresh reasoning chain on retry is invisible / desirable rather
   * than a duplicate. Usage / Error / Done are bookkeeping. Anything
   * else (text, tool calls, image generation, response-state capture,
   * server-tool lifecycle) is committed work that retry would shadow.
   */
  private def isMeaningfulProviderEvent(ev: ProviderEvent): Boolean = ev match {
    case _: ProviderEvent.Usage => false
    case _: ProviderEvent.Error => false
    case _: ProviderEvent.Done => false
    case _: ProviderEvent.ThinkingDelta => false
    case _: ProviderEvent.ReasoningItem => false
    case _ => true
  }

  /**
   * Derive the next attempt's [[RetryContext]] from the failure that
   * triggered the retry. Pulls the upstream-provider name out of a
   * typed [[ProviderStreamException]] when present so providers like
   * OpenRouter can append it to their `provider.ignore` request
   * block. Unknown errors yield an empty context.
   */
  private def nextRetryContext(t: Throwable): RetryContext = t match {
    case e: ProviderStreamException =>
      RetryContext(lastErrorUpstreamProvider = e.errorMetadata.flatMap(_.upstreamProvider))
    case _ => RetryContext()
  }

  /**
   * Sigil #283 — extract the upstream's requested `retry-after`
   * delta when the failing call carried one. Two carriers, in
   * priority order:
   *
   *   1. [[ProviderStreamException]] with `errorMetadata.retryAfterMs`
   *      populated — providers that detect a mid-stream 429 inline (an
   *      `error` event on a 200-OK SSE stream) lift the explicit
   *      `retry-after`-equivalent payload into the typed metadata.
   *   2. [[spice.http.client.StreamingHttpFailedException]] — when the
   *      upstream returned a non-2xx HTTP status, spice's streaming
   *      path now throws a typed exception carrying the response
   *      headers. The framework extracts `retry-after` directly so
   *      every provider gets retry-after honoring for free, without
   *      each provider's `call` having to translate the exception.
   *
   * Parses the `retry-after` header per RFC 7231: an integer delta
   * in seconds or an HTTP-date (absolute timestamp). Returns `None`
   * when the failure has no upstream guidance — the retry loop falls
   * back to `providerRetryDelay`.
   */
  private def retryAfterFrom(t: Throwable): Option[scala.concurrent.duration.FiniteDuration] = t match {
    case e: ProviderStreamException =>
      e.errorMetadata.flatMap(_.retryAfterMs).map { ms =>
        scala.concurrent.duration.FiniteDuration(math.max(0L, ms), "millis")
      }
    case e: spice.http.client.StreamingHttpFailedException =>
      parseRetryAfter(e.headers)
    case _ => None
  }

  /**
   * Parse a `Retry-After` HTTP header (RFC 7231 §7.1.3) into a
   * [[FiniteDuration]]. Accepts both formats: `Retry-After: 120`
   * (delta-seconds) and `Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`
   * (HTTP-date — clamped to non-negative). Returns `None` when the
   * header is absent or unparseable. Sigil #283.
   */
  private def parseRetryAfter(headers: spice.http.Headers): Option[scala.concurrent.duration.FiniteDuration] = {
    val raw = headers.map.get("Retry-After").flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)
    raw.flatMap { value =>
      scala.util.Try(value.toLong).toOption match {
        case Some(deltaSeconds) =>
          Some(scala.concurrent.duration.FiniteDuration(math.max(0L, deltaSeconds * 1000L), "millis"))
        case None =>
          // HTTP-date format — RFC 1123 / 850 / asctime. java.time
          // handles RFC 1123 directly; the other two are rarely seen
          // in modern responses but we attempt RFC 1123 only here.
          scala.util.Try {
            val instant = java.time.ZonedDateTime
              .parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
              .toInstant
            val deltaMs = instant.toEpochMilli - System.currentTimeMillis()
            scala.concurrent.duration.FiniteDuration(math.max(0L, deltaMs), "millis")
          }.toOption
      }
    }
  }

  /**
   * Pre-flight budget validation. Two layered checks against the
   * model record:
   *
   *   1. **Context-length** (`Model.contextLength`) — the static
   *      window the model accepts on a single request. Failure mode
   *      raised as [[RequestOverBudgetException]].
   *   2. **Per-minute input rate** (`Model.inputTokensPerMinute`,
   *      sigil #283) — the provider's published per-minute token
   *      ceiling. A single request larger than
   *      `rate * Sigil.rateLimitSafetyMargin` (default 0.85) can't
   *      succeed against the per-minute budget by itself, so
   *      retrying after a 429 is wasted work. Failure mode raised
   *      as [[RequestExceedsRateLimitException]].
   *
   * Both checks apply emergency shedding (tool-roster trim →
   * last-resort frame drop) before failing; the tighter of the two
   * effective limits drives the shed target. Critical memories live
   * in the system prompt and are never shed by this path.
   *
   * Returns `Right(call)` when the request fits both checks
   * (possibly after shedding), `Left(exception)` when it can't.
   */
  private def preFlightGate(request: ProviderRequest, providerCall: ProviderCall): Either[Throwable, ProviderCall] = {
    val modelRecord = sigil.cache.find(request.modelId)
    // Sigil #301 — tighten by `contextLengthSafetyMargin` so the
    // estimator's documented ~7-15% piecewise-vs-wire gap doesn't
    // squeeze requests past the provider's HTTP 400 path. Mirrors the
    // rate-side `rateLimitSafetyMargin` knob below.
    val contextLimit = modelRecord
      .map(m => math.max(1, (m.contextLength.toDouble * sigil.contextLengthSafetyMargin).toInt))
      .getOrElse(Int.MaxValue)
    val ratePerMinute = modelRecord.flatMap(_.inputTokensPerMinute)
    val rateLimit = ratePerMinute match {
      case Some(rpm) => math.max(1, (rpm * sigil.rateLimitSafetyMargin).toInt)
      case None => Int.MaxValue
    }
    val effectiveLimit = math.min(contextLimit, rateLimit)
    if (effectiveLimit == Int.MaxValue) Right(providerCall) // no model record AND no rate ceiling — can't validate
    else {
      val initial = estimateRequest(providerCall)
      if (initial <= effectiveLimit) Right(providerCall)
      else {
        val shed = emergencyShed(providerCall, effectiveLimit, tokenizer, estimateRequest)
        val shedEstimate = estimateRequest(shed)
        if (shedEstimate <= effectiveLimit) Right(shed)
        else if (shedEstimate > contextLimit) Left(new RequestOverBudgetException(shedEstimate, contextLimit, request.modelId))
        else Left(new RequestExceedsRateLimitException(
          estimatedTokens = shedEstimate,
          inputTokensPerMinute = ratePerMinute.getOrElse(0L),
          safetyMargin = sigil.rateLimitSafetyMargin,
          modelId = request.modelId
        ))
      }
    }
  }

  /**
   * Estimate the wire-rendered token count for `call`. Bug #46 —
   * exposed as a `protected` hook so providers whose wire is built
   * by composing a chat template (every chat-completions-style
   * provider) can override with an exact backend-rendered count
   * (e.g. `LlamaCppProvider` calls `/apply-template` + `/tokenize`).
   *
   * Default: piecewise sum of system + per-message + roster. Correct
   * within ~7-15% for chat-template providers; the gap is the
   * template glue between messages that piecewise summing misses.
   * Providers with large context windows tolerate the gap; tight
   * `n_ctx` configs don't, and override accordingly.
   */
  protected def estimateRequest(call: ProviderCall): Int = {
    val tok = tokenizer
    // Sigil #302 — count both stable and volatile system segments;
    // they both ship on the wire (Anthropic in two segments, other
    // providers concatenated).
    tok.count(call.system) +
      tok.count(call.systemVolatile) +
      call.messages.iterator.map(estimateMessage(_, tok)).sum +
      estimateRoster(call.tools, tok)
  }

  /**
   * Best-effort token count for a single [[ProviderMessage]] as it
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
   * adds ~4 tokens for the role + content envelope.
   */
  protected def estimateMessage(m: ProviderMessage, tok: Tokenizer): Int = m match {
    case ProviderMessage.System(c) => tok.count(c) + 4
    case ProviderMessage.User(blocks) => blocks.iterator.map {
        case MessageContent.Text(t) => tok.count(t)
        case _: MessageContent.Image => 85 // standard low-detail image overhead per OpenAI's docs
        case _: MessageContent.ImageBytes => 85
      }.sum + 4
    case ProviderMessage.Assistant(c, calls) =>
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

  /**
   * Token cost of the wire tool roster — name + description + the
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
   * override [[estimateToolBytes]] for higher fidelity.
   */
  protected def estimateRoster(tools: Vector[Tool], tok: Tokenizer): Int =
    tools.iterator.map(estimateToolBytes(_, tok)).sum

  /**
   * Per-tool wire-shape estimate. Default counts name + description +
   * the JSON-formatted parameter schema. Override for providers with
   * extra per-tool metadata (Anthropic's `cache_control`, OpenAI's
   * `strict` flag, etc.) — the framework's default already counts
   * the schema body which is the dominant cost.
   *
   * Bug #47 — concatenates the per-tool wire bytes into ONE
   * tokenizer call instead of three (name / description / schema).
   * For providers whose tokenizer makes an HTTP round-trip
   * (`LlamaCppTokenizer`), this drops the per-tool HTTP cost from
   * 3 to 1 — material when the agent has a dozen tools.
   */
  protected def estimateToolBytes(tool: Tool, tok: Tokenizer): Int = {
    val name = tool.schema.name.value
    val description = tool.descriptionFor(ConversationMode, sigil)
    val schemaJson = fabric.io.JsonFormatter.Compact(
      _root_.sigil.tool.DefinitionToSchema(tool.schema.input)
    )
    // Wrapper overhead: `{"type":"function","name":"...","description":"...","parameters":{...}}`
    // — keys + braces + colons. ~10 tokens depending on tokenizer.
    val wrapper = 12
    tok.count(s"$name\n$description\n$schemaJson") + wrapper
  }

  /**
   * Emergency-shed: trim tool roster (cap descriptions or drop
   * un-essential tools) and drop oldest frames until the request
   * fits. Stops when nothing more can be safely cut — caller raises
   * [[RequestOverBudgetException]] in that case. Does NOT call the
   * LLM (compression already happened in the curator); pure
   * truncation.
   */
  private def emergencyShed(initial: ProviderCall,
                            limit: Int,
                            tok: Tokenizer,
                            estimateOf: ProviderCall => Int): ProviderCall = {
    var current = initial

    // Stage 4 — drop tool roster down to (framework essentials +
    // tools the prompt actively advertises). Critical for cases where
    // a large tool catalog is the bulk of overhead; baseline tools
    // (respond / find_capability / stop / change_mode) are always
    // retained so the agent can still function.
    //
    // Sigil #305 — `preservedToolNames` adds the tools the prompt's
    // own sections (Suggested tools, Recently used) advertise to the
    // model. Without it, the shed could leave the wire carrying
    // ONLY the essentials set while the prompt still promised
    // the agent's discovered roster — the divergence that drove the
    // change_mode-loop failure mode in the field. Keeping the
    // advertised names means the agent can act on what the prompt
    // tells it is available; truly unused catalog bulk still drops.
    val essentials = Set(
      "respond",
      "find_capability",
      "stop",
      "change_mode",
      "no_response",
      "respond_options",
      "activate_skill")
    val keep: _root_.sigil.tool.ToolName => Boolean = n =>
      essentials.contains(n.value) || initial.preservedToolNames.contains(n)
    if (estimateOf(current) > limit && current.tools.exists(t => !keep(t.schema.name))) {
      val trimmed = current.tools.filter(t => keep(t.schema.name))
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
      while (estimateOf(current) > limit && current.messages.nonEmpty)
        current = current.copy(messages = current.messages.tail)
    }

    current
  }

  /**
   * Bulk-drop oldest messages from the call using a local
   * heuristic to compute the drop count, sized so the post-drop
   * message bytes fit under `limit` minus the system-prompt
   * overhead. Local-only — no HTTP round-trips even when the
   * provider's `tokenizer` would. Returns the trimmed call;
   * caller follows up with one `estimateOf` confirmation. Bug #59.
   */
  private def bulkDropMessages(call: ProviderCall,
                               limit: Int,
                               tok: Tokenizer,
                               estimateOf: ProviderCall => Int): ProviderCall = {
    val msgs = call.messages
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
    val margin = (limit * 0.05).toInt
    val msgBudget = math.max(0, limit - overhead - margin)
    val needToShed = math.max(0, msgSum - msgBudget)
    if (needToShed <= 0) call
    else {
      // Walk perMsg from the front, summing, until the prefix
      // sum reaches `needToShed`. That's the number of oldest
      // messages we can drop without crossing the budget.
      val cum = perMsg.scanLeft(0)(_ + _)
      val idx = cum.indices.find(i => cum(i) >= needToShed).getOrElse(perMsg.size)
      val k = math.min(idx, msgs.size)
      call.copy(messages = msgs.drop(k))
    }
  }

  /**
   * Best-effort textual rendering of a [[ProviderMessage]] for
   * the local heuristic tokenizer's per-message estimate. Bug #59
   * — exact wire-byte fidelity isn't needed here since the
   * caller follows up with `estimateOf` confirmation; this only
   * has to be a stable proxy for relative message size.
   */
  private def renderMessageForHeuristic(m: ProviderMessage): String = m match {
    case ProviderMessage.System(c) => c
    case ProviderMessage.User(blocks) => blocks.iterator.map {
        case t: MessageContent.Text => t.text
        case _ => ""
      }.mkString("\n")
    case ProviderMessage.Assistant(c, calls) =>
      val callsText = calls.iterator.map(tc => s"${tc.name}:${tc.argsJson}").mkString("\n")
      s"$c\n$callsText"
    case ProviderMessage.ToolResult(_, c) => c
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
    case s: OneShotRequest => Task.pure(translateOneShot(s))
  }

  private def translateConversation(c: ConversationRequest): Task[ProviderCall] =
    resolveReferences(c.turnInput).flatMap { resolved =>
      val agentId = c.chain.lastOption
      // Load the prior provider-response handle from the agent's
      // projection (today only OpenAI's Responses API uses it). Falls
      // back to (None, None) when no projection exists yet or no
      // agent is in the chain.
      val priorStateTask: Task[(Option[String], Option[Int])] = agentId match {
        case Some(pid) =>
          sigil.projectionFor(pid, c.conversationId).map { proj =>
            (proj.latestProviderResponseId, proj.latestProviderResponseMessageCount)
          }
        case None => Task.pure((None, None))
      }
      priorStateTask.flatMap { case (prevId, prevCount) =>
        translateConversationCore(c, resolved, agentId, prevId, prevCount)
      }
    }

  private def translateConversationCore(c: ConversationRequest,
                                        resolved: ResolvedReferences,
                                        agentId: Option[ParticipantId],
                                        previousResponseId: Option[String],
                                        priorMessageCount: Option[Int]): Task[ProviderCall] = {
    // Sigil #274 — the same wire-roster filter the Orchestrator's
    // `toolsByName` uses, so both ends of the dispatch agree on what's
    // in scope. See [[ConversationRequest.effectiveTools]].
    val effectiveTools = c.effectiveTools
    val toolChoice: ToolChoice =
      if (effectiveTools.isEmpty) ToolChoice.None
      else if (c.forceResponseSynthesis)
        // Sigil #375 — pin the forced-synthesis recovery turn to a
        // specific terminal tool. `Required` (Anthropic {type:"any"})
        // only constrains the model to call SOME tool, so a
        // tool-saturated model emits one OUTSIDE the narrowed respond
        // roster (observed Opus 4.8 answering a respond-family-only
        // turn with browser_screenshot); the recovery check then fails
        // and the loop throws AgentRunawayException. {type:"tool",
        // name:"respond"} forces exactly respond via constrained
        // decoding. forceResponseSynthesis already sets
        // reasoningMode=Off, so the thinking/forced-tool_choice
        // incompatibility doesn't apply.
        effectiveTools.find(_.schema.name == RespondTool.schema.name)
          .orElse(effectiveTools.headOption)
          .map(t => ToolChoice.Specific(t.schema.name))
          .getOrElse(ToolChoice.Required)
      else ToolChoice.Required
    val gen = tightenMaxTokensForParaphrase(c)
    val messages = nonEmptyMessages(c, agentId)
    // Sigil #305 — preserved-tool set: the tools the prompt's own
    // sections advertise to the model. emergencyShed honors this set
    // so the wire roster never drops below what the prompt promises,
    // closing the divergence behind the field's change_mode loop.
    val preserved: Set[_root_.sigil.tool.ToolName] = agentId match {
      case Some(pid) =>
        val proj = c.turnInput.projectionFor(pid)
        proj.suggestedTools.toSet ++
          proj.recentToolInvocations.iterator.map(_.toolName).toSet
      case None => Set.empty
    }
    val renderedSystem = renderSystem(c, resolved)
    val providerCall = ProviderCall(
      model = c.model,
      system = renderedSystem.stable,
      systemVolatile = renderedSystem.volatile,
      messages = messages,
      tools = effectiveTools,
      // Sigil #375 — built-in/server tools (web_search, …) bypass the
      // `effectiveTools` roster filter, so on a forced-synthesis turn
      // they'd reappear as a non-terminal escape hatch on the very
      // turn meant to force a terminal `respond`. Drop them for that
      // turn so the only callable tool is the pinned respond family.
      builtInTools = if (c.forceResponseSynthesis) Set.empty else c.builtInTools,
      toolChoice = toolChoice,
      generationSettings = gen,
      currentMode = c.currentMode,
      conversationId = Some(c.conversationId),
      agentId = agentId,
      previousResponseId = previousResponseId,
      priorMessageCount = priorMessageCount,
      preservedToolNames = preserved
    )
    emitWireProfile(c, resolved, agentId).map(_ => providerCall)
  }

  // Sigil #274 — `filterToolsForForcedSynthesis` moved to
  // [[ConversationRequest.effectiveTools]] so the wire path and the
  // dispatch path share one source of truth for the in-scope roster.

  /**
   * Adaptive max_tokens — when the paraphrase detector has flagged a
   * planning-without-acting loop on this turn (signal lives in
   * `turnInput.extraContext`), cap the per-call generation budget so
   * a degenerate model can't run all the way to its default
   * `maxOutputTokens` producing kilobytes of repeated text. Damage
   * bounded; the agent's next iteration reads the loop diagnostic and
   * can self-correct.
   */
  private def tightenMaxTokensForParaphrase(c: ConversationRequest): GenerationSettings =
    if (
      c.turnInput.extraContext.exists { case (k, _) =>
        k.value == _root_.sigil.conversation.compression.ParaphraseLoopDetector.ContextKeyValue
      }
    ) c.generationSettings.tightenedTo(Provider.ParaphraseLoopMaxOutputTokensCap)
    else c.generationSettings

  /**
   * Agent-initiated turns (greeting / scheduled / autonomous /
   * worker-spawn) reach this code path with no user message in the
   * conversation history — `renderFrames` returns empty and providers
   * would emit an empty `input` / `messages` array, which OpenAI
   * Responses, Anthropic Messages, and Google generateContent all
   * reject with HTTP 400 (each requires non-empty input). Synthesize
   * a single user-role placeholder so the wire shape is always
   * well-formed. The placeholder is request-only — never persists to
   * events; the agent's emitted reply is what gets stored.
   */
  private def nonEmptyMessages(c: ConversationRequest, agentId: Option[ParticipantId]): Vector[ProviderMessage] = {
    val rendered = renderFrames(c.turnInput.frames, agentId)
    if (rendered.nonEmpty) rendered
    else Vector(ProviderMessage.User(Provider.AgentInitiatedTurnTrigger))
  }

  /**
   * Diagnostic profiling — gated on `Sigil.profileWireRequests`
   * (default on; apps override to false to skip). Runs the tokenizer
   * once per turn over every section of the about-to-be-sent request
   * and broadcasts the breakdown as a `WireRequestProfile` Notice.
   * Cheap (jtokkit milliseconds for typical request sizes) — supports
   * the always-visible context-utilisation gauge downstream apps
   * render without further opt-in.
   */
  private def emitWireProfile(c: ConversationRequest,
                              resolved: ResolvedReferences,
                              agentId: Option[ParticipantId]): Task[Unit] =
    if (sigil.profileWireRequests) {
      agentId match {
        case Some(pid) =>
          val profile = RequestProfiler.profile(c, resolved, tokenizer, sigil)
          sigil.publish(WireRequestProfile(c.conversationId, c.modelId, pid, profile))
        case None => Task.unit
      }
    } else Task.unit

  private def translateOneShot(s: OneShotRequest): ProviderCall = {
    val toolChoice =
      if (s.tools.isEmpty) ToolChoice.None else ToolChoice.Required
    val userMessage =
      if (s.userContent.nonEmpty) ProviderMessage.User(toMessageContent(s.userContent))
      else ProviderMessage.User(s.userPrompt)
    ProviderCall(
      model = s.model,
      system = s.systemPrompt,
      messages = Vector(userMessage),
      tools = s.tools,
      builtInTools = s.builtInTools,
      toolChoice = toolChoice,
      generationSettings = s.generationSettings
    )
  }

  /**
   * Project the public [[ResponseContent]] vocabulary onto the
   * narrower wire-level [[MessageContent]] used in
   * [[ProviderMessage.User]]. `Text` and `Image` map directly;
   * structured variants (Code, Diff, Table, Heading, …) render to
   * a `Text` block via `toString` so the model still sees the
   * content even on text-only providers. Image blocks survive into
   * the wire layer; per-provider serialization there decides
   * whether to send or drop based on the target API's multimodal
   * support.
   */
  private def toMessageContent(content: Vector[ResponseContent]): Vector[MessageContent] =
    content.map {
      case ResponseContent.Text(t) => MessageContent.Text(t)
      // Sigil #382 — quality rides the storage URL as `_q` across the
      // persisted frame boundary; strip it here and carry it typed.
      case ResponseContent.Image(url, alt) =>
        MessageContent.Image(_root_.sigil.tool.ImageQuality.strip(url), alt, _root_.sigil.tool.ImageQuality.fromUrl(url))
      // Sigil #296 — inline bytes path. Apps that have transient
      // image data (PDF page renders, screen captures) avoid both
      // spice's URL.parse mangling of `data:` URIs AND the
      // StoredFile-persistence detour by handing in
      // ImageBytes directly; the wire layer's MessageContent.ImageBytes
      // is already supported by every multimodal provider.
      case ResponseContent.ImageBytes(mt, b64, alt) => MessageContent.ImageBytes(mt, b64, alt)
      case ResponseContent.Markdown(t) => MessageContent.Text(t)
      case ResponseContent.Code(c, lang) => MessageContent.Text(s"```${lang.getOrElse("")}\n$c\n```")
      case other => MessageContent.Text(MarkdownRenderer.renderBlock(other))
    }

  /**
   * Materialize internally-stored images for the wire. A
   * [[MessageContent.Image]] whose URL points at a Sigil
   * [[sigil.storage.StoredFile]] (path shape `…/storage/<id>`) is
   * rewritten to [[MessageContent.ImageBytes]] carrying the file's
   * bytes — the default local-storage URL is not reachable by the
   * provider's servers, so a fetchable URL can't be assumed.
   * Genuinely public URLs (signed S3, CDN) — whose path segment does
   * not resolve to a StoredFile — pass through unchanged. Runs once
   * over the translated call so every provider benefits.
   */
  private[provider] def normalizeStoredImages(call: ProviderCall): Task[ProviderCall] = {
    // Returns None to DROP the image block. Unresolvable images —
    // empty stored bytes, a missing blob or row, an empty external
    // fetch, empty inline base64 — are replaced by a caption-carrying
    // `[image unavailable]` Text marker instead: an empty image block
    // hard-400s the whole request on every subsequent turn
    // ("image cannot be empty"), permanently bricking the
    // conversation. The durable frame is untouched — only its wire
    // rendering changes — so a conversation that already captured a
    // bad image heals on its next turn.
    def normalizeContent(mc: MessageContent): Task[Option[MessageContent]] = mc match {
      case MessageContent.Image(url, altText, quality) =>
        storedFileIdFrom(url) match {
          case Some(id) =>
            sigil.withDB(_.storedFiles.transaction(_.get(id))).flatMap {
              case None =>
                // Storage-shaped URL with no row. A public URL that merely
                // contains `/storage/` may still be fetchable — route it
                // through the external path; anything else (the default
                // `sigil://` scheme) is unreachable by the provider and
                // must not ship as a `{type:url}` source.
                if (Provider.isFetchableImageUrl(url.toString)) materializeExternalImage(url.toString, quality, altText)
                else Task.pure(Some(Provider.imageUnavailableMarker(altText)))
              case Some(file) =>
                sigil.storageProvider.download(file.path).map {
                  case Some(bytes) if bytes.nonEmpty =>
                    // Sigil #382 — downscale to the quality tier before
                    // encoding; never ship original-resolution pixels. #401 —
                    // a re-encode can change the media type (webp → PNG), so
                    // carry the reported type, not the stored file's.
                    val r = _root_.sigil.image.ImageDownscale.resizeTyped(bytes, quality.maxPixels, file.contentType)
                    Some(MessageContent.ImageBytes(
                      mediaType = r.mediaType,
                      base64 = java.util.Base64.getEncoder.encodeToString(r.bytes),
                      altText = altText,
                      quality = quality
                    ))
                  case _ =>
                    // Empty or missing blob — a zero-byte capture stored by a
                    // flaky producer, or storage that lost the bytes.
                    scribe.warn(s"normalizeStoredImages: stored image ${id.value} resolved to no bytes — " +
                      "rendering an [image unavailable] marker instead of an empty image block")
                    Some(Provider.imageUnavailableMarker(altText))
                }
            }
          case None =>
            // Sigil #393 — an EXTERNAL image URL: fetch + downscale to the
            // quality tier and ship base64, never a raw `{type:url}` (the
            // provider would fetch it at full size — blowing the multimodal
            // ceiling → silent non-render → blind re-view loops). Non-http
            // (`data:` etc.) passes through untouched.
            if (Provider.isFetchableImageUrl(url.toString)) materializeExternalImage(url.toString, quality, altText)
            else Task.pure(Some(mc))
        }
      case MessageContent.ImageBytes(mediaType, base64, altText, quality) =>
        // Inline bytes (PDF page renders, screen captures via
        // ResponseContent.ImageBytes, data: URLs) reach the wire through
        // `translate` WITHOUT the stored/external downscale paths. Re-clamp
        // here so an oversized inline image can't 400 the provider
        // ("image dimensions exceed max allowed size: 8000 pixels"). Run on
        // every wire build, not at ingestion — the persisted event keeps the
        // original bytes, so a conversation that already captured an oversized
        // image self-heals on its next turn rather than staying poisoned.
        // Area unconstrained (the app chose the inline resolution); enforce
        // only the hard per-edge cap. `resize` returns the same array when
        // already within the cap, so the common case re-encodes nothing.
        Task {
          val raw = scala.util.Try(java.util.Base64.getDecoder.decode(base64)).getOrElse(Array.emptyByteArray)
          if (raw.isEmpty) Some(Provider.imageUnavailableMarker(altText))
          else {
            val r = _root_.sigil.image.ImageDownscale.resizeTyped(raw, maxPixels = 0L, mediaType = mediaType)
            if (r.bytes eq raw) Some(mc)
            else Some(MessageContent.ImageBytes(r.mediaType, java.util.Base64.getEncoder.encodeToString(r.bytes), altText, quality))
          }
        }
      case other => Task.pure(Some(other))
    }
    Task.sequence(call.messages.toList.map {
      case ProviderMessage.User(content) =>
        Task.sequence(content.toList.map(normalizeContent)).map(c => ProviderMessage.User(c.flatten.toVector))
      case other => Task.pure(other)
    }).map(messages => enforceImageEdgeCap(call.copy(messages = messages.toVector)))
  }

  /**
   * Sigil #400 — a provider may tighten its per-edge image cap based on how
   * many images the whole request carries. Anthropic drops the limit from
   * 8000 px to 2000 px once a request has more than
   * [[sigil.image.ImageDownscale.ManyImageThreshold]] images, and rejects the
   * ENTIRE request if any image exceeds it. The per-image downscale in
   * `normalizeStoredImages` can't see the request-wide count, so re-clamp here:
   * count the request's images, ask the provider for the effective per-edge cap
   * via [[imageEdgeCapFor]], and when it's tighter than the cap already applied,
   * shrink every inline image's long edge to it. A no-op for providers that
   * don't override `imageEdgeCapFor` (the default returns `MaxEdge`).
   */
  private def enforceImageEdgeCap(call: ProviderCall): ProviderCall = {
    def isImage(mc: MessageContent): Boolean = mc match {
      case _: MessageContent.ImageBytes => true
      case _: MessageContent.Image => true
      case _ => false
    }
    val imageCount = call.messages.iterator.collect {
      case ProviderMessage.User(content) => content.count(isImage)
    }.sum
    val cap = imageEdgeCapFor(imageCount)
    // The per-image pass already clamped to MaxEdge; only re-clamp when the
    // provider asks for something stricter.
    if (imageCount == 0 || cap >= _root_.sigil.image.ImageDownscale.MaxEdge) call
    else {
      val messages = call.messages.map {
        case ProviderMessage.User(content) =>
          ProviderMessage.User(content.map {
            case ib: MessageContent.ImageBytes =>
              val raw = java.util.Base64.getDecoder.decode(ib.base64)
              val r = _root_.sigil.image.ImageDownscale.resizeTyped(raw, maxPixels = 0L, mediaType = ib.mediaType, maxEdge = cap)
              if (r.bytes eq raw) ib
              else ib.copy(mediaType = r.mediaType, base64 = java.util.Base64.getEncoder.encodeToString(r.bytes))
            case other => other
          })
        case other => other
      }
      call.copy(messages = messages)
    }
  }

  /**
   * The maximum per-edge image size (px) this provider accepts for a request
   * carrying `imageCount` images. Default [[sigil.image.ImageDownscale.MaxEdge]]
   * regardless of count. Providers with a count-dependent limit (Anthropic's
   * 2000 px many-image cap) override. Sigil #400.
   */
  protected def imageEdgeCapFor(imageCount: Int): Int = _root_.sigil.image.ImageDownscale.MaxEdge

  /**
   * Sigil #393 — fetch an external image (via `Sigil.fetchExternalImageBytes`),
   * downscale it to the `quality` tier, and return base64 `ImageBytes`.
   * Process-cached by `url|quality` so we fetch/encode once and the bytes
   * stay STABLE across turns (provider prompt-caching of the prefix still
   * hits). `None` (drop) when the fetch failed.
   */
  private def materializeExternalImage(urlStr: String,
                                       quality: _root_.sigil.tool.ImageQuality,
                                       altText: Option[String]): Task[Option[MessageContent]] = {
    val key = s"$urlStr|${quality.toString}"
    Option(Provider.externalImageCache.get(key)) match {
      case Some((ct, b64)) => Task.pure(Some(MessageContent.ImageBytes(ct, b64, altText, quality)))
      case None =>
        sigil.fetchExternalImageBytes(urlStr).map {
          case Some((bytes, contentType)) if bytes.nonEmpty =>
            val ct = if (contentType.toLowerCase.startsWith("image/")) contentType else "image/png"
            val r = _root_.sigil.image.ImageDownscale.resizeTyped(bytes, quality.maxPixels, ct)
            val b64 = java.util.Base64.getEncoder.encodeToString(r.bytes)
            // Cache the POST-resize media type (#401 — a webp may have become PNG).
            Provider.cacheExternalImage(key, (r.mediaType, b64))
            Some(MessageContent.ImageBytes(r.mediaType, b64, altText, quality))
          case _ =>
            // Fetch failed or returned no bytes. A caption-carrying marker
            // (never cached — the fetch may succeed next turn) instead of a
            // silent drop: the caption tells the model the image named in
            // its context isn't visually present, and an empty image block
            // would 400 the whole request.
            Some(Provider.imageUnavailableMarker(altText))
        }
    }
  }

  /**
   * Extract a [[sigil.storage.StoredFile]] id from a URL of shape
   * `…/storage/<id>` — covers the default `sigil://storage/<id>` and
   * an app override to an `http(s)://host/storage/<id>` form. `None`
   * when the URL is not storage-shaped.
   */
  private def storedFileIdFrom(url: spice.net.URL): Option[Id[_root_.sigil.storage.StoredFile]] = {
    val marker = "/storage/"
    val s = url.toString
    val idx = s.indexOf(marker)
    if (idx < 0) None
    else {
      val id = s.substring(idx + marker.length).takeWhile(c => c != '/' && c != '?' && c != '#')
      if (id.isEmpty) None else Some(Id(id))
    }
  }

  /**
   * Resolve the ids on `TurnInput.criticalMemories` / `.memories` /
   * `.summaries` to full records via the DB. Ids that don't resolve are
   * dropped silently.
   */
  private def resolveReferences(turn: TurnInput): Task[ResolvedReferences] = {
    // Sigil bug #170 — collapse the prior per-id transaction fan into
    // two transactions total (one memories, one summaries). On every
    // turn the renderer resolves criticalMemories + memories + summaries;
    // pre-fix that was N + M + S transaction setup pairs sequentially.
    val memTask: Task[(List[Option[ContextMemory]], List[Option[ContextMemory]])] =
      if (turn.criticalMemories.isEmpty && turn.memories.isEmpty)
        Task.pure((Nil, Nil))
      else sigil.withDB(_.memories.transaction { tx =>
        for {
          crit <- Task.sequence(turn.criticalMemories.toList.map(tx.get))
          regular <- Task.sequence(turn.memories.toList.map(tx.get))
        } yield (crit, regular)
      })
    val sumTask: Task[List[Option[ContextSummary]]] =
      if (turn.summaries.isEmpty) Task.pure(Nil)
      else sigil.withDB(_.summaries.transaction { tx =>
        Task.sequence(turn.summaries.toList.map(tx.get))
      })
    for {
      (crit, regular) <- memTask
      summaries <- sumTask
    } yield ResolvedReferences(
      criticalMemories = crit.flatten.toVector,
      memories = regular.flatten.toVector,
      summaries = summaries.flatten.toVector
    )
  }

  /**
   * Compose the system prompt body from every contextually relevant
   * field on a [[ConversationRequest]]. Each section is omitted
   * when its source is empty. Every Model-visible field on `TurnInput`
   * MUST appear here. The companion
   * [[spec.LlamaCppRequestCoverageSpec]] is the regression guard.
   */
  /**
   * Compose the system prompt body, stable content first, volatile
   * content last.
   *
   * Section ordering is cache-aware: the prefix sections (tool
   * framing, mode + topic, instructions, roles, skills, pinned
   * directives, summaries, referenced content) change rarely across
   * turns within one conversation, so providers with prompt caching
   * (Anthropic's `cache_control` breakpoints, OpenAI / DeepSeek's
   * automatic prefix caches) can serve them from a cache hit. The
   * tail sections (retrieved non-critical memories, recently used
   * tools, repeated-call diagnostics, discovered capabilities,
   * per-turn budget warnings, the greeting hint) shift every turn —
   * placing them last keeps the cacheable prefix stable.
   */
  /**
   * Split system prompt return shape. The stable segment is the
   * provider's system prompt (part of the cacheable prefix); the
   * volatile segment rides behind the prefix as a trailing message
   * via [[ProviderCall.messagesWithVolatileTail]], or folds into a
   * per-request channel outside the transcript (OpenAI Responses'
   * `instructions`) via [[RenderedSystem.combined]].
   */
  protected case class RenderedSystem(stable: String, volatile: String) {

    /**
     * Single-string form used by providers that don't split.
     */
    def combined: String =
      if (volatile.isEmpty) stable
      else if (stable.isEmpty) volatile
      else stable + volatile
  }

  private def renderSystem(c: ConversationRequest,
                           resolved: ResolvedReferences): RenderedSystem = {
    val turn = c.turnInput
    val chain = c.chain
    val sb = new StringBuilder

    // ---- stable prefix (cacheable) ----

    if (c.tools.nonEmpty) {
      sb.append(
        "You communicate exclusively through tool calls. Plain text output is never delivered to the user — " +
          "always pick a tool.\n\n"
      )
      sb.append(
        "Tool calls go through the JSON `tool_calls` protocol the API negotiates with you. " +
          "Never emit `<tool_call>`, `<function=…>`, or similar XML/tag syntax inside `content` or any " +
          "other string field — those will NOT be parsed as tool calls; they will leak to the user as " +
          "text. If you want to make a follow-up tool call after responding, set `respond.endsTurn = false` " +
          "and issue the next call on the next iteration. A turn-ending respond describes what you DID, " +
          "not what you are about to do — content announcing work you have not done yet requires " +
          "`endsTurn = false`.\n\n"
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

    // The TOOLS discovery block teaches the discovery-first behaviour
    // generically — it names no specific tool, so tool-specific guidance
    // (how to query discovery, when to switch mode, how a reply renders)
    // travels with each tool's own description. The one gate left is
    // whether discovery itself is available: if `find_capability` isn't
    // in the roster (e.g. the active mode uses `ToolPolicy.None` or
    // `Exclusive` with a fixed set), telling the model to discover is a
    // dead loop, so strip the block.
    val findCapabilityAvailable =
      c.tools.exists(_.schema.name.value == "find_capability")
    val instr =
      if (!findCapabilityAvailable) c.instructions.renderWithoutTools
      else c.instructions.render
    if (instr.nonEmpty) sb.append("\n").append(instr).append("\n")

    // Roles render the agent's identity into the system prompt. A single
    // role is shown linearly (one description block); multiple roles get a
    // "You serve the following roles:" preamble + per-role enumeration so
    // the model handles multi-role identity explicitly even when each
    // role's description was written self-contained.
    c.roles match {
      case Nil => ()
      case List(single) =>
        if (single.description.nonEmpty)
          sb.append("\n").append(single.description).append("\n")
      case multi =>
        sb.append("\nYou serve the following roles:\n")
        multi.foreach { r =>
          sb.append(s"- ${r.name}")
          if (r.description.nonEmpty) sb.append(s" — ${r.description}")
          sb.append("\n")
        }
    }

    val skills = turn.aggregatedSkills(chain)
    val roleSkills = c.roles.flatMap(_.skill.toList)
    // Skill presence is state-coupled to the current mode, not coupled to a
    // ModeChange event having fired. A conversation created already in its
    // working mode never publishes a ModeChange, so `activeSkills[Mode]` is
    // empty — fold the current mode's skill in directly. `distinctBy(_.name)`
    // keeps this idempotent with the ModeChange-driven path (a switched-into
    // mode's slot is already in `activeSkills` under the same name).
    val modeSkill = c.currentMode.skill.toList
    val allSkills = (skills ++ roleSkills ++ modeSkill).distinctBy(_.name)
    if (allSkills.nonEmpty) {
      sb.append("\n== Active skills ==\n")
      allSkills.foreach { s =>
        sb.append(s"- ${s.name}\n")
        if (s.content.nonEmpty) sb.append(s.content).append("\n")
      }
    }

    if (resolved.criticalMemories.nonEmpty) {
      sb.append("\n== Pinned directives ==\n")
      resolved.criticalMemories.foreach(m => sb.append(s"- ${memoryRenderText(m)}\n"))
    }

    if (resolved.summaries.nonEmpty) {
      sb.append("\n== Earlier in this conversation (summarized) ==\n")
      resolved.summaries.foreach { s =>
        sb.append(s.text)
        if (s.coversEventIds.nonEmpty)
          sb.append(s""" [summarizes ${s.coversEventIds.size} earlier events — """ +
            s"""reload_content("${s._id.value}") to browse them and reload any in full]""")
        sb.append("\n")
      }
      // Reload convention (#316). Large tool results / messages may be
      // elided to a short summary + an id, and old history folded into
      // the summaries above. `reload_content("<id>")` reloads full content —
      // an event id returns that event (paginated); a summary id lists
      // the events it covers to drill into. Nothing is lost, only
      // deferred — reach for it when you need detail an entry only hints at.
      sb.append("\nWhen an entry shows `reload_content(\"<id>\")`, call it to reload the full content " +
        "it elided (an event id → that event; a summary id → the events it covers).\n")
    }

    if (turn.information.nonEmpty) {
      sb.append("\n== Referenced content (look up by id) ==\n")
      turn.information.foreach(i =>
        sb.append(s"- ${i.id.value} [${i.informationType.name}]: ${i.summary}\n"))
    }

    // ---- volatile tail (per-turn, excluded from the cacheable prefix) ----
    //
    // Everything below churns turn-to-turn, so it must ride BEHIND the
    // request's cacheable prefix (tools, system prompt, message history) —
    // full-history-replay providers append it as a trailing message via
    // [[ProviderCall.messagesWithVolatileTail]]; OpenAI Responses folds it
    // into its per-request `instructions`. Accumulate into its own builder
    // so [[RenderedSystem]] carries the two segments separately.
    val stable = sb.toString
    sb.setLength(0)

    if (resolved.memories.nonEmpty) {
      sb.append("\n== Memories ==\n")
      resolved.memories.foreach(m => sb.append(s"- ${memoryRenderText(m)}\n"))
    }

    val recentInvocations = chain.flatMap(id => turn.projectionFor(id).recentToolInvocations)
    val now = System.currentTimeMillis()
    val recent = recentInvocations
      .distinctBy(inv => (inv.toolName, inv.argsHash))
      .sortBy(-_.invokedAt.value)
      .take(Provider.RecentToolsPromptCap)
    if (recent.nonEmpty) {
      sb.append("\n== Recently used tools ==\n")
      // Agency must be unambiguous: this digest is a memory aid about
      // the assistant's OWN prior calls, not an external log. When
      // budget pressure has trimmed a call's full transaction from the
      // history, this line is the only remaining record — a model that
      // reads it as "the system did this" disowns its own actions and
      // hands the user work it already owns.
      sb.append("These are tool calls YOU (the assistant) made earlier in this conversation:\n")
      recent.foreach { inv =>
        val ago = Provider.humanizeAgo(now - inv.invokedAt.value)
        val previewSuffix = if (inv.argsPreview.nonEmpty) s" (${inv.argsPreview})" else ""
        sb.append(s"- ${inv.toolName.value}$previewSuffix -- $ago\n")
      }
    }
    // Surface every (toolName, argsHash) bucket that fires more than
    // once in the rolling window. Informational, not directive: the
    // count and args are stated as data; the agent decides what to do
    // with it. Earlier wording prescribed "try a different approach"
    // and listed options, which some models read as a hard stop
    // signal and respond to by abandoning the turn entirely. Stating
    // the fact and pointing at the prior outputs lets the agent
    // self-correct without an over-interpreted directive.
    val duplicateGroups = recentInvocations
      .groupBy(inv => (inv.toolName, inv.argsHash))
      .collect { case (key, occurrences) if occurrences.size > 1 => key -> occurrences }
      .toList
      .sortBy(-_._2.maxBy(_.invokedAt.value).invokedAt.value)
    if (duplicateGroups.nonEmpty) {
      sb.append("\n== Repeated tool calls ==\n")
      // State the explanation ONCE — repeating the full paragraph per group
      // (4+ identical ~350-char blocks observed live) bloats the prompt and
      // reads as noise. The per-group lines below carry only the facts.
      sb.append(
        // Sigil #397 — only point at `find_capability` when discovery is in
        // the roster. Under ToolPolicy.ActiveOnly/None/Exclusive it isn't, so
        // naming it tells the model to call a tool it doesn't have.
        if (findCapabilityAvailable)
          "Identical inputs yield identical results UNLESS your tool roster has changed since " +
            "(compare your current offered tools against what you remember). If a tool you used before isn't in " +
            "your offer now, re-call `find_capability` even with the same keywords; the framework's cache state " +
            "may have changed.\n"
        else
          "Identical inputs yield identical results. If you've already seen a tool's output for these exact " +
            "arguments, reuse it instead of calling again.\n"
      )
      val summary = duplicateGroups.map { case ((toolName, _), occurrences) =>
        val preview = occurrences.head.argsPreview
        val latest = occurrences.maxBy(_.invokedAt.value).invokedAt.value
        val ago = Provider.humanizeAgo(now - latest)
        val previewText = if (preview.nonEmpty) s" `$preview`" else ""
        sb.append(s"- `${toolName.value}` called ${occurrences.size}x with identical args (most recent $ago):$previewText\n")
        s"${toolName.value}=${occurrences.size}x"
      }.mkString(", ")
      // Mirror the prompt insertion to the backend log so forensics
      // questions ("did the duplicate-call detector fire?") resolve
      // via a log grep without having to dig into the wire-log
      // capture of the system prompt itself. The prompt insertion
      // above remains authoritative for agent behavior.
      scribe.info(s"Duplicate tool calls detected: $summary")
    }

    // Sigil #299 — the prompt's "Suggested tools" / "Capabilities
    // you've already discovered" sections can only honestly name
    // tools the wire ACTUALLY offers in `c.tools`. Pre-fix the
    // sections were rendered from raw projection / TurnContext
    // sources, which drifted from the merged wire roster: the
    // narrowing path (#286/#287), or `findTools.byName` returning
    // None on a discovered name, both produced rosters smaller than
    // what the prompt advertised. The model read "X is in your
    // roster" but the wire didn't carry X's schema, so the only
    // legal output became `respond` — driving the byte-identical-
    // respond loop the bug pattern names. Filtering both sections
    // by `wireToolNames` makes the prompt's claim accurate by
    // construction.
    val wireToolNames: Set[_root_.sigil.tool.ToolName] = c.tools.map(_.schema.name).toSet

    val suggestedTools = chain
      .flatMap(id => turn.projectionFor(id).suggestedTools)
      .distinct
      .filter(wireToolNames.contains)
    if (suggestedTools.nonEmpty) {
      sb.append("\n== Suggested tools ==\n")
      suggestedTools.foreach(t => sb.append(s"- ${t.value}\n"))
    }

    // Tools the agent has already discovered via `find_capability`
    // earlier in this agent loop. Source is the per-loop cache on
    // [[sigil.TurnContext]] (Bug #226) — empty on a fresh user turn,
    // populated as the agent issues find_capability calls during the
    // iteration loop, discarded when the loop ends. Cap keeps the
    // prompt bounded inside one loop. Per-match filter (#299) keeps
    // only names actually present in the wire roster — so the
    // "DIRECTIVE" sentence below isn't a lie when narrowing has
    // dropped a discovered tool from the offered set.
    val discovered = c.discoveredCapabilities.toList
      .sortBy(-_._2.lastSeen.value)
      .take(sigil.discoveredCapabilitiesPromptCap)
      .map { case (query, dc) => (query, dc.matches.filter(wireToolNames.contains)) }
      .filter { case (_, matches) => matches.nonEmpty }
    if (discovered.nonEmpty) {
      sb.append("\n== Capabilities you've already discovered (this turn) ==\n")
      discovered.foreach { case (query, matches) =>
        sb.append(s"- `find_capability($query)` → ${matches.map(_.value).mkString(", ")}\n")
      }
      sb.append(
        "DIRECTIVE: These tools are NOW in your roster — call them directly to complete the task. " +
          "Re-calling `find_capability` for the same query, or falling back to `respond` without first " +
          "calling the discovered action tool the user requested, is a protocol violation. If the user's " +
          "request maps to one of these tools, invoke it on THIS iteration.\n"
      )
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
      sb.append("state your role and offer to help. ")
      // Sigil #397 — drop the `find_capability` clause when discovery is off.
      if (findCapabilityAvailable)
        sb.append("Do NOT call `no_response` or `find_capability` on this turn; " +
          "the user expects a greeting, not silence or discovery.\n")
      else
        sb.append("Do NOT call `no_response` on this turn; the user expects a greeting, not silence.\n")
    }

    RenderedSystem(stable = stable, volatile = sb.toString)
  }

  /**
   * What to render for a memory in the system prompt's `Critical
   * directives` / `Memories` sections. Prefers `summary` when set so
   * apps that author tight directives keep per-turn cost down; the
   * full `fact` is always recoverable via the `lookup` tool.
   */
  private def memoryRenderText(m: ContextMemory): String =
    if (m.summary.trim.nonEmpty) m.summary else m.fact

  /**
   * Render a conversation's [[ContextFrame]]s into format-neutral
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
    // Bug #167 — track ALL unpaired tool_call ids, not just the most-
    // recent one. The previous `Option[String]` overwrote when two
    // ContextFrame.ToolCall entries arrived without an intervening
    // ToolResult, silently losing the first call from the pending
    // fallback and shipping it unpaired to the wire. OpenAI Responses
    // 400s on the next request ("No tool output found for function
    // call <id>"). LinkedHashSet preserves emission order so the
    // synthetic fallback output entries land in the same sequence the
    // calls were emitted.
    val pendingToolCallIds: scala.collection.mutable.LinkedHashSet[String] =
      scala.collection.mutable.LinkedHashSet.empty
    // Sigil #313 — when the invariant fails, build typed evidence rows
    // for each orphan so the agent loop's `handleError` chain can
    // hand a structured payload to the matching healing strategy
    // without parsing a log line. Maps wireId -> evidence.
    val pendingOrphans: scala.collection.mutable.LinkedHashMap[String, _root_.sigil.heal.CorruptionEvidence.MissingToolResult] =
      scala.collection.mutable.LinkedHashMap.empty

    // Forensic trail — every wire id that walked through the
    // ToolCall branch for this agent, and every wire id that
    // walked through the ToolResult branch (paired or orphan).
    // Surfaced in the dangling-tool_call error log so on the next
    // field occurrence we can see whether the orphan-settle path
    // missed a known invoke or whether the wireId itself drifted
    // between invoke and result.
    val invokesSeen: scala.collection.mutable.LinkedHashSet[String] =
      scala.collection.mutable.LinkedHashSet.empty
    val resultsSeen: scala.collection.mutable.LinkedHashSet[String] =
      scala.collection.mutable.LinkedHashSet.empty

    // Sigil #261 — the unified `ToolCall(state)` frame model carries
    // wire call id and tool result content in one frame; the prior
    // `wireCallIdByEvent` lookup map and `mergeAdjacentToolResults`
    // pre-pass exist only in pre-refactor history.
    //
    // Streaming-respond order normalization. The #210 merge below only
    // collapses a ToolCall-then-Text pair (the atomic path, where the
    // ToolInvoke is created before `RespondTool`'s reply Message). The
    // STREAMING path births the user-facing Message from ContentBlockDelta
    // BEFORE the respond ToolInvoke settles, so the frames arrive
    // Text-then-ToolCall and the merge misses — rendering two consecutive
    // assistant messages with duplicated content. Swap each adjacent
    // (agent Text, atomic-content ToolCall from the same agent) into
    // ToolCall-then-Text so the existing merge fires.
    val merged = {
      val arr = frames.toArray
      var k = 0
      while (k + 1 < arr.length)
        (arr(k), arr(k + 1)) match {
          case (t: ContextFrame.Text, tc: ContextFrame.ToolCall)
              if agentId.contains(t.participantId) && agentId.contains(tc.participantId)
                && atomicContentToolNames.contains(tc.toolName) =>
            arr(k) = tc
            arr(k + 1) = t
            k += 2
          case _ => k += 1
        }
      arr.toVector
    }

    // Walk with explicit index so we can consume the optional
    // adjacent `Text` frame that follows an atomic-content
    // `ToolCall` (the respond family's
    // `RespondTool.executeResult` Message). Sigil bug #210 —
    // pre-fix the two were emitted as separate consecutive
    // assistant messages, doubling per-call context cost and
    // reinforcing respond-loop patterns; merged here into a single
    // assistant message with both `content` and `tool_calls`
    // populated (OpenAI / Anthropic protocols permit both fields
    // on one assistant message).
    var i = 0
    while (i < merged.length) {
      merged(i) match {
        case ContextFrame.Text(content, participantId, _, _, images) =>
          if (agentId.contains(participantId)) out += ProviderMessage.Assistant(content)
          else if (images.isEmpty) out += ProviderMessage.User(content)
          else {
            // Sigil #405 — a user upload attached to the turn rides the vision
            // channel: lift each image URL into a MessageContent.Image block
            // (quality stripped + carried typed, like the ToolCall Complete
            // branch below). normalizeStoredImages inlines any
            // sigil://storage/<id> URL as downscaled bytes downstream. Keep the
            // text adjacent so the model maps caption -> image (#391).
            val imageBlocks: Vector[MessageContent] = images.map(u =>
              MessageContent.Image(
                _root_.sigil.tool.ImageQuality.strip(u),
                quality = _root_.sigil.tool.ImageQuality.fromUrl(u))).toVector
            val labeled =
              if (content.trim.nonEmpty) MessageContent.Text(content) +: imageBlocks
              else imageBlocks
            out += ProviderMessage.User(labeled)
          }
          i += 1

        case tc: ContextFrame.ToolCall if tc.internal =>
          // Sigil #385 — framework-internal synthetic diagnostics
          // (`_stall_detected`, `_refusal_challenge`, `_cap_reached`,
          // `_repeated_query_intercept`, …) must NOT render as an assistant
          // `tool_use`: the model treats it as a tool IT called and mimics it,
          // emitting a real call that fails with "Unknown tool" and loops
          // (observed 9× `_stall_detected` in one turn). Surface the directive
          // as an out-of-band `System` note — the same channel ModeChange uses
          // — so the model reads it as guidance, never as a callable tool. The
          // directive text is the frame's settled (folded) Complete content.
          tc.state match {
            case ToolCallState.Complete(content, _) if content.trim.nonEmpty =>
              out += ProviderMessage.System(content)
            case _ => ()
          }
          i += 1

        case tc: ContextFrame.ToolCall if agentId.contains(tc.participantId) =>
          // Sigil bug #167 r5 — when the upstream model emitted this
          // call, `wireCallId` carries the provider's wire identifier
          // (e.g. OpenAI's `call_<hash>`). Renderers prefer it so the
          // wire's `tool_call.id` / `function_call_output.call_id`
          // matches the provider's `previous_response_id` state.
          // Falls back to the framework's `Id[Event]` for synthetic
          // / framework-emitted calls (where there's no upstream
          // wire id to roundtrip).
          // Sigil #343 — normalize to the portable tool-call-id charset
          // (`[A-Za-z0-9_-]`, Anthropic's rule, which OpenAI / Cloudflare
          // also accept) so a conversation that minted ids on one provider
          // (Cloudflare/Kimi emits `functions.NAME:N` — `.` and `:` are
          // illegal for Anthropic) replays cleanly when a later turn routes
          // to another. Applied to the single `wireId` so the tool_use id,
          // its paired tool_result id, and the `invokesSeen` tracker all
          // stay consistent. Idempotent for already-portable ids.
          val wireId = Provider.portableToolCallId(tc.wireCallId.getOrElse(tc.callId.value))
          // Sigil bug #174 — record EVERY rendered ToolCall (not just those
          // with an upstream wireCallId) so the ToolResult branch can
          invokesSeen.add(wireId)
          val isAtomic = atomicContentToolNames.contains(tc.toolName)
          // Sigil bug #210 — if the next frame is a `Text` from the
          // same agent AND this ToolCall is an atomic-content tool
          // (`respond` family), the Text frame is the
          // user-facing artifact corresponding to this call's
          // `content` argument. Merge them into one assistant
          // message rather than emitting two adjacent ones.
          val mergedContent: String =
            if (isAtomic && i + 1 < merged.length) {
              merged(i + 1) match {
                case t: ContextFrame.Text if agentId.contains(t.participantId) =>
                  i += 1 // consume the Text frame; the outer loop bumps i again below
                  t.content
                case _ => ""
              }
            } else ""
          out += ProviderMessage.Assistant(
            content = mergedContent,
            toolCalls = List(ToolCallMessage(
              id = wireId,
              name = tc.toolName.value,
              argsJson = tc.argsJson
            ))
          )
          // Sigil #261 — the unified ToolCall(state) frame model emits
          // both wire messages from one frame: the Assistant(tool_use)
          // above, and the matching User(tool_result) immediately
          // after when `state` is `Complete`. Pair adjacency is
          // guaranteed by construction — the call and its result
          // live on one stateful `ToolInvoke` frame, so nothing else
          // can interleave between them.
          tc.state match {
            case ToolCallState.Complete(content, images) =>
              out += ProviderMessage.ToolResult(toolCallId = wireId, content = content)
              // Tool-result images ride as a follow-up user message so
              // the model actually sees them; normalizeStoredImages
              // inlines any internal-storage URLs as bytes downstream.
              if (images.nonEmpty) {
                // Sigil #382 — the producing tool's quality rides each
                // URL as `_q`; strip it and carry it typed so the
                // downscale/detail downstream uses it.
                val imageBlocks: Vector[MessageContent] = images.map(u =>
                  MessageContent.Image(
                    _root_.sigil.tool.ImageQuality.strip(u),
                    quality = _root_.sigil.tool.ImageQuality.fromUrl(u))).toVector
                // Sigil #391 — keep the caption ADJACENT to the image. Hoisting
                // a bare image to its own message (caption stranded back in the
                // tool_result) turned N image tools into a pile of anonymous
                // pictures the model couldn't map to a gid/label — reviving the
                // #280 re-view loop (observed: 32 uncaptioned image messages,
                // 0 in tool_results, agent re-viewing to re-anchor). One short
                // text block per image restores the image→caption mapping.
                val labeled =
                  if (content.trim.nonEmpty) MessageContent.Text(content) +: imageBlocks
                  else imageBlocks
                out += ProviderMessage.User(labeled)
              }
              resultsSeen.add(wireId)
            case ToolCallState.Active =>
              // No result yet — only happens for mid-turn debug
              // projections (the wire-request path always renders
              // after the agent's turn has settled). Track as pending
              // so the post-walk invariant check surfaces it loudly.
              pendingToolCallIds.add(wireId)
              pendingOrphans.update(
                wireId,
                _root_.sigil.heal.CorruptionEvidence.MissingToolResult(
                  // `sourceEventId` is definitionally the durable
                  // `ToolInvoke` row id (Sigil #314) — use it for
                  // `invokeId` so the heal's id-based resolution lands
                  // on the real row regardless of how `callId` was
                  // historically populated.
                  invokeId = tc.sourceEventId,
                  callId = wireId,
                  toolName = tc.toolName.value
                )
              )
          }
          i += 1

        case _: ContextFrame.ToolCall =>
          // ToolCall from someone else — skip (not rendered as a tool call for this agent).
          i += 1

        case ContextFrame.System(content, _, _) =>
          out += ProviderMessage.System(content)
          i += 1

        case ContextFrame.Reasoning(providerItemId, summary, encryptedContent, _, _, _) =>
          // Provider-internal reasoning state from a prior turn (bug #61).
          // Surfaced uniformly as a `ProviderMessage.Reasoning` entry; the
          // originating provider serializes it back onto the wire and other
          // providers drop it in their `renderInput`.
          out += ProviderMessage.Reasoning(providerItemId, summary, encryptedContent)
          i += 1
      }
    }

    // Invariant check — every tool call settles by construction:
    // atomic dispatch emits a [[ToolDelta]] folding output / outcome
    // / state onto the invoke; streaming `respond` emits one too;
    // and a provider stream that dies mid-args is settled by
    // `settleOrphanToolInvoke`. So the frame trail handed here
    // should never carry a dangling `ContextFrame.ToolCall` in
    // `Active` state. If one slips through, log loudly AND throw a
    // typed [[_root_.sigil.heal.BrokenHistoryException]] carrying the
    // structured corruption evidence so the agent loop's
    // `handleError` chain can dispatch to a matching
    // [[_root_.sigil.heal.HealingStrategy]] without parsing the log line.
    // Sigil #313.
    if (pendingToolCallIds.nonEmpty) {
      scribe.error(
        s"renderFrames: ${pendingToolCallIds.size} dangling tool_call(s) with no paired " +
          s"ToolResult in this turn's frame trail — wireIds=[${pendingToolCallIds.mkString(", ")}]. " +
          "Every tool call should be paired by construction; this indicates a framework bug. " +
          s"invokes seen: [${invokesSeen.mkString(", ")}]; results seen: [${resultsSeen.mkString(", ")}]."
      )
      throw _root_.sigil.heal.BrokenHistoryException(pendingOrphans.values.toList)
    }

    mergeAdjacentAssistantContent(out.result())
  }

  /**
   * Bug #74 — merge consecutive content-only `ProviderMessage.Assistant`
   * entries into a single message whose content is the run joined with
   * `\n\n`. OpenAI-compatible providers (incl. llama.cpp) reject two
   * adjacent `role=assistant` content messages with HTTP 400 ("Cannot
   * have 2 or more assistant messages at the end of the list"); the
   * canonical multi-respond turn (`endsTurn = false` followed by a
   * settling `endsTurn = true` respond) produces exactly that shape.
   *
   * Only content-only assistants merge — tool-call assistant messages
   * pass through untouched (they're paired with their `tool` result
   * messages and provider wire formats accept them).
   */
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
              content = prev.content + joiner + a.content,
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

}

object Provider {

  /**
   * Sigil #393 — whether a URL is a fetchable external image (http/https).
   * `data:` / `sigil://storage` and other schemes are handled elsewhere.
   */
  def isFetchableImageUrl(url: String): Boolean = {
    val l = url.toLowerCase
    l.startsWith("http://") || l.startsWith("https://")
  }

  /**
   * Sigil #393 — process-wide cache of downscaled external images, keyed by
   * `url|quality` → `(mediaType, base64)`. Fetch + downscale + encode happen
   * once; the stable base64 means the provider's prompt-caching of a fixed
   * prefix still hits across turns instead of busting on a re-encoded blob.
   * Bounded by a coarse size cap (clear-on-overflow) — image bytes are heavy
   * and the working set per conversation is small.
   */
  private val externalImageCache =
    new java.util.concurrent.ConcurrentHashMap[String, (String, String)]()
  private val MaxExternalImageCacheEntries = 256
  private[provider] def cacheExternalImage(key: String, value: (String, String)): Unit = {
    if (externalImageCache.size >= MaxExternalImageCacheEntries) externalImageCache.clear()
    externalImageCache.put(key, value)
    ()
  }

  /**
   * Sigil #343 — map a tool-call id to the portable charset every
   * provider accepts (`[A-Za-z0-9_-]`, Anthropic's `tool_use.id` rule).
   * Cloudflare/OpenAI mint `functions.<name>:<n>` ids whose `.` and `:`
   * Anthropic 400-rejects when a mixed-provider conversation later routes
   * to it. Deterministic (so a call id and its paired result id map to
   * the same value) and idempotent (already-portable ids pass through).
   */
  def portableToolCallId(id: String): String = {
    val mapped = id.replaceAll("[^A-Za-z0-9_-]", "-")
    if (mapped.isEmpty) "tool-call" else mapped
  }

  /**
   * Adaptive `max_tokens` cap applied when the paraphrase loop
   * detector has flagged this turn — bounds the damage when a
   * degenerate model is about to retry the same content. Default
   * 500 is informed by the live wire-log scenario where
   * `qwen3.6-35b` produced ~200k chars of repeated output before
   * hitting `max_tokens = 4096`. Smaller cap means the next
   * iteration sees the failure quickly and can self-correct via
   * the Failure-block diagnostic the orchestrator emits.
   */
  val ParaphraseLoopMaxOutputTokensCap: Int = 500

  /**
   * Sigil #387 — substring Anthropic returns when a model categorically
   * forbids forced `tool_choice` (`any`/`tool`/`required`). Claude Fable 5
   * / Mythos 5 (and any future model that accepts only `auto`/`none`)
   * reject a forced choice with HTTP 400
   * `"tool_choice forces tool use is not compatible with this model."`.
   * Matched case-insensitively.
   */
  val ForcedToolChoiceRejectionMarker: String = "tool_choice forces tool use is not compatible"

  /**
   * Sigil #387 — whether `t` (or any throwable in its cause chain) is a
   * model rejecting forced `tool_choice`. Drives the provider self-heal
   * that retries the same call once with `tool_choice` downgraded to
   * [[ToolChoice.Auto]]. Walks the cause chain and matches
   * [[ForcedToolChoiceRejectionMarker]] case-insensitively, so it fires
   * regardless of throwable type or wrapping.
   */
  def isForcedToolChoiceRejection(t: Throwable): Boolean =
    messageChainContains(t, ForcedToolChoiceRejectionMarker)

  /**
   * Sigil #395 — process-wide memo of models observed to reject a forced
   * `tool_choice`. The #387 self-heal is stateless per-call, so without this
   * EVERY forced-`tool_choice` call re-pays the 400-then-downgrade round-trip
   * — once per agent-loop iteration, i.e. many times within a single turn for
   * a tool-heavy run. OpenRouter can't gate this proactively (it wrongly lists
   * `tool_choice` in such models' `supported_parameters`), so the honest
   * signal is the directly-observed rejection: trip the memo on the first 400,
   * then demote forced choices to [[ToolChoice.Auto]] up front on every later
   * call. Model-keyed (rejection is a property of the model, not the provider
   * instance) and shared across all providers. In-memory: re-discovered once
   * per process after restart.
   */
  private val forcedToolChoiceRejectors: java.util.Set[String] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /**
   * Record that `modelId` rejected a forced `tool_choice`. Idempotent.
   */
  def recordForcedToolChoiceRejection(modelId: Id[Model]): Unit = {
    forcedToolChoiceRejectors.add(modelId.value)
    ()
  }

  /**
   * Whether `modelId` is known (this process) to reject a forced `tool_choice`.
   */
  def rejectsForcedToolChoice(modelId: Id[Model]): Boolean =
    forcedToolChoiceRejectors.contains(modelId.value)

  /**
   * Sigil #390 — substring an Anthropic 400 carries when a model rejects a
   * sampling parameter it no longer supports, e.g. Claude 5 generation
   * (Fable 5 / Mythos 5): `"`temperature` is deprecated for this model."`
   * (also `top_p`). Matched case-insensitively.
   */
  val DeprecatedSamplingParamMarker: String = "is deprecated for this model"

  /**
   * Sigil #390 — whether `t` (or any throwable in its cause chain) is a
   * model rejecting a deprecated sampling parameter. Drives the self-heal
   * that retries the same call once with `temperature` / `topP` stripped.
   */
  def isDeprecatedSamplingParam(t: Throwable): Boolean =
    messageChainContains(t, DeprecatedSamplingParamMarker)

  /**
   * Case-insensitively test whether `t` or any throwable in its cause chain
   * carries `needle` in its message — cycle-guarded, so it works regardless
   * of throwable type or wrapping.
   */
  /**
   * Vendor wire messages for a request that exceeds the model's context
   * window. The pre-flight gate estimates and sheds, but estimates can
   * under-count (markup-heavy content); when they do, the provider's 400
   * is the ground truth. The agent loop matches this to trigger
   * emergency compaction + retry instead of failing the turn.
   */
  private val ContextOverflowMarkers: List[String] = List(
    "prompt is too long", // Anthropic
    "context_length_exceeded", // OpenAI error code
    "maximum context length", // OpenAI message
    "exceeds the maximum number of tokens", // Google Gemini
    "input token count" // Google Gemini variant
  )

  /**
   * Text stand-in for an image whose bytes could not be resolved at
   * wire-render time (empty stored file, missing blob or row, failed
   * or empty external fetch, empty inline base64). Emitted in the
   * image block's place: an empty image block hard-400s the entire
   * request ("image cannot be empty") on every turn that re-renders
   * the frame, permanently bricking the conversation, while a raw
   * unreachable URL invites the same class of rejection. The caption
   * survives so the model knows the image named in its context isn't
   * visually present — the caption-preserving-eviction spirit.
   */
  private[provider] def imageUnavailableMarker(altText: Option[String]): MessageContent =
    MessageContent.Text(altText.filter(_.nonEmpty) match {
      case Some(alt) => s"[image unavailable: $alt]"
      case None => "[image unavailable]"
    })

  /**
   * Whether `t` (anywhere in its cause chain) is a provider
   * invalid-request rejection that is NOT a context overflow —
   * malformed content the model API refused (empty image source,
   * schema violation, …). Overflow has its own recovery path
   * ([[isContextOverflow]]); everything else in this class needs a
   * readable failure surface instead of a raw wire blob.
   */
  def isInvalidRequest(t: Throwable): Boolean =
    !isContextOverflow(t) && messageChainContains(t, "invalid_request_error")

  /**
   * Best-effort extraction of the concise human-readable message from
   * an invalid-request error body anywhere in `t`'s cause chain —
   * e.g. `messages.140.content.1.image.source.base64: image cannot
   * be empty` out of the full JSON envelope. `None` when no message
   * field is recoverable.
   */
  def invalidRequestDetail(t: Throwable): Option[String] = {
    val pattern = """"message"\s*:\s*"((?:[^"\\]|\\.)*)"""".r
    val seen = scala.collection.mutable.Set.empty[Throwable]
    @scala.annotation.tailrec
    def loop(cur: Throwable): Option[String] =
      if (cur == null || seen.contains(cur)) None
      else Option(cur.getMessage).flatMap(m => pattern.findFirstMatchIn(m).map(_.group(1))) match {
        case some @ Some(_) => some
        case None => seen += cur; loop(cur.getCause)
      }
    loop(t)
  }

  /**
   * Whether `t` (anywhere in its cause chain) is a context-window
   * overflow — either the framework's own pre-flight
   * [[RequestOverBudgetException]] or a vendor wire rejection.
   */
  def isContextOverflow(t: Throwable): Boolean = {
    val seen = scala.collection.mutable.Set.empty[Throwable]
    @scala.annotation.tailrec
    def loop(cur: Throwable): Boolean =
      if (cur == null || seen.contains(cur)) false
      else if (cur.isInstanceOf[RequestOverBudgetException]) true
      else if (
        Option(cur.getMessage).map(_.toLowerCase)
          .exists(m => ContextOverflowMarkers.exists(m.contains))
      ) true
      else { seen += cur; loop(cur.getCause) }
    loop(t)
  }

  private def messageChainContains(t: Throwable, needle: String): Boolean = {
    val lc = needle.toLowerCase
    val seen = scala.collection.mutable.Set.empty[Throwable]
    @scala.annotation.tailrec
    def loop(cur: Throwable): Boolean =
      if (cur == null || seen.contains(cur)) false
      else if (Option(cur.getMessage).exists(_.toLowerCase.contains(lc))) true
      else { seen += cur; loop(cur.getCause) }
    loop(t)
  }

  /**
   * Bug #132 — synthetic user message used when an agent-initiated
   * turn (greeting / scheduled / autonomous wake-up / worker spawn)
   * reaches the provider with no user message in the conversation
   * history. Every provider's API (OpenAI Responses, Anthropic
   * Messages, Google generateContent) requires non-empty input;
   * without this placeholder the request would be rejected with
   * HTTP 400 ("input must be provided"). The placeholder rides the
   * request only — never persists to the conversation event store.
   * The agent's emitted reply is what gets stored. Tagged so a
   * model that pattern-matches the trigger knows it's responding
   * to a framework-initiated turn rather than user input.
   */
  val AgentInitiatedTurnTrigger: String =
    "(agent-initiated turn — no user input yet; produce your greeting or scheduled output)"

  /**
   * Cap on entries emitted under the "Recently used tools" prompt
   * section. The full rolling window may carry more than this; the
   * renderer takes the most-recent distinct (toolName, argsHash)
   * subset so the prompt stays bounded and the agent still sees
   * what's pertinent.
   */
  val RecentToolsPromptCap: Int = 15

  /**
   * Render an elapsed-millis interval as a coarse "ago" string --
   * one of "just now", "moments ago", "recently", "earlier today",
   * "earlier this week", or "a while ago". The agent doesn't need
   * stopwatch precision for duplicate-call detection; categorical
   * recency is the load-bearing signal. Stable bucket strings also
   * keep the rendered system prompt deterministic across short
   * replay windows, which lets recorded fixtures match on the second
   * turn of a multi-turn run.
   */
  def humanizeAgo(elapsedMs: Long): String = {
    val seconds = math.max(0L, elapsedMs / 1000L)
    if (seconds < 60) "just now"
    else if (seconds < 600) "moments ago"
    else if (seconds < 3600) "recently"
    else if (seconds < 86400) "earlier today"
    else if (seconds < 604800) "earlier this week"
    else "a while ago"
  }
}
