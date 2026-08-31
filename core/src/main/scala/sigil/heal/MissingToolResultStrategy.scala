package sigil.heal

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{Conversation, ContextFrame, ToolCallState}
import sigil.event.{Event, ToolInvoke, ToolOutcome}
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.{TextToolOutput, ToolOutput}

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

  /**
   * OpenAI Responses: "No tool output found for function call call_XXX".
   * Tightened from `\S+` to `[A-Za-z0-9_\-]+` so JSON-body delimiters
   * (`"`, `}`, `,`, …) don't get captured as part of the id.
   */
  private val OpenAIMissing: Regex = """No tool output found for function call ([A-Za-z0-9_\-]+)""".r

  /**
   * Anthropic's pairing-violation envelope: `tool_use ids found in
   * `assistant` … without `tool_result` blocks immediately after`.
   */
  private val AnthropicMissing: Regex =
    """tool_use ids? (?:found|"[^"]+")\s+.*?without\s+`?tool_result`?""".r

  /**
   * Gemini's functionCall-without-functionResponse phrasing.
   */
  private val GeminiMissing: Regex =
    """functionCall\s+(?:without|missing)\s+functionResponse""".r

  /**
   * The phrases above are all 400-status bodies — match by status
   * AND body content. Connection failures / 5xx / auth issues stay
   * outside our scope.
   */
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
          (if (
             AnthropicMissing.findFirstIn(body).isDefined ||
             GeminiMissing.findFirstIn(body).isDefined
           ) List("*")
           else Nil)
      ).distinct
      callIds.map(cid =>
        CorruptionEvidence.MissingToolResult(
          invokeId = Id[Event](""),
          callId = cid,
          toolName = ""
        ))
    case _ => Nil
  }

  override def apply(corruption: List[CorruptionEvidence],
                     convId: Id[Conversation],
                     host: Sigil): Task[HealResult] =
    host.withDB(_.conversationEventsConsistent(convId)).flatMap { events =>
      // Sigil #314 — an orphan is a `ToolInvoke` whose paired result
      // never landed: its inlined `ContextFrame.ToolCall` is still
      // `Active`, or (defensively, for legacy rows that carry no
      // inlined frame) its `outcome` / `output` are still `Pending`.
      // This is the SAME notion of "orphan" the detector keys off —
      // `renderFrames` walks the frame's `ToolCallState`, NOT the
      // event's `EventState` — so heal and detector agree on what's
      // broken. The prior `EventState.Active` predicate matched ZERO
      // real orphans: a bricked invoke completes its EVENT lifecycle
      // (`EventState.Complete`) without ever recording a result, so
      // it is Complete at the event level but Active at the frame
      // level.
      def isOrphan(inv: ToolInvoke): Boolean = {
        val frameActive = inv.contextFrame.collect {
          case tc: ContextFrame.ToolCall => tc.state
        }.contains(ToolCallState.Active)
        val noResult = inv.outcome == ToolOutcome.Pending && inv.output == ToolOutput.Pending
        frameActive || noResult
      }
      val orphanInvokes: List[ToolInvoke] = events.iterator
        .collect { case t: ToolInvoke => t }
        .filter(_.conversationId == convId)
        .filter(isOrphan)
        .toList

      // Sigil #314 — the evidence rows name an orphan by TWO id forms:
      // `invokeId` (the durable `ToolInvoke` event id) and `callId`
      // (the wire `call_<hash>` the provider remembers). Those live
      // in different keyspaces from each other AND from what the
      // persisted invoke carries (`_id`, `callId`, the inlined
      // frame's `callId` / `wireCallId`). Match an orphan if ANY
      // identifier it is known by appears in the union of requested
      // ids, so a keyspace mismatch on any single field can't
      // silently drop a real orphan. An empty requested set (or an
      // explicit `"*"` from a vendor phrasing we couldn't parse a
      // concrete id out of) means "settle every orphan we find".
      val requestedIds: Set[String] = corruption.collect {
        case m: CorruptionEvidence.MissingToolResult => List(m.invokeId.value, m.callId)
      }.flatten.filter(_.nonEmpty).toSet
      val wildcardMatch = requestedIds.isEmpty || requestedIds.contains("*")

      def identifiersOf(inv: ToolInvoke): Set[String] = {
        val frameIds = inv.contextFrame.collect {
          case tc: ContextFrame.ToolCall => Set(tc.callId.value) ++ tc.wireCallId.toSet
        }.getOrElse(Set.empty)
        Set(inv._id.value) ++ inv.callId.toSet ++ frameIds
      }

      val targets: List[ToolInvoke] =
        if (wildcardMatch) orphanInvokes
        else orphanInvokes.filter(inv => identifiersOf(inv).exists(requestedIds.contains))

      if (targets.isEmpty) {
        // Nothing to settle — the corruption evidence didn't resolve
        // to any persisted orphan invoke. Either the heal already
        // ran (idempotent return) or the upstream's complaint
        // doesn't match what we have. A non-empty `remainingIssues`
        // here signals a no-op heal to the framework, which treats it
        // as a FAILED heal rather than publishing a misleading
        // `ConversationHealed` with zero corrections (Sigil #314).
        Task.pure(HealResult(
          corrections = Nil,
          remainingIssues =
            if (corruption.isEmpty) Nil
            else List(s"No orphan ToolInvoke matched ${corruption.size} corruption row(s) for conversation ${convId.value}")
        ))
      } else {
        val marker = "[orphan tool call — no recorded result; settled by framework-heal]"
        val publishes: Task[List[HealAction]] = Task.sequence(targets.map { invoke =>
          val delta = ToolDelta(
            target = invoke._id,
            conversationId = convId,
            state = Some(EventState.Complete),
            summary = Some(marker),
            outcome = Some(ToolOutcome.Failure(marker, recoverable = false)),
            output = Some(TextToolOutput(marker)),
            error = Some(marker)
          )
          host.publish(delta).map { _ =>
            HealAction(
              strategyName = name,
              precondition =
                s"orphan ToolInvoke ${invoke._id.value} (callId=${invoke.callId.getOrElse("-")}, tool=${invoke.toolName.value})",
              synthesisedEventId = invoke._id,
              synthesisedContent = marker,
              caveats = Nil
            )
          }
        })
        publishes.map(HealResult(_, remainingIssues = Nil))
      }
    }
}
