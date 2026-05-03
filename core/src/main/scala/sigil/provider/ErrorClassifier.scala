package sigil.provider

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
   * Default classifier — string-matches common transient signatures.
   * Apps with stronger typing (e.g. provider-specific exception
   * types) override.
   */
  val Default: ErrorClassifier = new ErrorClassifier {
    override def classify(throwable: Throwable): ErrorClassification = {
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
