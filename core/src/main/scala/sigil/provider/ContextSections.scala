package sigil.provider

import sigil.conversation.ContextMemory
import sigil.diagnostics.ProfileSection

/**
 * The ordered section list that defines the system prompt's structure.
 *
 * `Provider.renderSystem`, [[sigil.diagnostics.RequestProfiler]], the
 * curator's section-shed cascade, and `context_breakdown` are all
 * consumers of this one list — the layout cannot drift between them.
 * Apps reorder, drop, or add sections by overriding
 * `Provider.contextSections`.
 *
 * Order is the emission order: stable-prefix sections first, then the
 * volatile tail. Rendering concatenates each section's output for the
 * requested [[Placement]]; a section returning `None` contributes
 * nothing.
 */
object ContextSections {

  /** Prompt rendering for a memory: the summary when set (the
    * compressed per-turn form), the full fact otherwise. When the
    * summary elides a materially longer fact AND the memory has a
    * referenceable key, the line carries its drill-down handle —
    * `[full: lookup("<key>")]` — mirroring the summaries section's
    * inline `reload_content("<id>")` convention. */
  def memoryRenderText(m: ContextMemory): String =
    if (m.summary.trim.isEmpty) m.fact
    else {
      val handle = m.key match {
        case Some(key) if m.fact.trim.length > m.summary.trim.length * 2 && m.fact.trim.length > 200 =>
          s" [full: lookup(\"" + key + "\")]"
        case _ => ""
      }
      m.summary + handle
    }

  private def section(id: ProfileSection,
                      placement: Placement,
                      shedStage: Option[Int] = None)
                     (render: SectionContext => Option[String]): ContextSection =
    ContextSection(id, placement, shedStage, render)

  private def nonEmpty(s: String): Option[String] = if (s.isEmpty) None else Some(s)

  // ---- stable prefix (cacheable) ----

  private val toolFramingPrefix: ContextSection =
    section(ProfileSection.ToolFramingPrefix, Placement.StablePrefix) { c =>
      if (c.request.tools.isEmpty) None
      else Some(
        "You communicate exclusively through tool calls. Plain text output is never delivered to the user — " +
          "always pick a tool.\n\n" +
          "Tool calls go through the JSON `tool_calls` protocol the API negotiates with you. " +
          "Never emit `<tool_call>`, `<function=…>`, or similar XML/tag syntax inside `content` or any " +
          "other string field — those will NOT be parsed as tool calls; they will leak to the user as " +
          "text. If you want to make a follow-up tool call after responding, set `respond.endsTurn = false` " +
          "and issue the next call on the next iteration. A turn-ending respond describes what you DID, " +
          "not what you are about to do — content announcing work you have not done yet requires " +
          "`endsTurn = false`.\n\n"
      )
    }

  /** Tools that need runtime context (e.g. `change_mode` enumerating
    * the available modes) override `Tool.descriptionFor` to fold that
    * context into their own description, so this stays free of
    * per-tool special cases. */
  private val modeBlock: ContextSection =
    section(ProfileSection.ModeBlock, Placement.StablePrefix) { c =>
      Some(s"Current mode: ${c.request.currentMode} — ${c.request.currentMode.description}\n" +
        s"Current topic: \"${c.request.currentTopic.label}\" — ${c.request.currentTopic.summary}\n")
    }

  private val previousTopics: ContextSection =
    section(ProfileSection.PreviousTopics, Placement.StablePrefix) { c =>
      if (c.request.previousTopics.isEmpty) None
      else Some("Previous topics in this conversation:\n" +
        c.request.previousTopics.map(t => s"  - \"${t.label}\" — ${t.summary}\n").mkString)
    }

  /** The discovery block teaches discovery-first behaviour generically —
    * it names no specific tool, so tool-specific guidance travels with
    * each tool's own description. The one gate is whether discovery
    * itself is in the roster. */
  private val instructions: ContextSection =
    section(ProfileSection.Instructions, Placement.StablePrefix) { c =>
      val instr = c.instructionsText
      if (instr.isEmpty) None else Some("\n" + instr + "\n")
    }

  /** A single role renders linearly; multiple roles get a preamble and
    * per-role enumeration so the model handles multi-role identity
    * explicitly even when each description was written self-contained. */
  private val roles: ContextSection =
    section(ProfileSection.Roles, Placement.StablePrefix) { c =>
      c.request.roles match {
        case Nil          => None
        case List(single) =>
          if (single.description.nonEmpty) Some("\n" + single.description + "\n") else None
        case multi        =>
          Some("\nYou serve the following roles:\n" + multi.map { r =>
            val tail = if (r.description.nonEmpty) s" — ${r.description}" else ""
            s"- ${r.name}$tail\n"
          }.mkString)
      }
    }

