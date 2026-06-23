package sigil.provider

import sigil.AgentRunawayException
import sigil.heal.HealingStrategy

/**
 * Categorize a provider-call failure into "retry the same candidate",
 * "fall through to the next candidate", or "stop the strategy entirely."
 *
 * The default classifier treats common transient signatures (rate
 * limits, timeouts, 5xx, network errors) as `Retry`, malformed
 * requests / auth errors as `Fatal`, and everything else as `Fallthrough`.
 * Apps that need provider-specific handling supply their own classifier
 * to a strategy.
 */
trait ErrorClassifier {
  def classify(throwable: Throwable): ErrorClassification

  /**
   * Compose with another classifier. `this` wins on `Retry` / `Fatal`;
   * a `Fallthrough` defers to `other`. Apps chain a provider-specific
   * classifier on top of [[ErrorClassifier.Default]] so well-typed
   * exceptions get explicit handling and unknown errors fall back to
   * the standard HTTP-signature heuristics.
   *
   * Idiom: `myProviderClassifier.orElse(ErrorClassifier.Default)`.
   */
  def orElse(other: ErrorClassifier): ErrorClassifier = {
    val self = this
    new ErrorClassifier {
      override def classify(throwable: Throwable): ErrorClassification =
        self.classify(throwable) match {
          case ErrorClassification.Fallthrough => other.classify(throwable)
          case decided                         => decided
        }
    }
  }
}

sealed trait ErrorClassification

object ErrorClassification {
  /** Same candidate, after `retryDelay`. The candidate's
    * `retryCount` caps how many times this fires. */
  case object Retry extends ErrorClassification

  /** Move to the next candidate in the chain. The current
    * candidate enters its `cooldown` before being eligible
    * again. */
  case object Fallthrough extends ErrorClassification

  /** Stop the strategy — surface the error to the caller. */
  case object Fatal extends ErrorClassification

  /** Error matches a registered [[sigil.heal.HealingStrategy]]. The
    * agent loop's `handleError` chain branches here BEFORE the
    * Retry / Fallthrough / Fatal triage — runs the strategy
    * ([[sigil.heal.HealingMode.Recover]]) or records the corruption
    * and re-throws ([[sigil.heal.HealingMode.Strict]]).
    *
    * Distinguished from `Retry` because the heal repairs the
    * durable log BEFORE retrying — the agent's iteration runs
    * against a fixed history, not the same broken history that
    * caused the failure. */
  case class Healable(strategy: HealingStrategy) extends ErrorClassification
}

object ErrorClassifier {

  /** The throwable plus its transitive causes, depth-capped against cycles. */
  private def causeChain(t: Throwable): List[Throwable] = {
    val b = List.newBuilder[Throwable]
    var c = t
    var depth = 0
    while (c != null && depth < 16) { b += c; c = c.getCause; depth += 1 }
    b.result()
  }

  /**
   * Whether the failure (anywhere in its cause chain) is a connection-pool
   * acquire timeout — Netty's `FixedChannelPool` failing an acquire when the
   * per-host pool is saturated. Matched by a `java.util.concurrent.TimeoutException`
   * (the type Netty's `AcquireTimeoutAction.FAIL` raises) or the pool's
   * distinctive message (carrying Netty's "then" typo), so it's recognized even
   * when re-thrown wrapped at a fiber boundary. Sigil #386.
   */
  private[provider] def isConnectionAcquireTimeout(t: Throwable): Boolean =
    causeChain(t).exists { c =>
      c.isInstanceOf[java.util.concurrent.TimeoutException] ||
        Option(c.getMessage).exists(_.toLowerCase.contains("acquire operation took longer"))
    }

  /**
   * Classifier that walks [[HealingStrategy]] candidates and
   * returns [[ErrorClassification.Healable]] on the first match.
   * Falls through ([[ErrorClassification.Fallthrough]]) on no
   * match so callers can chain via
   * `healing(strategies).orElse(Default)`.
   *
   * Used at the agent-loop boundary — the agent loop reads
   * [[sigil.Sigil.healingStrategies]] and composes a healing
   * classifier on top of `Default` for that turn.
   */
  def healing(strategies: List[HealingStrategy]): ErrorClassifier = new ErrorClassifier {
    override def classify(throwable: Throwable): ErrorClassification =
      strategies.find(_.matches(throwable)) match {
        case Some(strategy) => ErrorClassification.Healable(strategy)
        case None           => ErrorClassification.Fallthrough
      }
  }

