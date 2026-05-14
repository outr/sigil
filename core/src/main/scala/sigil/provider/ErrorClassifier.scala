package sigil.provider

import sigil.AgentRunawayException

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

enum ErrorClassification {
  /** Same candidate, after `retryDelay`. The candidate's
    * `retryCount` caps how many times this fires. */
  case Retry

  /** Move to the next candidate in the chain. The current
    * candidate enters its `cooldown` before being eligible
    * again. */
  case Fallthrough

  /** Stop the strategy — surface the error to the caller. */
  case Fatal
}

object ErrorClassifier {

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
        // Provider-side degeneration (empty_budget_burn, malformed_tool_args,
        // inline server errors on 200-OK streams). Always fall through to
        // the next candidate — the current model has produced unusable
        // output; the same retry won't help.
        case _: ProviderStreamException =>
          return ErrorClassification.Fallthrough

        // Pre-flight capacity-gate timeout (configured per provider).
        // Fall through to the next candidate; this candidate is saturated.
        case _: CapacityAcquireTimeoutException =>
          return ErrorClassification.Fallthrough

        // Request was reshaped past every shed stage and still over budget.
        // Fatal: there's no candidate-level mitigation. The conversation
        // needs human / app-level intervention.
        case _: RequestOverBudgetException =>
          return ErrorClassification.Fatal

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
