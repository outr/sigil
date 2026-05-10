package sigil.event

import fabric.rw.*

/**
 * Bucket for how an agent should respond to a tool failure.
 * Filled in by the framework's auto-classifier when it constructs
 * the [[ErrorContext]] payload on a `ResponseContent.Failure`
 * block; tools with domain knowledge override the default.
 */
enum ErrorClassification derives RW {
  /** Bad arguments, missing path, permission-denied input — fix
    * the input and retry, or explain the input shape to the user. */
  case UserInputError

  /** Network blip, rate limit, transient upstream error — retrying
    * is likely to help. */
  case TransientError

  /** OOM, disk full, file too large — the operation needs different
    * resources, not a retry. */
  case ResourceExhausted

  /** Looks like a framework-side defect (NoSuchElement on internal
    * state, MalformedInput on a file we should have skipped, NPE on
    * framework data). The agent should surface to the user and ask
    * if they want this filed as feedback. */
  case FrameworkBug

  /** Upstream LLM/API returned an error — report verbatim to the
    * user. */
  case ProviderError

  /** Couldn't classify — explain what was tried and what the error
    * said; defer to the user on next steps. */
  case Unknown
}