  /**
   * Default classifier — typed-exception dispatch first, then string
   * matching for common transient signatures.
   *
   * Apps with stronger provider-specific typing chain their own
   * classifier on top via [[ErrorClassifier.orElse]].
   */
  val Default: ErrorClassifier = new ErrorClassifier {
    override def classify(throwable: Throwable): ErrorClassification = {
      // Typed dispatch — recognises framework-thrown exception types
      // by class, not by message string. Robust against exception
      // chaining at fiber boundaries that can wrap the original
      // message and break substring heuristics. Sigil audit H5.
      throwable match {
        // Provider-side mid-stream failure. Default disposition is
        // `Fallthrough` (degenerate model output — no use re-running the
        // same candidate). Two flavors promote to `Retry`: typed
        // metadata names a transient upstream category
        // (`provider_unavailable`, `upstream_silent`, `rate_limited`,
        // `truncated_stream`) OR the wire-level status is a transient
        // 5xx. Both signal a sick upstream / dropped transport behind the
        // gateway; the same candidate typically clears. Sigil #360 — a
        // truncated stream (socket dropped mid-flight, no finish_reason /
        // [DONE]) is the textbook auto-retry case: re-run the same
        // candidate rather than downgrading the planner to a lesser one.
        case e: ProviderStreamException =>
          // `overloaded_error` is Anthropic's inline-SSE analogue of
          // `provider_unavailable` (a 200-OK stream carrying `event: error`,
          // so it arrives with status=None / code=0): the tier is briefly
          // saturated, not broken. Retry the same candidate; the live turn
          // path degrades to a lower tier once the per-candidate retries
          // exhaust (#397). 529 is the matching literal HTTP overload status.
          val transientErrorTypes: Set[String] =
            Set("provider_unavailable", "upstream_silent", "rate_limited", "truncated_stream", "overloaded_error")
          val typedTransient: Boolean =
            e.errorMetadata.flatMap(_.errorType).exists(transientErrorTypes.contains) ||
              transientErrorTypes.contains(e.typ)
          val transientStatus: Boolean =
            e.status.orElse(Option(e.code).filter(_ > 0)).exists(s => s == 429 || s == 502 || s == 503 || s == 504 || s == 529)
          if (typedTransient || transientStatus) return ErrorClassification.Retry
          else return ErrorClassification.Fallthrough

        // Pre-flight capacity-gate timeout (configured per provider).
        // Fall through to the next candidate; this candidate is saturated.
        case _: CapacityAcquireTimeoutException =>
          return ErrorClassification.Fallthrough

        // Netty FixedChannelPool acquire timeout — the spice client's per-host
        // connection pool was briefly saturated and a channel couldn't be
        // acquired within the timeout. Momentary resource contention; retry the
        // same candidate with backoff rather than surfacing a raw transport
        // error as the terminal result (Retry, not Fallthrough — a single-
        // provider app has nowhere to fall through to, so Fallthrough would be
        // terminal). Recognized anywhere in the cause chain since it's commonly
        // re-thrown wrapped at a fiber boundary. Sigil #386.
        case t if isConnectionAcquireTimeout(t) =>
          return ErrorClassification.Retry

        // Request was reshaped past every shed stage and still over budget.
        // Fatal: there's no candidate-level mitigation. The conversation
        // needs human / app-level intervention.
        case _: RequestOverBudgetException =>
          return ErrorClassification.Fatal

        // Sigil #283 — request exceeds the model's per-minute input-token
        // rate by itself. Retrying against the same ceiling can't succeed
        // (each attempt eats into the same per-minute budget). Fatal so
        // the framework's transient-retry loop doesn't burn the minute's
        // budget on guaranteed-failing attempts and the consumer surfaces
        // a meaningful error to the user.
        case _: RequestExceedsRateLimitException =>
          return ErrorClassification.Fatal

        // Sigil #283 — typed dispatch on spice's streaming-path HTTP
        // failure: classify by status code rather than substring-matching
        // the message. 429 / 5xx surfaces stay Retry; 4xx auth / bad-
        // request stays Fatal; 404 falls through to the next candidate
        // (route-elsewhere is typically what apps want for an absent
        // model id).
        case e: spice.http.client.StreamingHttpFailedException =>
          return e.status match {
            case 429 | 502 | 503 | 504       => ErrorClassification.Retry
            case 401 | 403 | 400             => ErrorClassification.Fatal
            case 404                         => ErrorClassification.Fallthrough
            case s if s >= 500 && s <= 599   => ErrorClassification.Retry
            case _                           => ErrorClassification.Fallthrough
          }

        // Run-out — agent loop exhausted iterations. Fatal so the user
        // sees the cap rather than infinite candidate cycling.
        case _: AgentRunawayException =>
          return ErrorClassification.Fatal

        case _ => ()
      }

      val msg = Option(throwable.getMessage).getOrElse("").toLowerCase
      val cls = throwable.getClass.getSimpleName.toLowerCase
      if (msg.contains("rate limit") || msg.contains("429") || msg.contains("too many requests")) ErrorClassification.Retry
      else if (cls.contains("timeout") || msg.contains("timeout") || msg.contains("read timed out")) ErrorClassification.Retry
      else if (msg.contains("503") || msg.contains("service unavailable") || msg.contains("overloaded")) ErrorClassification.Retry
      else if (msg.contains("502") || msg.contains("bad gateway")) ErrorClassification.Retry
      else if (msg.contains("connection reset") || msg.contains("connection refused")) ErrorClassification.Retry
      else if (msg.contains("401") || msg.contains("unauthorized") || msg.contains("invalid api key")) ErrorClassification.Fatal
      else if (msg.contains("403") || msg.contains("forbidden")) ErrorClassification.Fatal
      else if (msg.contains("400") || msg.contains("bad request") || msg.contains("invalid request")) ErrorClassification.Fatal
      else if (msg.contains("404") || msg.contains("not found")) ErrorClassification.Fallthrough
      else ErrorClassification.Fallthrough
    }
  }
}
