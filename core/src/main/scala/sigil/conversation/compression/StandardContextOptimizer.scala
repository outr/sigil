package sigil.conversation.compression

import lightdb.id.Id
import sigil.conversation.ContextFrame
import sigil.event.Event
import sigil.participant.{AgentParticipantId, ParticipantId}

/**
 * Default [[ContextOptimizer]] — a bundle of individually-toggleable
 * cleanup rules. Every rule is conservative: removing a frame means
 * the information it carried is ALSO conveyed elsewhere (on a
 * participant projection, in a `System` frame, etc.), so no
 * information is lost — only redundancy.
 *
 * Rules and rationale:
 *
 *   - [[dropWhitespaceFrames]]: strip Text frames whose content is
 *     empty or whitespace. These are a retry / delta-flush artifact
 *     with no semantic value.
 *
 *   - [[dedupConsecutiveText]]: collapse back-to-back Text frames from
 *     the same participant with identical content (UI retries,
 *     duplicate streaming flushes).
 *
 *   - Tool-pair stripping is data-driven from tool freshness:
 *     [[StandardContextCurator]] resolves the elide-set per turn and
 *     passes it to `optimize`. Tools that declare `Freshness.Volatile` reads
 *     (e.g. `find_capability`, `change_mode`) get their call/result
 *     pairs dropped because the meaningful effect lives on a
 *     projection or `System` frame, not in the verbose settled
 *     tool-call payload.
 *
 *   - [[stripStaleTools]]: explicit additional tool names whose
 *     call/result pairs should be collapsed regardless of their
 *     freshness. Useful for app code that wants to elide a tool
 *     it doesn't own (e.g. an experimental built-in whose author
 *     hasn't declared a TTL yet).
 */
case class StandardContextOptimizer(dropWhitespaceFrames: Boolean = true,
                                    dedupConsecutiveText: Boolean = true,
                                    stripStaleTools: Set[String] = Set.empty) extends ContextOptimizer {

  override def optimize(frames: Vector[ContextFrame],
                        elideToolNames: Set[String] = Set.empty,
                        currentTurnSource: Option[ParticipantId] = None): Vector[ContextFrame] = {
    val trim = elideToolNames ++ stripStaleTools
    var out = frames
    if (trim.nonEmpty) out = collapseToolPairs(out, trim, currentTurnSource)
    if (dropWhitespaceFrames) out = pruneWhitespace(out)
    if (dedupConsecutiveText) out = dedupRun(out)
    out
  }

  /** Drop earlier ToolCall+ToolResult pairs for every tool name in
    * `trim` according to the following two-tier rule:
    *
    *   - For pairs **before the current turn**: keep the LAST pair
    *     per tool name so the agent has its one-turn-of-validity
    *     window after the turn that produced it; drop everything
    *     earlier.
    *   - For pairs **within the current turn**: KEEP ALL of them.
    *     Eliding within-turn iterations hides the agent's own working
    *     memory: the model calls the tool, the framework deletes the
    *     call before the next loop iteration, the model "sees no
    *     prior call" and calls again, ad infinitum until
    *     `maxAgentIterations` fires. A parallel batch is the acute
    *     case — its siblings share one tool name, so collapsing by
    *     name destroys every result but one and the model rationally
    *     re-asks for the rest.
    *
    * The boundary is the most-recent Text frame that OPENS a turn:
    * one authored by a non-agent participant. `currentTurnSource`
    * narrows that to a specific participant when it names one; an
    * agent id never does, because the agent's own prose is emitted
    * mid-turn and would put the turn's own tool pairs on the
    * droppable side of the line. When no turn-opening frame exists at
    * all, every frame counts as current-turn: a pair that cannot be
    * proven stale is kept. */
  private def collapseToolPairs(frames: Vector[ContextFrame],
                                trim: Set[String],
                                currentTurnSource: Option[ParticipantId]): Vector[ContextFrame] = {
    val boundaryIdx: Int = frames.lastIndexWhere {
      case t: ContextFrame.Text =>
        !t.participantId.isInstanceOf[AgentParticipantId] &&
          currentTurnSource.forall(src => src.isInstanceOf[AgentParticipantId] || src == t.participantId)
      case _ => false
    }

    // Within-turn: every ToolCall at or after the boundary is kept
    // regardless of trim membership. These represent the agent's
    // current iteration history — eliding them is the bug.
    val withinTurnCallIds: Set[Id[Event]] =
      if (boundaryIdx < 0) frames.iterator.collect { case tc: ContextFrame.ToolCall => tc.callId }.toSet
      else frames.iterator.zipWithIndex.collect {
        case (tc: ContextFrame.ToolCall, i) if i >= boundaryIdx => tc.callId
      }.toSet

    // Across-turn elision: for each trim'd name, keep the LAST pair
    // among the BEFORE-boundary frames.
    val priorTurnFrames =
      if (boundaryIdx < 0) Vector.empty[ContextFrame]
      else frames.take(boundaryIdx)
    val priorKeepCallIds: Set[Id[Event]] = trim.iterator.flatMap { name =>
      priorTurnFrames.reverseIterator.collectFirst {
        case tc: ContextFrame.ToolCall if tc.toolName.value == name => tc.callId
      }
    }.toSet

    val keepCallIds = priorKeepCallIds ++ withinTurnCallIds

    val dropCallIds = frames.iterator.collect {
      case tc: ContextFrame.ToolCall
        if trim.contains(tc.toolName.value) && !keepCallIds.contains(tc.callId) => tc.callId
    }.toSet
    if (dropCallIds.isEmpty) frames
    else frames.filterNot {
      // Sigil #261 — unified ToolCall(state) frame carries both
      // call AND result content; one filter check drops the whole
      // transaction (previously two: ToolCall + ToolResult).
      case tc: ContextFrame.ToolCall => dropCallIds.contains(tc.callId)
      case _                         => false
    }
  }

  private def pruneWhitespace(frames: Vector[ContextFrame]): Vector[ContextFrame] =
    frames.filter {
      case ContextFrame.Text(content, _, _, _, _) => content.trim.nonEmpty
      case _                                    => true
    }

  private def dedupRun(frames: Vector[ContextFrame]): Vector[ContextFrame] = {
    val out = Vector.newBuilder[ContextFrame]
    var prev: Option[ContextFrame.Text] = None
    frames.foreach {
      case t: ContextFrame.Text =>
        val isDup = prev.exists(p => p.content == t.content && p.participantId == t.participantId)
        if (!isDup) {
          out += t
          prev = Some(t)
        }
      case other =>
        out += other
        prev = None
    }
    out.result()
  }
}
