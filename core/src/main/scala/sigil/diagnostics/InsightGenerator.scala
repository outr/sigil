package sigil.diagnostics

import sigil.conversation.{ContextMemory, ContextSummary}
import sigil.tokenize.Tokenizer

/**
 * Derives [[ContextManagementInsight]]s from a [[RequestProfile]] plus
 * the supplementary records that fed into it. Pure function; no side
 * effects, no DB access. Called by the request profiler during
 * profile construction so every emitted [[sigil.signal.WireRequestProfile]]
 * carries both the raw section breakdown AND the actionable insight
 * list.
 *
 * Thresholds are tunable via the [[InsightConfig]] arg — apps with
 * different sensitivities (e.g. a tight per-turn budget) override.
 * Defaults match the Phase 0 cross-scenario findings.
 */
object InsightGenerator {

  /**
   * @param contextLength            model's window in tokens
   * @param criticalShareThreshold   warn when critical memory share > this
   * @param retrievedShareThreshold  info when retrieved memory share > this
   * @param toolRosterShareThreshold info when tool roster share > this
   * @param frameShareThreshold      info when frames share > this (compression imminent)
   * @param totalShareThreshold      warn when overall total > this fraction of contextLength
   * @param perCriticalFactThreshold per-record token cap; over → recommend summary
   */
  case class InsightConfig(contextLength: Int,
                           criticalShareThreshold: Double = 0.30,
                           retrievedShareThreshold: Double = 0.25,
                           toolRosterShareThreshold: Double = 0.25,
                           frameShareThreshold: Double = 0.70,
                           totalShareThreshold: Double = 0.80,
                           perCriticalFactThreshold: Int = 150)

  def insights(profile: RequestProfile,
               criticalMemories: Vector[ContextMemory],
               retrievedMemories: Vector[ContextMemory],
               summaries: Vector[ContextSummary],
               cfg: InsightConfig,
               tokenizer: Tokenizer): List[ContextManagementInsight] = {
    val out = List.newBuilder[ContextManagementInsight]

    val total = profile.total
    if (cfg.contextLength > 0 && total.toDouble / cfg.contextLength > cfg.totalShareThreshold) {
      out += ContextManagementInsight(
        level = InsightLevel.Warning,
        category = InsightCategory.Budget,
        message = f"Total context usage at ${total.toDouble / cfg.contextLength * 100}%.0f%% (${total} / ${cfg.contextLength} tok) — consider reviewing what's pinned",
        suggestedAction = Some("context_breakdown")
      )
    }

    val criticalTokens = profile.sections.getOrElse(ProfileSection.CriticalMemories, 0)
    if (total > 0 && criticalTokens.toDouble / total > cfg.criticalShareThreshold) {
      val top = topContributorsByTokens(criticalMemories, tokenizer, n = 3)
      val topStr = top.map { case (key, tok) => s"$key @${tok} tok" }.mkString(", ")
      out += ContextManagementInsight(
        level = InsightLevel.Recommendation,
        category = InsightCategory.Memory,
        message = f"Critical directives are ${criticalTokens.toDouble / total * 100}%.0f%% of your context (top: $topStr)",
        suggestedAction = Some("list_pinned_memories")
      )
    }

    // Per-critical-record nudge: long fact + empty summary → suggest summary.
    criticalMemories.foreach { m =>
      val factTokens = tokenizer.count(m.fact)
      if (factTokens > cfg.perCriticalFactThreshold && m.summary.trim.isEmpty) {
        out += ContextManagementInsight(
          level = InsightLevel.Recommendation,
          category = InsightCategory.Memory,
          message = s"Memory `${displayKey(m)}` (${factTokens} tok) has no summary; setting one would shrink the rendered cost",
          suggestedAction = None
        )
      }
    }

    val retrievedTokens = profile.sections.getOrElse(ProfileSection.Memories, 0)
    if (total > 0 && retrievedTokens.toDouble / total > cfg.retrievedShareThreshold) {
      out += ContextManagementInsight(
        level = InsightLevel.Info,
        category = InsightCategory.Memory,
        message = f"Retrieved memories are ${retrievedTokens.toDouble / total * 100}%.0f%% of context — agent could trim if not all are relevant this turn",
        suggestedAction = None
      )
    }

    val toolRosterTokens = profile.sections.getOrElse(ProfileSection.ToolRoster, 0)
    if (total > 0 && toolRosterTokens.toDouble / total > cfg.toolRosterShareThreshold) {
      out += ContextManagementInsight(
        level = InsightLevel.Info,
        category = InsightCategory.Tools,
        message = f"Tool roster is ${toolRosterTokens.toDouble / total * 100}%.0f%% of context (${toolRosterTokens} tok) — consider mode-scoping the available tools",
        suggestedAction = None
      )
    }

    val frameTokens = profile.sections.getOrElse(ProfileSection.Frames, 0)
    if (cfg.contextLength > 0 && frameTokens.toDouble / cfg.contextLength > cfg.frameShareThreshold) {
      out += ContextManagementInsight(
        level = InsightLevel.Info,
        category = InsightCategory.Frames,
        message = f"Conversation history is ${frameTokens.toDouble / cfg.contextLength * 100}%.0f%% of context — compression likely imminent",
        suggestedAction = None
      )
    }

    if (summaries.nonEmpty) {
      out += ContextManagementInsight(
        level = InsightLevel.Info,
        category = InsightCategory.Frames,
        message = s"This turn includes ${summaries.size} summary record(s) from prior compression — older history is preserved in compressed form",
        suggestedAction = None
      )
    }

    out.result()
  }

  private def topContributorsByTokens(memories: Vector[ContextMemory],
                                      tokenizer: Tokenizer,
                                      n: Int): List[(String, Int)] =
    memories.iterator
      .map(m => displayKey(m) -> tokenizer.count(if (m.summary.nonEmpty) m.summary else m.fact))
      .toList
      .sortBy(-_._2)
      .take(n)

  private def displayKey(m: ContextMemory): String =
    m.key.getOrElse(if (m.label.nonEmpty) m.label else m._id.value)
}
