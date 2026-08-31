package sigil.event

import fabric.rw.*

/**
 * Structured failure payload attached to `MessageDisposition.Failure`
 * so the agent can act on a tool failure intelligently — distinguish
 * "I called this wrong" from "the framework broke" from "the network
 * blipped".
 *
 *   - `classification` — bucket the agent reads first to pick a
 *     response shape (retry / fix args / report to user / file bug).
 *   - `exceptionClass` — fully-qualified Java class name when known
 *     (e.g. `java.nio.charset.MalformedInputException`). Helps the
 *     agent recognise patterns and aids triage when forwarded as
 *     feedback.
 *   - `stackHead` — top 5–10 frames as text. Enough for triage; not
 *     the full ~80-line JVM stack.
 *   - `suggestion` — tool-specified hint when one applies ("retry
 *     with a smaller input", "file may be binary"). Optional.
 *   - `frameworkBugLikelihood` — 0.0 to 1.0 heuristic; the
 *     auto-classifier sets this based on the exception shape.
 *     `FrameworkBug` defaults to 0.85+; `UserInputError` defaults
 *     to 0.0.
 */
case class ErrorContext(classification: ErrorClassification,
                        exceptionClass: Option[String],
                        message: String,
                        stackHead: List[String] = Nil,
                        suggestion: Option[String] = None,
                        frameworkBugLikelihood: Double = 0.5)
  derives RW

object ErrorContext {

  /**
   * Auto-classify a Throwable into an ErrorContext. Apps and tools
   * with domain knowledge override the framework's classification
   * by constructing the ErrorContext directly.
   */
  def classify(t: Throwable): ErrorContext = {
    val cls = Option(t.getClass.getName)
    val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
    val head = stackHead(t)
    val name = cls.getOrElse("")

    if (
      name.contains("MalformedInputException") ||
      name.contains("UnmappableCharacterException")
    ) {
      ErrorContext(
        classification = ErrorClassification.FrameworkBug,
        exceptionClass = cls,
        message = msg,
        stackHead = head,
        suggestion = Some("file may be binary or encoded non-UTF-8 — the tool should have skipped or decoded leniently"),
        frameworkBugLikelihood = 0.9
      )
    } else if (name.contains("NoSuchElementException") || name.contains("NullPointerException")) {
      ErrorContext(
        classification = ErrorClassification.FrameworkBug,
        exceptionClass = cls,
        message = msg,
        stackHead = head,
        suggestion = None,
        frameworkBugLikelihood = 0.85
      )
    } else if (
      name.contains("NoSuchFileException") ||
      name.contains("FileNotFoundException") ||
      name.contains("IllegalArgumentException")
    ) {
      ErrorContext(
        classification = ErrorClassification.UserInputError,
        exceptionClass = cls,
        message = msg,
        stackHead = head,
        suggestion = None,
        frameworkBugLikelihood = 0.0
      )
    } else if (
      name.contains("AccessDeniedException") ||
      name.contains("SecurityException")
    ) {
      ErrorContext(
        classification = ErrorClassification.UserInputError,
        exceptionClass = cls,
        message = msg,
        stackHead = head,
        suggestion = Some("permission denied — try a path the agent's chain has access to"),
        frameworkBugLikelihood = 0.0
      )
    } else if (
      name.contains("SocketTimeoutException") ||
      name.contains("ConnectException") ||
      name.contains("UnknownHostException") ||
      name.contains("TimeoutException")
    ) {
      ErrorContext(
        classification = ErrorClassification.TransientError,
        exceptionClass = cls,
        message = msg,
        stackHead = head,
        suggestion = Some("retry — likely transient"),
        frameworkBugLikelihood = 0.1
      )
    } else if (
      name.contains("OutOfMemoryError") ||
      name.contains("StackOverflowError")
    ) {
      ErrorContext(
        classification = ErrorClassification.ResourceExhausted,
        exceptionClass = cls,
        message = msg,
        stackHead = head,
        suggestion = None,
        frameworkBugLikelihood = 0.3
      )
    } else {
      ErrorContext(
        classification = ErrorClassification.Unknown,
        exceptionClass = cls,
        message = msg,
        stackHead = head,
        suggestion = None,
        frameworkBugLikelihood = 0.5
      )
    }
  }

  private def stackHead(t: Throwable): List[String] =
    Option(t.getStackTrace).map(_.iterator.take(8).map(_.toString).toList).getOrElse(Nil)
}
