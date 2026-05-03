package sigil.diagnostics

import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory}
import sigil.provider.{ConversationRequest, ResolvedReferences}
import sigil.tokenize.Tokenizer
import sigil.tool.Tool

/**
 * Build a [[RequestProfile]] for a [[ConversationRequest]] — counting
 * tokens per system-prompt section, per conversation frame, and per
 * wire-level piece. Mirrors the section layout `Provider.renderSystem`
 * produces; if those two drift, [[spec.RequestProfilerSpec]] catches it
 * by asserting `profile.total ≈ tokenizer.count(rendered system + frames)`.
 *
 * Phase 0 instrumentation: opt-in via `Sigil.profileWireRequests`. The
 * data drives the shed-order decisions in the curator; not used in any
 * hot path.
 */
object RequestProfiler {

  /** Profile a request using a Sigil instance (typical Provider call site).
    * `descriptionFor` calls hit `Tool.descriptionFor(currentMode, sigil)` —
    * which is the wire-accurate string for tools like `change_mode` that
    * fold runtime context into their description. Adds insights derived
    * from the model's `contextLength` (looked up via `sigil.cache`). */
  def profile(request: ConversationRequest,
              resolved: ResolvedReferences,
              tokenizer: Tokenizer,
              sigil: Sigil): RequestProfile = {
    val raw = profileWith(request, resolved, tokenizer, t => t.descriptionFor(request.currentMode, sigil))
    val contextLength = sigil.cache.find(request.modelId).map(_.contextLength.toInt).getOrElse(0)
    val cfg = InsightGenerator.InsightConfig(contextLength = contextLength)
    val insights = InsightGenerator.insights(
      profile = raw,
      criticalMemories = resolved.criticalMemories,
      retrievedMemories = resolved.memories,
      summaries = resolved.summaries,
      cfg = cfg,
      tokenizer = tokenizer
    )
    raw.copy(insights = insights)
  }

