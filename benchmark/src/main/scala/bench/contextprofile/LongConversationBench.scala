package bench.contextprofile

import sigil.conversation.ContextFrame
import sigil.tool.core.{FindCapabilityTool, RespondTool, StopTool}

/**
 * Phase 0 — long conversation profile. Synthesizes a 50-turn back-and-forth
 * (user message + agent respond), tracks how the per-section token totals
 * grow turn-over-turn. The standard tool roster (respond / find_capability /
 * stop) is in scope every turn — typical chat-style flow.
 *
 * Run: `sbt "benchmark/runMain bench.contextprofile.LongConversationBench"`
 * Output: `benchmark/profiles/long-conversation.md`
 */
object LongConversationBench {

  def main(args: Array[String]): Unit = {
    val turns = 50
    val tools = Vector[sigil.tool.Tool](RespondTool, FindCapabilityTool, StopTool)

    // Build cumulative frame list: each turn adds [userMessage, agentRespond].
    val framesByTurn: Vector[Vector[ContextFrame]] = (1 to turns).scanLeft(Vector.empty[ContextFrame]) {
      case (prev, n) =>
        val userMsg  = ProfilerHarness.textFrame(s"User message $n: tell me about the framework's pipeline architecture in detail.", ProfilerHarness.UserId)
        val agentMsg = ProfilerHarness.textFrame(s"Agent reply $n: ${"the pipeline operates on a stream of signals; each signal can be an event or a delta. ".repeat(3)}", ProfilerHarness.AgentId)
        prev :+ userMsg :+ agentMsg
    }.tail.toVector

    val profiles = framesByTurn.map { frames =>
      val view = ProfilerHarness.viewWith(frames)
      val req = ProfilerHarness.buildRequest(view, tools)
      ProfilerHarness.profile(req)
    }

    ProfilerHarness.writeReport(
      "long-conversation",
      "Phase 0 — Long Conversation (50 turns, no tool calls)",
      profiles
    )
  }
}
