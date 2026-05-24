package sigil.tool.context

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.conversation.{ContextFrame, ContextMemory}
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.model.{ContextBreakdownOutput, ContextSectionBreakdown, ContextSectionKind}
import sigil.tool.{Tool, ToolName}

/**
 * Returns a breakdown of how the current turn's context is being
 * spent — section-by-section token contributions. Used when the user
 * asks "where is your context going?" / "why is my context full?".
 *
 * Computed against the current [[TurnContext]]: the conversation's
 * frames + critical memories + active skills + mode block. Token
 * counts via the char/4 heuristic — accurate enough to answer the
 * user's question; production budget enforcement uses the provider's
 * per-vendor tokenizer separately.
 *
 * Emits a typed [[ContextBreakdownOutput]].
 */
case object ContextBreakdownTool extends Tool {
  type Input  = ContextBreakdownInput
  type Output = ContextBreakdownOutput
  val inputRW  = summon[RW[ContextBreakdownInput]]
  val outputRW = summon[RW[ContextBreakdownOutput]]
  val name = ToolName("context_breakdown")
  val description =
    """Return a section-by-section breakdown of where your context window is being spent
      |this turn — frames, critical memories, retrieved memories, active skills, etc.
      |Use this when the user asks "what's in your context?" / "why is my context full?".
      |
      |For details on specific pinned items, use the memory-listing and memory-unpinning
      |tools available in this conversation.""".stripMargin
  override val keywords = Set("context", "breakdown", "tokens", "usage", "share", "where", "why")

  override def resultTtl: Option[Int] = Some(0)

  override def executeOutput(input: ContextBreakdownInput, context: ToolContext): Task[ContextBreakdownOutput] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { spaces =>
      val critTask = if (spaces.isEmpty) Task.pure(List.empty[ContextMemory])
                     else context.sigil.findCriticalMemories(spaces)
      critTask.map { criticals =>
        val tokenizer = HeuristicTokenizer
        val turn      = context.turnInput

        val frameTokens = turn.frames.iterator.map {
          case f: ContextFrame.Text      => tokenizer.count(f.content)
          case f: ContextFrame.ToolCall  =>
            // Sigil #261 — unified ToolCall(state) frame: count args
            // plus (when Complete) the result content as one frame.
            val resultTokens = f.state match {
              case sigil.conversation.ToolCallState.Complete(content, _) => tokenizer.count(content)
              case sigil.conversation.ToolCallState.Active               => 0
            }
            tokenizer.count(f.argsJson) + resultTokens
          case f: ContextFrame.System    => tokenizer.count(f.content)
          case f: ContextFrame.Reasoning => tokenizer.count(f.summary.mkString("\n"))
        }.sum

        val criticalTokens = criticals.iterator.map { m =>
          tokenizer.count(if (m.summary.trim.nonEmpty) m.summary else m.fact)
        }.sum

        val skills      = turn.aggregatedSkills(context.chain)
        val skillTokens = skills.iterator.map(s => tokenizer.count(s.name) + tokenizer.count(s.content)).sum

        val mode       = context.conversation.currentMode
        val modeTokens = tokenizer.count(s"${mode.name} — ${mode.description}")

        val total = frameTokens + criticalTokens + skillTokens + modeTokens

        ContextBreakdownOutput(
          totalTokens = total,
          currentMode = mode.name,
          sections = List(
            ContextSectionBreakdown(ContextSectionKind.Frames,           frameTokens,    turn.frames.size),
            ContextSectionBreakdown(ContextSectionKind.CriticalMemories, criticalTokens, criticals.size),
            ContextSectionBreakdown(ContextSectionKind.ActiveSkills,     skillTokens,    skills.size),
            ContextSectionBreakdown(ContextSectionKind.ModeBlock,        modeTokens,     1)
          ),
          note = "Tokens estimated via the char/4 heuristic; production budget uses the provider's tokenizer."
        )
      }
    }
}