  private val activeSkills: ContextSection =
    section(ProfileSection.ActiveSkills, Placement.StablePrefix) { c =>
      if (c.allSkills.isEmpty) None
      else Some("\n== Active skills ==\n" + c.allSkills.map { s =>
        s"- ${s.name}\n" + (if (s.content.nonEmpty) s.content + "\n" else "")
      }.mkString)
    }

  private val criticalMemories: ContextSection =
    section(ProfileSection.CriticalMemories, Placement.StablePrefix) { c =>
      if (c.resolved.criticalMemories.isEmpty) None
      else Some("\n== Pinned directives ==\n" +
        c.resolved.criticalMemories.map(m => s"- ${memoryRenderText(m)}\n").mkString)
    }

  private val information: ContextSection =
    section(ProfileSection.Information, Placement.StablePrefix, shedStage = Some(2)) { c =>
      if (c.turn.information.isEmpty) None
      else Some("\n== Referenced content (look up by id) ==\n" +
        c.turn.information.map(i => s"- ${i.id.value} [${i.informationType.name}]: ${i.summary}\n").mkString)
    }

  // ---- volatile tail (per-turn, excluded from the cacheable prefix) ----

  /** Summaries ride the volatile tail because the rolling intra-turn
    * compaction stream updates them mid-turn; rendered ahead of the
    * message history they invalidated the whole prompt cache on every
    * update. In the tail, an update invalidates nothing ahead of it. */
  private val summaries: ContextSection =
    section(ProfileSection.Summaries, Placement.VolatileTail, shedStage = Some(3)) { c =>
      if (c.resolved.summaries.isEmpty) None
      else {
        val body = c.resolved.summaries.map { s =>
          val handle =
            if (s.coversEventIds.nonEmpty)
              s""" [summarizes ${s.coversEventIds.size} earlier events — """ +
                s"""reload_content("${s._id.value}") to browse them and reload any in full]"""
            else ""
          s.text + handle + "\n"
        }.mkString
        Some("\n== Earlier in this conversation (summarized) ==\n" + body +
          "\nWhen an entry shows `reload_content(\"<id>\")`, call it to reload the full content " +
          "it elided (an event id → that event; a summary id → the events it covers).\n")
      }
    }

  private val memories: ContextSection =
    section(ProfileSection.Memories, Placement.VolatileTail, shedStage = Some(1)) { c =>
      if (c.resolved.memories.isEmpty) None
      else Some("\n== Memories ==\n" + c.resolved.memories.map(m => s"- ${memoryRenderText(m)}\n").mkString)
    }

  /** Agency must be unambiguous: this digest is a memory aid about the
    * assistant's OWN prior calls, not an external log. When budget
    * pressure has trimmed a call's full transaction from the history,
    * this line is the only remaining record. */
  private val recentTools: ContextSection =
    section(ProfileSection.RecentTools, Placement.VolatileTail) { c =>
      if (c.recentTools.isEmpty) None
      else Some("\n== Recently used tools ==\n" +
        "These are tool calls YOU (the assistant) made earlier in this conversation:\n" +
        c.recentTools.map { inv =>
          val ago = Provider.humanizeAgo(c.now - inv.invokedAt.value)
          val previewSuffix = if (inv.argsPreview.nonEmpty) s" (${inv.argsPreview})" else ""
          s"- ${inv.toolName.value}$previewSuffix -- $ago\n"
        }.mkString)
    }

