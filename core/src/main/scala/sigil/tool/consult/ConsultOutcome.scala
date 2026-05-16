package sigil.tool.consult

import sigil.provider.{StopReason, TokenUsage}

/**
 * Richer outcome shape for [[ConsultTool.invokeRich]]. Distinguishes
 * the four terminal states that the legacy `Option[I]` API collapsed
 * into "Some payload" vs. "None" — silent absorption of
 * `finish_reason: length`-truncated streams as `None` lost the
 * diagnostic signal exactly when it was most useful (sigil bug #197).
 *
 * Callers that care about which terminal state actually occurred —
 * routing classifiers, app-supplied work-type inference, anything
 * whose default flow on `None` is "shrug and use the fallback" — can
 * now pattern-match and surface a `FrameworkWorkflowNotice` on
 * `Truncated` / `Failed` instead of returning silently.
 */
sealed trait ConsultOutcome[+I]

object ConsultOutcome {

  /** Model emitted the expected tool call and the payload parsed
    * cleanly into `I`. */
  case class Parsed[I](value: I) extends ConsultOutcome[I]

  /** Stream completed with `finish_reason: stop` / `Complete` and
    * no matching tool_call. The model decided not to call the tool
    * (or called a different one) — a legitimate "no opinion" answer
    * that the caller should treat as "use the default." */
  case object NoOpinion extends ConsultOutcome[Nothing]

  /** Stream closed with `finish_reason: length` (i.e.
    * [[StopReason.MaxTokens]]) and no matching tool_call. The model
    * ran out of output budget before forming the structured answer.
    * Token counts (when surfaced by the provider's `usage` block)
    * let the caller decide whether to retry with a larger cap or
    * fall back. */
  case class Truncated(promptTokens: Option[Int],
                       completionTokens: Option[Int],
                       totalTokens: Option[Int]) extends ConsultOutcome[Nothing]

  /** Stream errored, parse failed, or the wire layer threw. */
  case class Failed(cause: Throwable) extends ConsultOutcome[Nothing]

  /** Build a `Truncated` from a possibly-missing [[TokenUsage]]. */
  def truncated(usage: Option[TokenUsage]): Truncated =
    Truncated(
      promptTokens     = usage.map(_.promptTokens),
      completionTokens = usage.map(_.completionTokens),
      totalTokens      = usage.map(_.totalTokens)
    )
}
