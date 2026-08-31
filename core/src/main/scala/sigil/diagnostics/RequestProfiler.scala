package sigil.diagnostics

import sigil.Sigil
import sigil.conversation.ContextFrame
import sigil.provider.{ContextSection, ContextSections, ConversationRequest, SectionContext}
import sigil.tokenize.Tokenizer
import sigil.tool.Tool

/**
 * Build a [[RequestProfile]] for a [[ConversationRequest]] — counting
 * tokens per system-prompt section, per conversation frame, and per
 * wire-level piece.
 *
 * Section accounting runs the SAME [[ContextSection]] renderers
 * `Provider.renderSystem` emits from, so the profile cannot drift from
 * the wire. Only the two wire-level pieces the section list does not
 * cover — conversation frames and the tool roster — are counted here.
 *
 * Opt-in via `Sigil.profileWireRequests`. Not used in any hot path.
 */
object RequestProfiler {

  /**
   * Profile a request using a Sigil instance (typical Provider call site).
   * `descriptionFor` calls hit `Tool.descriptionFor(currentMode, sigil)` —
   * which is the wire-accurate string for tools like `change_mode` that
   * fold runtime context into their description. Adds insights derived
   * from the model's `contextLength` (looked up via `sigil.cache`).
   *
   * The [[SectionContext]] is the renderer's own — the provider builds
   * it once per turn and hands the same value to both, so the profile
   * counts the bytes the wire carries (same `now`, same derivations,
   * one pass of the shared lazy vals).
   */
  def profile(ctx: SectionContext,
              tokenizer: Tokenizer,
              sigil: Sigil,
              sections: List[ContextSection] = ContextSections.all): RequestProfile = {
    val request = ctx.request
    val resolved = ctx.resolved
    val raw = profileWith(ctx, tokenizer, t => t.descriptionFor(request.currentMode, sigil), sections)
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

  /**
   * Profile a request with an explicit description supplier. Used by
   * synthetic benches that don't want to spin up a full Sigil — pass
   * `_.description` to fall back to static descriptions, or supply a
   * custom mapping for richer scenarios.
   */
  def profileWith(sectionContext: SectionContext,
                  tokenizer: Tokenizer,
                  descriptionFor: Tool => String,
                  sectionList: List[ContextSection] = ContextSections.all): RequestProfile = {
    val request = sectionContext.request
    val turn = request.turnInput

    val sections = scala.collection.mutable.Map.empty[ProfileSection, Int]
    def add(section: ProfileSection, text: String): Unit =
      if (text.nonEmpty) sections(section) = sections.getOrElse(section, 0) + tokenizer.count(text)

    // System-prompt sections: counted from the renderer's own
    // functions, so the accounting is the wire's by construction.
    sectionList.foreach(s => s.rendered(sectionContext).foreach(add(s.id, _)))

    // Frames (the message array) — wire-level, outside the section list.
    val frameProfiles = turn.frames.map { f =>
      val (kind, text, eventId) = f match {
        case t: ContextFrame.Text => ("Text", t.content, t.sourceEventId)
        case tc: ContextFrame.ToolCall =>
          // Sigil #261 — unified ToolCall(state) frame contributes
          // both its args AND (when Complete) the result content to
          // the profile in one entry; previously this was profiled as
          // two separate Frame rows (ToolCall + ToolResult).
          val resultText = tc.state match {
            case sigil.conversation.ToolCallState.Complete(content, _) => "\n" + content
            case sigil.conversation.ToolCallState.Active => ""
          }
          ("ToolCall", tc.argsJson + resultText, tc.sourceEventId)
        case s: ContextFrame.System => ("System", s.content, s.sourceEventId)
        case r: ContextFrame.Reasoning => ("Reasoning", r.summary.mkString("\n"), r.sourceEventId)
      }
      FrameProfile(kind, eventId, tokenizer.count(text))
    }
    val framesTotal = frameProfiles.iterator.map(_.tokens).sum
    if (framesTotal > 0) sections(ProfileSection.Frames) = framesTotal

    // Tool roster — every tool's name + description as the wire
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
}
