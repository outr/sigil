package bench.contextprofile

import sigil.tool.core.{FindCapabilityTool, RespondTool, StopTool}

/**
 * Phase 0 — compression-trigger profile. Same shape as
 * [[LongConversationBench]] but each turn emits a verbose ToolResult
 * (1.5KB) so the conversation crosses a typical small-model context
 * window quickly. Then renders a "post-compression" variant where
 * the older half of frames have been replaced with a single 200-token
 * summary, demonstrating the savings.
 *
 * Run: `sbt "benchmark/runMain bench.contextprofile.CompressionTriggerBench"`
 * Output: `benchmark/profiles/compression-trigger.md`
 */
object CompressionTriggerBench {

  private val verboseToolResult =
    s"""{"records":[${(1 to 30).map(i => s"""{"id":"rec-$i","data":"${("payload " * 8).trim}"}""").mkString(",")}]}"""

  def main(args: Array[String]): Unit = {
    val turns = 40
    val tools = Vector[sigil.tool.Tool](RespondTool, FindCapabilityTool, StopTool)

    val cumulativeFrames = scala.collection.mutable.ArrayBuffer.empty[Vector[sigil.conversation.ContextFrame]]
    var current = Vector.empty[sigil.conversation.ContextFrame]
    (1 to turns).foreach { n =>
      val userMsg = ProfilerHarness.textFrame(s"User turn $n: fetch records and summarize them.")
      current :+= userMsg
      val tc = ProfilerHarness.toolCallFrame("fetch_records", s"""{"page":$n}""")
      current :+= tc
      current :+= ProfilerHarness.toolResultFrame(tc.callId, verboseToolResult)
      current :+= ProfilerHarness.textFrame(s"Agent reply $n: summarized $n pages.", ProfilerHarness.AgentId)
      cumulativeFrames += current
    }

    val rawProfiles = cumulativeFrames.map { frames =>
      val view = ProfilerHarness.viewWith(frames)
      val req = ProfilerHarness.buildRequest(view, tools)
      ProfilerHarness.profile(req)
    }.toVector

    ProfilerHarness.writeReport(
      "compression-trigger-uncompressed",
      "Phase 0 — Compression Trigger (uncompressed: 40 turns × 1.5KB ToolResults)",
      rawProfiles
    )

    // Compressed variant: at turn 25, replace older 15 turns' frames with one summary.
    val compressionPoint = 25
    val summaryRecord = ProfilerHarness.summary(
      "Earlier in this conversation: agent fetched 15 pages of records and summarized each. " +
        "User confirmed the data looked correct. Agent moved on to the next batch."
    )

    val compressedProfiles = cumulativeFrames.zipWithIndex.map { case (frames, idx) =>
      val turnNumber = idx + 1
      val view = if (turnNumber <= compressionPoint) {
        ProfilerHarness.viewWith(frames)
      } else {
        // Drop the first 15 turns × 4 frames each = 60 frames; keep the rest.
        val keptFrames = frames.drop(60)
        ProfilerHarness.viewWith(keptFrames)
      }
      val req = ProfilerHarness.buildRequest(view, tools)
      val refs = if (turnNumber > compressionPoint)
        ProfilerHarness.resolved(summaries = Vector(summaryRecord))
      else ProfilerHarness.resolved()
      ProfilerHarness.profile(req, refs)
    }.toVector

    ProfilerHarness.writeReport(
      "compression-trigger-compressed",
      "Phase 0 — Compression Trigger (compressed at turn 25: older 15 turns → 1 summary)",
      compressedProfiles
    )
  }
}
