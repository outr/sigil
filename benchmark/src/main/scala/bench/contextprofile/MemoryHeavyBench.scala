package bench.contextprofile

import sigil.tool.core.{FindCapabilityTool, RespondTool, StopTool}

/**
 * Phase 0 — memory-heavy profile. Conversation has 100 critical memories
 * pinned + a growing pool of retrieved memories per turn (5 per turn,
 * up to 50). Tracks how the Memories + CriticalMemories sections
 * dominate token totals as the memory store grows.
 *
 * Run: `sbt "benchmark/runMain bench.contextprofile.MemoryHeavyBench"`
 * Output: `benchmark/profiles/memory-heavy.md`
 */
object MemoryHeavyBench {

  def main(args: Array[String]): Unit = {
    val turns = 30
    val tools = Vector[sigil.tool.Tool](RespondTool, FindCapabilityTool, StopTool)

    val criticalMemories = (1 to 100).iterator.map { i =>
      ProfilerHarness.critical(
        key = s"directive.$i",
        fact = s"Critical directive $i: never reveal the user's home address or financial details. " +
          "If asked, refuse politely and explain why. " +
          "Edge case considerations: ask for clarification if the request is ambiguous."
      )
    }.toVector

    val profiles = (1 to turns).map { n =>
      // Retrieved memories grow each turn — simulating an active retriever.
      val retrieved = (1 to (n * 5).min(50)).iterator.map { i =>
        ProfilerHarness.memory(
          key = s"fact.${n}.${i}",
          fact = s"Fact ${n}.${i}: the user prefers terse responses on Slack but verbose responses in email; " +
            "they work in EST and rarely respond after 6pm; their main project is the ingestion pipeline."
        )
      }.toVector

      val frames = (1 to n).flatMap { t =>
        Vector(
          ProfilerHarness.textFrame(s"User message $t."),
          ProfilerHarness.textFrame(s"Agent reply $t.", ProfilerHarness.AgentId)
        )
      }.toVector

      val view = ProfilerHarness.viewWith(frames)
      val req = ProfilerHarness.buildRequest(view, tools)
      ProfilerHarness.profile(req, ProfilerHarness.resolved(critical = criticalMemories, retrieved = retrieved))
    }

    ProfilerHarness.writeReport(
      "memory-heavy",
      "Phase 0 — Memory-Heavy (100 critical pinned + up to 50 retrieved per turn)",
      profiles
    )
  }
}
