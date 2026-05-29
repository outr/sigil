package sigil.heal

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{Conversation, ContextFrame, ToolCallState}
import sigil.event.{Event, MessageRole, ToolInvoke, ToolOutcome}
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.TextToolOutput

import scala.util.matching.Regex

/**
 * Default healing strategy — settles every orphan `ToolInvoke` (one
 * whose paired typed result never landed) by folding a synthetic
 * marker output and `EventState.Complete` onto it.
 *
 * Matches:
 *  - `spice.http.client.StreamingHttpFailedException` 400 bodies
 *    that contain the OpenAI / Anthropic / Google phrasings naming a
 *    missing tool output / paired tool_result.
 *  - [[BrokenHistoryException]] from
 *    [[sigil.provider.Provider.renderFrames]], whose typed
 *    [[CorruptionEvidence.MissingToolResult]] rows name the orphan
 *    invokes directly.
 *
 * The synthetic result is durable — once published, the next agent
 * iteration renders the now-paired call/result and the wire shape is
 * legal again. The marker text makes the synthesis visible to the
 * model so it doesn't proceed as though the tool genuinely returned
 * an empty success.
 */
object MissingToolResultStrategy extends HealingStrategy {

  override val name: String = "MissingToolResultStrategy"

  /** OpenAI Responses: "No tool output found for function call call_XXX".
    * Tightened from `\S+` to `[A-Za-z0-9_\-]+` so JSON-body delimiters
    * (`"`, `}`, `,`, …) don't get captured as part of the id. */
  private val OpenAIMissing: Regex = """No tool output found for function call ([A-Za-z0-9_\-]+)""".r

  /** Anthropic's pairing-violation envelope: `tool_use ids found in
    * `assistant` … without `tool_result` blocks immediately after`. */
  private val AnthropicMissing: Regex =
    """tool_use ids? (?:found|"[^"]+")\s+.*?without\s+`?tool_result`?""".r

  /** Gemini's functionCall-without-functionResponse phrasing. */
  private val GeminiMissing: Regex =
    """functionCall\s+(?:without|missing)\s+functionResponse""".r

  /** The phrases above are all 400-status bodies — match by status
    * AND body content. Connection failures / 5xx / auth issues stay
    * outside our scope. */
  override def matches(error: Throwable): Boolean = error match {
    case _: BrokenHistoryException => true
    case e: spice.http.client.StreamingHttpFailedException if e.status == 400 =>
      val body = Option(e.body).getOrElse("")
      OpenAIMissing.findFirstIn(body).isDefined ||
        AnthropicMissing.findFirstIn(body).isDefined ||
        GeminiMissing.findFirstIn(body).isDefined
    case _ => false
  }

  override def detect(error: Throwable): List[CorruptionEvidence] = error match {
    case BrokenHistoryException(corruption) =>
      // The renderFrames path already typed the corruption — re-emit
      // only the MissingToolResult rows so apply() can settle them.
      corruption.collect { case m: CorruptionEvidence.MissingToolResult => m }
    case e: spice.http.client.StreamingHttpFailedException =>
      val body = Option(e.body).getOrElse("")
      val callIds: List[String] = (
        OpenAIMissing.findAllMatchIn(body).map(_.group(1)).toList ++
          // The Anthropic / Gemini regexes don't capture an id; the
          // detect pass on those paths returns an unlabelled marker
          // and apply() falls back to "settle every orphan invoke".
          (if (AnthropicMissing.findFirstIn(body).isDefined ||
                GeminiMissing.findFirstIn(body).isDefined) List("*") else Nil)
      ).distinct
      callIds.map(cid => CorruptionEvidence.MissingToolResult(
        invokeId = Id[Event](""),
        callId   = cid,
        toolName = ""
      ))
    case _ => Nil
  }

  override def apply(corruption: List[CorruptionEvidence],
                     convId: Id[Conversation],
                     host: Sigil): Task[HealResult] =
    host.withDB(_.eventsTransaction(convId)(_.list)).flatMap { events =>
      // Index the conversation's ToolInvoke events by callId (wire-
      // level) and by _id (framework-level) so we can resolve either
      // shape of evidence to the underlying durable row.
      val orphanInvokes: List[ToolInvoke] = events.iterator
        .collect { case t: ToolInvoke => t }
        .filter(_.conversationId == convId)
        .filter(_.state == EventState.Active)
        .toList

      // Pull the wire-callIds from the typed corruption evidence; an
      // empty list (or a `"*"` marker from a vendor-specific phrasing
      // we couldn't parse) means "settle every Active invoke we find".
      val requestedCallIds: Set[String] = corruption.collect {
        case m: CorruptionEvidence.MissingToolResult => m.callId
      }.toSet
      val wildcardMatch = requestedCallIds.isEmpty || requestedCallIds.contains("*")

      val targets: List[ToolInvoke] =
        if (wildcardMatch) orphanInvokes
        else orphanInvokes.filter { inv =>
          val wireMatch = inv.callId.exists(requestedCallIds.contains)
          val invokeMatch = requestedCallIds.contains(inv._id.value)
          wireMatch || invokeMatch
        }

      if (targets.isEmpty) {
        // Nothing to settle — the corruption evidence didn't resolve
        // to any persisted Active invoke. Either the heal already
        // ran (idempotent return) or the upstream's complaint
        // doesn't match what we have.
        Task.pure(HealResult(corrections = Nil, remainingIssues =
          if (corruption.isEmpty) Nil
          else List(s"No Active ToolInvoke matched ${corruption.size} corruption row(s) for conversation ${convId.value}")
        ))
      } else {
        val marker = "[orphan tool call — no recorded result; settled by framework-heal]"
        val publishes: Task[List[HealAction]] = Task.sequence(targets.map { invoke =>
          val delta = ToolDelta(
            target         = invoke._id,
            conversationId = convId,
            state          = Some(EventState.Complete),
            summary        = Some(marker),
            outcome        = Some(ToolOutcome.Failure(marker, recoverable = false)),
            output         = Some(TextToolOutput(marker)),
            error          = Some(marker)
          )
          host.publish(delta).map { _ =>
            HealAction(
              strategyName       = name,
              precondition       = s"orphan ToolInvoke ${invoke._id.value} (callId=${invoke.callId.getOrElse("-")}, tool=${invoke.toolName.value})",
              synthesisedEventId = invoke._id,
              synthesisedContent = marker,
              caveats            = Nil
            )
          }
        })
        publishes.map(HealResult(_, remainingIssues = Nil))
      }
    }
}
