package sigil.event

import fabric.rw.*

/**
 * Forensic snapshot of an upstream / framework error captured at the
 * agent-loop boundary. Persisted on
 * [[ConversationCorruptionDetected]] and [[HealingExhausted]] so the
 * audit trail carries enough payload to root-cause a future
 * recurrence from the event log alone.
 *
 * `body` is the raw response body when the error is a
 * [[spice.http.client.StreamingHttpFailedException]] — keeps the
 * upstream's error envelope (OpenAI / Anthropic / Google JSON
 * shape) verbatim. Truncated at the throw site (spice already caps
 * it), persisted as-is here.
 */
case class ErrorEvidence(errorClass: String,
                         message: String,
                         statusCode: Option[Int] = None,
                         body: Option[String] = None)
  derives RW

object ErrorEvidence {

  /**
   * Best-effort conversion from a `Throwable` to a structured
   * `ErrorEvidence`. Recognises spice's `StreamingHttpFailedException`
   * for the status / body fields; falls back to class name + message
   * for everything else.
   */
  def of(error: Throwable): ErrorEvidence = error match {
    case e: spice.http.client.StreamingHttpFailedException =>
      ErrorEvidence(
        errorClass = e.getClass.getName,
        message = Option(e.getMessage).getOrElse(""),
        statusCode = Some(e.status),
        body = Some(e.body)
      )
    case _ =>
      ErrorEvidence(
        errorClass = error.getClass.getName,
        message = Option(error.getMessage).getOrElse("")
      )
  }
}