  /** Profile a request with an explicit description supplier. Used by
    * synthetic benches that don't want to spin up a full Sigil — pass
    * `_.description` to fall back to static descriptions, or supply a
    * custom mapping for richer scenarios. */
  def profileWith(request: ConversationRequest,
                  resolved: ResolvedReferences,
                  tokenizer: Tokenizer,
                  descriptionFor: Tool => String): RequestProfile = {
    val turn = request.turnInput
    val view = turn.conversationView
    val chain = request.chain

    val sections = scala.collection.mutable.Map.empty[ProfileSection, Int]
    def add(section: ProfileSection, text: String): Unit =
      if (text.nonEmpty) sections(section) = sections.getOrElse(section, 0) + tokenizer.count(text)

    // 1. Tool framing prefix
    if (request.tools.nonEmpty) add(ProfileSection.ToolFramingPrefix,
      "You communicate exclusively through tool calls. Plain text output is never delivered to the user — always pick a tool.\n\n")

    // 2. Mode + topic block
    val modeText = new StringBuilder
    modeText.append(s"Current mode: ${request.currentMode} — ${request.currentMode.description}\n")
    modeText.append(s"Current topic: \"${request.currentTopic.label}\" — ${request.currentTopic.summary}\n")
    if (request.previousTopics.nonEmpty) {
      modeText.append("Previous topics in this conversation:\n")
      request.previousTopics.foreach(t => modeText.append(s"  - \"${t.label}\" — ${t.summary}\n"))
    }
    add(ProfileSection.ModeBlock, modeText.toString)

    // 3. Instructions (variant matches Provider.renderSystem branching)
    val findCapabilityAvailable = request.tools.exists(_.schema.name.value == "find_capability")
    val respondAvailable = request.tools.exists(_.schema.name.value == "respond")
    val instr =
      if (!findCapabilityAvailable) request.instructions.renderWithoutTools
      else if (!respondAvailable) request.instructions.forPureDiscovery.render
      else request.instructions.render
    add(ProfileSection.Instructions, instr)

    // 4. Critical memories — use `summary || fact` to mirror Provider.renderSystem
    if (resolved.criticalMemories.nonEmpty) {
      val text = "\n== Critical directives ==\n" +
        resolved.criticalMemories.map(m => s"- ${memoryRenderText(m)}\n").mkString
      add(ProfileSection.CriticalMemories, text)
    }

    // 5. Summaries
    if (resolved.summaries.nonEmpty) {
      val text = "\n== Earlier in this conversation ==\n" +
        resolved.summaries.map(_.text + "\n").mkString
      add(ProfileSection.Summaries, text)
    }

    // 6. Memories — use `summary || fact` to mirror Provider.renderSystem
    if (resolved.memories.nonEmpty) {
      val text = "\n== Memories ==\n" +
        resolved.memories.map(m => s"- ${memoryRenderText(m)}\n").mkString
      add(ProfileSection.Memories, text)
    }

    // 7. Information
    if (turn.information.nonEmpty) {
      val text = "\n== Referenced content (look up by id) ==\n" +
        turn.information.map(i => s"- ${i.id.value} [${i.informationType.name}]: ${i.summary}\n").mkString
      add(ProfileSection.Information, text)
    }

    // 8. Roles
    val rolesText = request.roles match {
      case Nil => ""
      case List(single) =>
        if (single.description.nonEmpty) s"\n${single.description}\n" else ""
      case multi =>
        "\nYou serve the following roles:\n" + multi.map { r =>
          val tail = if (r.description.nonEmpty) s" — ${r.description}" else ""
          s"- ${r.name}$tail\n"
        }.mkString
    }
    add(ProfileSection.Roles, rolesText)

    // 9. Active skills (chain-aggregated + role-bundled)
    val skills = view.aggregatedSkills(chain)
    val roleSkills = request.roles.flatMap(_.skill.toList)
    val allSkills = (skills ++ roleSkills).distinctBy(_.name)
    if (allSkills.nonEmpty) {
      val text = "\n== Active skills ==\n" + allSkills.map { s =>
        val body = if (s.content.nonEmpty) s.content + "\n" else ""
        s"- ${s.name}\n$body"
      }.mkString
      add(ProfileSection.ActiveSkills, text)
    }

    // 10. Recently used tools
    val recentTools = chain.flatMap(id => view.projectionFor(id).recentTools).distinct
    if (recentTools.nonEmpty) {
      val text = "\n== Recently used tools ==\n" + recentTools.map(t => s"- $t\n").mkString
      add(ProfileSection.RecentTools, text)
    }

    // 11. Suggested tools
    val suggestedTools = chain.flatMap(id => view.projectionFor(id).suggestedTools).distinct
    if (suggestedTools.nonEmpty) {
      val text = "\n== Suggested tools ==\n" + suggestedTools.map(t => s"- $t\n").mkString
      add(ProfileSection.SuggestedTools, text)
    }

    // 12. Extra context (turn-level + per-participant)
    val extraText = new StringBuilder
    if (turn.extraContext.nonEmpty) {
      extraText.append("\n== Conversation context ==\n")
      turn.extraContext.foreach { case (k, v) => extraText.append(s"- ${k.value}: $v\n") }
    }
    val perPart = chain.flatMap(id => view.projectionFor(id).extraContext.map(id -> _))
    if (perPart.nonEmpty) {
      extraText.append("\n== Participant context ==\n")
      perPart.foreach { case (pid, (k, v)) => extraText.append(s"- ${pid.value} ${k.value}: $v\n") }
    }
    add(ProfileSection.ExtraContext, extraText.toString)

    // 13. Frames (the message array)
    val frameProfiles = view.frames.map { f =>
      val (kind, text, eventId) = f match {
        case t: ContextFrame.Text        => ("Text", t.content, t.sourceEventId)
        case tc: ContextFrame.ToolCall   => ("ToolCall", tc.argsJson, tc.sourceEventId)
        case tr: ContextFrame.ToolResult => ("ToolResult", tr.content, tr.sourceEventId)
        case s: ContextFrame.System      => ("System", s.content, s.sourceEventId)
        case r: ContextFrame.Reasoning   => ("Reasoning", r.summary.mkString("\n"), r.sourceEventId)
      }
      FrameProfile(kind, eventId, tokenizer.count(text))
    }
    val framesTotal = frameProfiles.iterator.map(_.tokens).sum
    if (framesTotal > 0) sections(ProfileSection.Frames) = framesTotal

    // 14. Tool roster — every tool's name + description as the wire
    // payload would carry it. JSON-schema overhead approximated by
    // the description length (the schema body is comparable in size).
    if (request.tools.nonEmpty) {
      val rosterText = request.tools.iterator.map { t =>
        s"${t.schema.name.value}\n${descriptionFor(t)}\n"
      }.mkString
      add(ProfileSection.ToolRoster, rosterText)
    }

    val total = sections.values.sum
    RequestProfile(total = total, sections = sections.toMap, frames = frameProfiles)
  }

  /** Mirrors `Provider.renderSystem`'s memory-render policy: prefer
    * `summary` when set, fall back to `fact`. Apps writing concise
    * critical directives via the `summary` field shrink per-turn
    * rendered cost; the full `fact` remains recoverable via `lookup`. */
  private def memoryRenderText(m: ContextMemory): String =
    if (m.summary.trim.nonEmpty) m.summary else m.fact
}
