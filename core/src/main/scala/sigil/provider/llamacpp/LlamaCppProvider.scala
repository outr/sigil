package sigil.provider.llamacpp

import fabric.*
import fabric.io.JsonFormatter
import fabric.rw.valueRW
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.provider.wire.OpenAIChatCompletions
import spice.http.{HttpMethod, HttpRequest}
import spice.http.client.HttpClient
import spice.http.content.StringContent
import spice.net.*

import scala.concurrent.duration.*

case class LlamaCppProvider(url: URL,
                            override val models: List[Model],
                            sigilRef: Sigil,
                            /**
                             * Per-read idle timeout for the SSE stream. spice's
                             * okhttp client wires this through to okhttp's
                             * `readTimeout`, which fires only when NO bytes
                             * arrive for the duration. Slow-but-working models
                             * (tokens trickling steadily) keep streaming
                             * indefinitely; genuinely stuck streams fail
                             * within `tokenIdleTimeout`. Default 120s.
                             */
                            tokenIdleTimeout: FiniteDuration = 120.seconds)
  extends Provider {
  override def `type`: ProviderType = ProviderType.LlamaCpp
  override val providerKey: String = LlamaCpp.Provider

  override protected def sigil: Sigil = sigilRef

  /**
   * Backend-exact tokenizer via llama.cpp's `POST /tokenize`. Falls
   * back to the heuristic on transient failures so a degraded backend
   * doesn't crash pre-flight. The heuristic under-counts JSON / chat-
   * template content by ~7-15% which let oversized requests slip past
   * the gate.
   */
  override val tokenizer: _root_.sigil.tokenize.Tokenizer =
    LlamaCppTokenizer(url, interceptor = sigilRef.wireInterceptor)

  /**
   * Capacity from llama.cpp's `total_slots`. Read at provider
   * construction; falls back to 1 when `/props` is unreachable
   * (single-slot is the safe assumption for an unknown deployment).
   */
  override val maxConcurrent: Int =
    LlamaCpp.fetchProps(url).map(_.map(_.totalSlots.toInt).getOrElse(1))
      .handleError(_ => Task.pure(1))
      .sync()

  /**
   * Llama.cpp's chat-completions wire is the OpenAI shape, with three
   * provider-specific twists expressed via [[OpenAIChatCompletions.Config]]:
   *
   *   - `preprocess` folds mid-array System messages into a `[system: ...]`
   *     prefix on the next non-system message (Qwen3.5 raises HTTP 500 on
   *     non-leading System), combines leading System content with
   *     `input.system`, and injects a placeholder user message when the
   *     turn has no user content (chat-template invariants).
   *   - `ReasoningPolicy.ChatTemplateEnableThinking` flips Qwen3's
   *     `chat_template_kwargs.enable_thinking` based on whether the
   *     caller set `GenerationSettings.effort`.
   *   - `inlineErrorThrows` surfaces 200-OK `data: {"error": {...}}`
   *     events as [[ProviderStreamException]] so the agent loop's
   *     failure-surface path emits a user-visible Failure Message.
   *   - `toolCallIdNormalizer` coerces non-conforming tool-call ids
   *     (e.g. Mistral NeMo's long form) into the 9-char alphanumeric
   *     the chat template expects on later turns.
   *
   * The full JSON-Schema (with `pattern` / `format` / numeric bounds)
   * passes through unchanged — llama.cpp's chat-completions endpoint
   * translates it into a GBNF grammar at generation time.
   */
  private val wireConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = LlamaCpp.Provider,
    providerName = "LlamaCpp",
    nonStrictSchemaTransform = identity,
    reasoningPolicy = OpenAIChatCompletions.ReasoningPolicy.ChatTemplateEnableThinking,
    multimodalPolicy = OpenAIChatCompletions.MultimodalPolicy.TextOnlyWithWarning,
    preprocess = preprocessForLlamaCpp,
    toolCallIdNormalizer = LlamaCppProvider.normalizeWireId,
    inlineErrorThrows = true
  )

  override def call(input: ProviderCall): Stream[ProviderEvent] =
    OpenAIChatCompletions.streamCall(input, sigilRef, url, identity, tokenIdleTimeout, wireConfig)

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    OpenAIChatCompletions.buildHttpRequest(input, sigilRef, url, identity, wireConfig)

  /**
   * Backend-exact pre-flight estimate in a single round-trip. Sends both
   * `messages` and `tools` to `POST /apply-template` so the rendered
   * prompt includes every inline tool-declaration block the chat template
   * emits. One subsequent `/tokenize` call against the whole rendered
   * string produces an exact estimate.
   *
   * Cost: 2 HTTP round-trips per pre-flight regardless of how many tools
   * the agent has. Falls back to the piecewise default on
   * `/apply-template` failure.
   */
  override protected def estimateRequest(call: ProviderCall): Int = {
    val fullBody = OpenAIChatCompletions.buildBody(call, sigilRef, wireConfig)
    val body = obj(
      "messages" -> fullBody.get("messages").getOrElse(arr()),
      "tools" -> fullBody.get("tools").getOrElse(arr())
    )
    val req = HttpRequest(
      method = HttpMethod.Post,
      url = url.withPath("/apply-template"),
      content = Some(StringContent(JsonFormatter.Compact(body), ContentType.`application/json`))
    )
      // `Connection: close` so the backend drops the TCP slot rather than
      // letting it sit warm in the pool and go stale before the next
      // pre-flight call.
      .withHeader("Connection", "close")
    // Hard timeout — stalled `/apply-template` calls can't block the
    // agent loop indefinitely. Single 100ms retry recovers from a
    // stale-pool transient without padding wall-clock by 2s.
    val task: Task[Int] = HttpClient.modify(_ => req)
      .interceptor(sigilRef.wireInterceptor)
      .timeout(estimateTimeout)
      .retryManager(spice.http.client.RetryManager.simple(retries = 1, delay = 100.millis, warnRetries = false))
      .call[Json].map { json =>
        val rendered = json.get("prompt").map(_.asString).getOrElse("")
        tokenizer.count(rendered)
      }.handleError(_ => Task.pure(super.estimateRequest(call)))
    task.sync()
  }

  /**
   * Hard wall-clock cap on `/apply-template`. Distinct from the
   * streaming chat-completions path (which uses the per-read idle
   * timeout).
   */
  protected def estimateTimeout: FiniteDuration = 5.seconds

  // ---- message preprocessing ----

  /**
   * Single placeholder user-role message used to satisfy chat-template
   * invariants (Qwen3.5's "No user query found") when a turn legitimately
   * has no user content (greet-on-join). The text is incidental — what
   * matters is that a user-role entry exists.
   */
  private val placeholderUserMessage: ProviderMessage =
    ProviderMessage.User(Vector(MessageContent.Text("(begin conversation)")))

  private def preprocessForLlamaCpp(call: ProviderCall): OpenAIChatCompletions.Preprocessed = {
    val (leadingSystem, nonLeading) = call.messages.span {
      case _: ProviderMessage.System => true
      case _ => false
    }
    val combinedSystem =
      (call.system +: leadingSystem.collect {
        case ProviderMessage.System(c) => c
      }).filter(_.nonEmpty).mkString("\n\n")
    val folded = foldMidArraySystems(nonLeading)
    val withUserAnchor =
      if (folded.exists { case _: ProviderMessage.User => true; case _ => false }) folded
      else placeholderUserMessage +: folded
    OpenAIChatCompletions.Preprocessed(combinedSystem, withUserAnchor)
  }

  /**
   * Walk the message array; collect mid-array System content into a
   * pending buffer; flush the buffer onto the next non-system message
   * (User / Assistant / ToolResult) as a `[system: ...]` prefix. If
   * the array ends with pending System content, fold it into the last
   * non-system message instead. Leading System messages pass through
   * unchanged.
   */
  private def foldMidArraySystems(messages: Vector[ProviderMessage]): Vector[ProviderMessage] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[ProviderMessage]
    val pending = scala.collection.mutable.ListBuffer.empty[String]
    var seenNonSystem = false
    messages.foreach {
      case ProviderMessage.System(content) if !seenNonSystem =>
        out += ProviderMessage.System(content)
      case ProviderMessage.System(content) =>
        pending += content
      case r: ProviderMessage.Reasoning =>
        // Reasoning state is foreign to llama.cpp; pass it through
        // untouched so renderMessages drops it. Don't treat it as a
        // textual carrier for pending system content.
        out += r
      case other =>
        seenNonSystem = true
        out += (if (pending.nonEmpty) prependSystem(pending.toList, other) else other)
        pending.clear()
    }
    if (pending.nonEmpty) {
      out.lastOption match {
        case Some(last) =>
          out(out.size - 1) = prependSystem(pending.toList, last)
        case None => ()
      }
    }
    out.toVector
  }

  private def prependSystem(systems: List[String], target: ProviderMessage): ProviderMessage = {
    val prefix = systems.map(s => s"[system: $s]").mkString("\n") + "\n"
    target match {
      case ProviderMessage.User(blocks) =>
        ProviderMessage.User(MessageContent.Text(prefix) +: blocks)
      case ProviderMessage.Assistant(content, toolCalls) =>
        ProviderMessage.Assistant(prefix + content, toolCalls)
      case ProviderMessage.ToolResult(id, content) =>
        ProviderMessage.ToolResult(id, prefix + content)
      case s: ProviderMessage.System => s // caller filters
      case r: ProviderMessage.Reasoning => r // caller filters
    }
  }
}