  /** Every `(toolName, argsHash)` bucket firing more than once in the
    * rolling window. Informational, not directive: the count and args
    * are stated as data. Earlier wording prescribed an action, which
    * some models read as a hard stop and answered by abandoning the
    * turn. The explanation is stated once — repeating it per group
    * bloats the prompt and reads as noise. */
  private val repeatedToolCalls: ContextSection =
    section(ProfileSection.RepeatedToolCalls, Placement.VolatileTail) { c =>
      if (c.duplicateGroups.isEmpty) None
      else {
        val explanation =
          if (c.findCapabilityAvailable)
            "Identical inputs yield identical results UNLESS your tool roster has changed since " +
              "(compare your current offered tools against what you remember). If a tool you used before isn't in " +
              "your offer now, re-call `find_capability` even with the same keywords; the framework's cache state " +
              "may have changed.\n"
          else
            "Identical inputs yield identical results. If you've already seen a tool's output for these exact " +
              "arguments, reuse it instead of calling again.\n"
        val lines = c.duplicateGroups.map { case ((toolName, _), occurrences) =>
          val preview = occurrences.head.argsPreview
          val latest = occurrences.maxBy(_.invokedAt.value).invokedAt.value
          val ago = Provider.humanizeAgo(c.now - latest)
          val previewText = if (preview.nonEmpty) s" `$preview`" else ""
          s"- `${toolName.value}` called ${occurrences.size}x with identical args (most recent $ago):$previewText\n"
        }.mkString
        Some("\n== Repeated tool calls ==\n" + explanation + lines)
      }
    }

  private val suggestedTools: ContextSection =
    section(ProfileSection.SuggestedTools, Placement.VolatileTail) { c =>
      if (c.suggestedTools.isEmpty) None
      else Some("\n== Suggested tools ==\n" + c.suggestedTools.map(t => s"- ${t.value}\n").mkString)
    }

  /** Tools the agent already discovered via `find_capability` earlier
    * in this agent loop. The per-match wire-roster filter keeps the
    * DIRECTIVE sentence honest when narrowing dropped a discovered
    * tool from the offered set. */
  private val discoveredCapabilities: ContextSection =
    section(ProfileSection.DiscoveredCapabilities, Placement.VolatileTail) { c =>
      if (c.discovered.isEmpty) None
      else Some("\n== Capabilities you've already discovered (this turn) ==\n" +
        c.discovered.map { case (query, matches) =>
          s"- `find_capability($query)` → ${matches.map(_.value).mkString(", ")}\n"
        }.mkString +
        "DIRECTIVE: These tools are NOW in your roster — call them directly to complete the task. " +
        "Re-calling `find_capability` for the same query, or falling back to `respond` without first " +
        "calling the discovered action tool the user requested, is a protocol violation. If the user's " +
        "request maps to one of these tools, invoke it on THIS iteration.\n")
    }

  private val extraContext: ContextSection =
    section(ProfileSection.ExtraContext, Placement.VolatileTail) { c =>
      if (c.turn.extraContext.isEmpty) None
      else Some("\n== Conversation context ==\n" +
        c.turn.extraContext.map { case (k, v) => s"- ${k.value}: $v\n" }.mkString)
    }

  private val participantContext: ContextSection =
    section(ProfileSection.ParticipantContext, Placement.VolatileTail) { c =>
      if (c.perParticipantExtras.isEmpty) None
      else Some("\n== Participant context ==\n" +
        c.perParticipantExtras.map { case (pid, (k, v)) => s"- ${pid.value} ${k.value}: $v\n" }.mkString)
    }

  /** When the turn was fired by `greetsOnJoin`'s greeting flow, say so
    * explicitly — without the hint the model picks `respond` vs
    * `no_response` stochastically, breaking the contract implied by
    * `greetsOnJoin = true`. Rendered last so it sits within the
    * model's recency-biased attention. */
  private val greetingHint: ContextSection =
    section(ProfileSection.GreetingHint, Placement.VolatileTail) { c =>
      if (!c.request.isGreeting) None
      else Some("\n== Greeting turn ==\n" +
        "This is a fresh conversation. Call `respond` with a brief introduction — " +
        "state your role and offer to help. " +
        (if (c.findCapabilityAvailable)
           "Do NOT call `no_response` or `find_capability` on this turn; " +
             "the user expects a greeting, not silence or discovery.\n"
         else
           "Do NOT call `no_response` on this turn; the user expects a greeting, not silence.\n"))
    }

  /** The framework's section list, in emission order. */
  val all: List[ContextSection] = List(
    toolFramingPrefix,
    modeBlock,
    previousTopics,
    instructions,
    roles,
    activeSkills,
    criticalMemories,
    information,
    summaries,
    memories,
    recentTools,
    repeatedToolCalls,
    suggestedTools,
    discoveredCapabilities,
    extraContext,
    participantContext,
    greetingHint
  )

  /** Concatenate every section rendering for one placement. */
  def render(sections: List[ContextSection], placement: Placement, c: SectionContext): String =
    sections.iterator.filter(_.placement == placement).flatMap(_.render(c)).mkString
}
