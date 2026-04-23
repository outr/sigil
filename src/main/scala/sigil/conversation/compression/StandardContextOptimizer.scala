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
 *   - [[stripStaleFindCapabilityPairs]]: drop `find_capability`
 *     tool-call / tool-result pairs. The result's effect (new
 *     `suggestedTools` on the caller's [[sigil.conversation.ParticipantProjection]])
 *     is already rendered into the provider's system prompt as the
 *     "Suggested tools" section, so the pair is noise.
 *
 *   - [[stripStaleChangeModePairs]]: drop `change_mode` tool-call /
 *     tool-result pairs. The mode transition is rendered independently
 *     as a `System` frame (from the paired `ModeChange` event) and on
 *     the system prompt's "Current mode" line, so the pair is noise.
 *
 *   - [[stripStaleTools]]: additional tool names (by
 *     [[sigil.tool.ToolName]] value) whose call/result pairs should be
 *     collapsed — same mechanism as the built-in rules, exposed as an
 *     app-configurable set. Useful for app tools whose effect lands on
 *     a projection or other durable record.
 *
 * The order of rules is fixed (pair-collapsing first, then dedup/prune)
 * and doesn't depend on which are enabled.
 */
case class StandardContextOptimizer(dropWhitespaceFrames: Boolean = true,
                                    dedupConsecutiveText: Boolean = true,
                                    stripStaleFindCapabilityPairs: Boolean = true,
                                    stripStaleChangeModePairs: Boolean = true,
                                    stripStaleTools: Set[String] = Set.empty) extends ContextOptimizer {

  private val trimToolNames: Set[String] = {
    val base = Set.newBuilder[String]
    if (stripStaleFindCapabilityPairs) base += "find_capability"
    if (stripStaleChangeModePairs) base += "change_mode"
    (base.result() ++ stripStaleTools)
  }

  override def optimize(frames: Vector[ContextFrame]): Vector[ContextFrame] = {
    var out = frames
    if (trimToolNames.nonEmpty) out = collapseToolPairs(out, trimToolNames)
    if (dropWhitespaceFrames) out = pruneWhitespace(out)
    if (dedupConsecutiveText) out = dedupRun(out)
    out
  }

  /** Drop every ToolCall whose tool name is in `trim`, plus the
    * matching ToolResult (by callId). Other frames flow through
    * untouched. */
  private def collapseToolPairs(frames: Vector[ContextFrame],
                                trim: Set[String]): Vector[ContextFrame] = {
    val dropCallIds = frames.iterator.collect {
      case tc: ContextFrame.ToolCall if trim.contains(tc.toolName.value) => tc.callId
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
      case ContextFrame.Text(content, _, _) => content.trim.nonEmpty
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
