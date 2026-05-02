package sigil.conversation.compression

import sigil.conversation.ContextFrame

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
 *   - Tool-pair stripping is data-driven via [[sigil.tool.Tool.resultTtl]]:
 *     [[StandardContextCurator]] resolves the elide-set per turn and
 *     passes it to `optimize`. Tools that declare `resultTtl = Some(0)`
 *     (e.g. `find_capability`, `change_mode`) get their call/result
 *     pairs dropped because the meaningful effect lives on a
 *     projection or `System` frame, not in the verbose ToolResults
 *     payload.
 *
 *   - [[stripStaleTools]]: explicit additional tool names whose
 *     call/result pairs should be collapsed regardless of their
 *     `resultTtl`. Useful for app code that wants to elide a tool
 *     it doesn't own (e.g. an experimental built-in whose author
 *     hasn't declared a TTL yet).
 */
case class StandardContextOptimizer(dropWhitespaceFrames: Boolean = true,
                                    dedupConsecutiveText: Boolean = true,
                                    stripStaleTools: Set[String] = Set.empty) extends ContextOptimizer {

  override def optimize(frames: Vector[ContextFrame],
                        elideToolNames: Set[String] = Set.empty,
                        currentTurnSource: Option[sigil.participant.ParticipantId] = None): Vector[ContextFrame] = {
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
    *   - For pairs **before the current turn** (events older than the
    *     most-recent Text frame from `currentTurnSource`): apply the
    *     legacy bug-#44 rule — keep the LAST pair per tool name so
    *     the agent has its one-turn-of-validity window after the
    *     turn that produced it; drop everything earlier.
    *   - For pairs **within the current turn** (events at or after
    *     that boundary): KEEP ALL of them. Bug #73 — eliding within-
    *     turn iterations of `find_capability` / `change_mode` etc.
    *     was hiding the agent's own working memory: the model
    *     called the tool, the framework deleted the call before the
    *     next agent loop iteration, the model "saw no prior call"
    *     and called the tool again, ad infinitum until
    *     `maxAgentIterations` fired. Within-turn calls now stay
    *     visible so the model can recognise it's already iterated.
    *
    * If `currentTurnSource` is `None` or no Text frame from that
    * participant exists in the vector, the legacy "keep latest per
    * name globally" rule applies (back-compat for callers without a
    * turn-boundary notion). */
  private def collapseToolPairs(frames: Vector[ContextFrame],
                                trim: Set[String],
                                currentTurnSource: Option[sigil.participant.ParticipantId]): Vector[ContextFrame] = {
    // Locate the boundary: index of the most-recent Text frame whose
    // participantId matches `currentTurnSource`. The boundary marks
    // the start of the current agent turn — frames at index >=
    // boundary are "within-turn" and MUST be preserved regardless of
    // resultTtl. -1 means no boundary (no source supplied or no
    // matching frame); falls through to legacy global behaviour.
    val boundaryIdx: Int = currentTurnSource match {
      case Some(src) =>
        frames.lastIndexWhere {
          case t: ContextFrame.Text if t.participantId == src => true
          case _                                              => false
        }
      case None => -1
    }

    // Within-turn: every ToolCall at or after the boundary is kept
    // regardless of trim membership. These represent the agent's
    // current iteration history — eliding them is the bug.
    val withinTurnCallIds: Set[lightdb.id.Id[sigil.event.Event]] =
      if (boundaryIdx < 0) Set.empty
      else frames.iterator.zipWithIndex.collect {
        case (tc: ContextFrame.ToolCall, i) if i >= boundaryIdx => tc.callId
      }.toSet

    // Across-turn elision: for each trim'd name, keep the LAST pair
    // among the BEFORE-boundary frames (the bug-#44 "give the agent
    // one shot at the recent discovery" semantics). When boundaryIdx
    // is -1, this scans the whole vector (legacy behaviour).
    val priorTurnFrames =
      if (boundaryIdx < 0) frames
      else frames.take(boundaryIdx)
    val priorKeepCallIds: Set[lightdb.id.Id[sigil.event.Event]] = trim.iterator.flatMap { name =>
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
      case tc: ContextFrame.ToolCall   => dropCallIds.contains(tc.callId)
      case tr: ContextFrame.ToolResult => dropCallIds.contains(tr.callId)
      case _                            => false
    }
  }

  private def pruneWhitespace(frames: Vector[ContextFrame]): Vector[ContextFrame] =
    frames.filter {
      case ContextFrame.Text(content, _, _, _) => content.trim.nonEmpty
      case _                                => true
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
