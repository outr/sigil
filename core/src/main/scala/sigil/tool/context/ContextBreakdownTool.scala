package sigil.tool.context

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ContextFrame, ContextMemory, MemorySource}
import sigil.event.{Event, Message, MessageRole}
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Returns a human-readable breakdown of how the current turn's context
 * is being spent — section-by-section token contributions plus a list
 * of insights derived from the same data. Used when the user asks
 * "where is your context going?" / "why is my context full?".
 *
 * Computed against the current [[TurnContext]]: the conversation's
 * frames + critical memories + retrieved memories + active skills +
 * roles. Token counts via the char/4 heuristic — accurate enough to
 * answer the user's question; production budget enforcement uses the
 * provider's per-vendor tokenizer separately.
 */
case object ContextBreakdownTool extends TypedTool[ContextBreakdownInput](
  name = ToolName("context_breakdown"),
  description =
    """Return a section-by-section breakdown of where your context window is being spent
      |this turn — frames, critical memories, retrieved memories, active skills, etc.
      |Use this when the user asks "what's in your context?" / "why is my context full?".
      |
      |Pair with `list_memories(pinned=true)` to drill into Critical directives, or
      |`unpin_memory` to remove ones the user no longer needs.""".stripMargin,
  keywords = Set("context", "breakdown", "tokens", "usage", "share", "where", "why")
) {
  override def resultTtl: Option[Int] = Some(0)

  override protected def executeTyped(input: ContextBreakdownInput, context: TurnContext): Stream[Event] =
    Stream.force(breakdown(context).map { body =>
      Stream.emits(List[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(body)),
        role = MessageRole.Tool
      )))
    })

  private def breakdown(context: TurnContext): Task[String] =
    context.sigil.accessibleSpaces(context.chain).flatMap { spaces =>
      val critTask = if (spaces.isEmpty) Task.pure(List.empty[ContextMemory])
                     else context.sigil.findCriticalMemories(spaces)
      critTask.map { criticals =>
        val tokenizer = HeuristicTokenizer
        val view = context.conversationView

        val frameTokens = view.frames.iterator.map {
          case f: ContextFrame.Text       => tokenizer.count(f.content)
          case f: ContextFrame.ToolCall   => tokenizer.count(f.argsJson)
          case f: ContextFrame.ToolResult => tokenizer.count(f.content)
          case f: ContextFrame.System     => tokenizer.count(f.content)
          case f: ContextFrame.Reasoning  => tokenizer.count(f.summary.mkString("\n"))
        }.sum

        val criticalTokens = criticals.iterator.map { m =>
          tokenizer.count(if (m.summary.trim.nonEmpty) m.summary else m.fact)
        }.sum

        val skills = view.aggregatedSkills(context.chain)
        val skillTokens = skills.iterator.map(s => tokenizer.count(s.name) + tokenizer.count(s.content)).sum

        val mode = context.conversation.currentMode
        val modeTokens = tokenizer.count(s"${mode.name} — ${mode.description}")

        val total = frameTokens + criticalTokens + skillTokens + modeTokens

        val sections = arr(
          obj("section" -> str("Frames"),            "tokens" -> num(frameTokens),    "count" -> num(view.frames.size)),
          obj("section" -> str("CriticalMemories"),  "tokens" -> num(criticalTokens), "count" -> num(criticals.size)),
          obj("section" -> str("ActiveSkills"),      "tokens" -> num(skillTokens),    "count" -> num(skills.size)),
          obj("section" -> str("ModeBlock"),         "tokens" -> num(modeTokens),     "count" -> num(1))
        )

        val payload = obj(
          "total_tokens" -> num(total),
          "model_id" -> str(context.conversation.currentMode.name), // best-effort
          "current_mode" -> str(mode.name),
          "sections" -> sections,
          "note" -> str("Tokens estimated via the char/4 heuristic; production budget uses the provider's tokenizer.")
        )
        JsonFormatter.Default(payload)
      }
    }
}