object LlamaCppProvider {

  /**
   * Construct a [[LlamaCppProvider]] and seed its model catalog
   * into [[sigil.cache.ModelRegistry]]. The cache merge is the
   * registry's contract for every provider that carries its own
   * model list at construction — the curator and other consumers
   * query the cache by id, so any model the provider can serve
   * must be visible there before a turn runs against it.
   */
  def apply(sigil: Sigil, url: URL): Task[LlamaCppProvider] =
    LlamaCpp.loadModels(url).flatMap { models =>
      sigil.cache.merge(models).map(_ => LlamaCppProvider(url, models, sigil))
    }

  /**
   * Map any tool-call id from the wire to a 9-char alphanumeric, applied
   * at parse time so the framework stores the canonical form throughout.
   * Some local-model chat templates (e.g. Mistral NeMo) hard-validate
   * this length on subsequent turns; coercing at parse means later
   * write-time emits the same form the chat template expects, and
   * ContextFrame projections / replays carry consistent ids. Already-
   * conformant ids (9 alphanumeric chars) pass through unchanged.
   */
  def normalizeWireId(id: String): String =
    if (id.length == 9 && id.forall(_.isLetterOrDigit)) id
    else {
      val hash = java.util.UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      hash.toString.replace("-", "").take(9)
    }
}
