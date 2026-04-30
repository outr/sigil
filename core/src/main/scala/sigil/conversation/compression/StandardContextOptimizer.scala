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
                        elideToolNames: Set[String] = Set.empty): Vector[ContextFrame] = {
    val trim = elideToolNames ++ stripStaleTools
    var out = frames
    if (trim.nonEmpty) out = collapseToolPairs(out, trim)
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
