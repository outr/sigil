package bench.contextprofile

import sigil.conversation.{ContextFrame, ParticipantProjection}
import sigil.tool.{Tool, ToolName}
import sigil.tool.core.{FindCapabilityTool, RespondTool, StopTool}

/**
 * Phase 0 — tool-heavy profile. Agent has 25 fake tools registered
 * (each with a realistic ~150-char description); each turn includes a
 * `find_capability` → `<discovered tool>` → `ToolResult` cycle. Tracks
 * how the tool roster + ToolCall/ToolResult frames contribute relative
 * to chat content.
 *
 * Run: `sbt "benchmark/runMain bench.contextprofile.ToolHeavyBench"`
 * Output: `benchmark/profiles/tool-heavy.md`
 */
object ToolHeavyBench {

  private val descriptions = Vector(
    "Send a Slack message to a specific channel. Pass channel name and message text.",
    "Fetch the contents of a URL via HTTP GET, returning markdown-rendered HTML.",
    "Search the web via the configured provider. Returns top-10 ranked snippets.",
    "Read a file from the local filesystem. Optional offset/limit for windows.",
    "Write a file to disk. Creates parent dirs as needed; refuses overwrite without flag.",
    "Edit a file by replacing one exact substring with another. Fails if not unique.",
    "Glob the filesystem for paths matching a shell pattern. Bounded by maxResults.",
    "Grep text across files matching a glob, returning matched lines + context.",
    "Run a bash command. Captures stdout + stderr; reports exit code.",
    "Look up an Information record by id. Returns the full body as a Message.",
    "Save a memory record under a key for later semantic / keyword recall.",
    "Recall memories by semantic query. Returns ranked hits with scores.",
    "Activate a discovered Skill, loading its prompt overlay into the agent.",
    "Sleep for N milliseconds. Yields to other agents while waiting.",
    "Report the current system stats — CPU, memory, disk usage.",
    "Search persisted conversation events by Lucene query. Returns matched messages.",
    "Send an email with attachments. Requires SMTP config in app settings.",
    "Translate text between two languages. Supports 90+ language pairs.",
    "Summarize a block of text into N sentences (default 3).",
    "Generate an image from a prompt. Returns a URL to the generated image.",
    "Look up a stock price by ticker. Returns current bid/ask + last close.",
    "Convert currency at the latest interbank rate. Returns the converted value.",
    "Geocode a place name to lat/lon coordinates with country / region metadata.",
    "Reverse-geocode lat/lon to a place name. Returns nearest city/state/country.",
    "Extract named entities (people / orgs / places) from a block of text."
  )

  private def fakeTools(count: Int): Vector[Tool] =
    (0 until count).iterator.map { i =>
      new ProfilerHarness.FakeTool(s"tool_$i", descriptions(i % descriptions.size))
    }.toVector

  def main(args: Array[String]): Unit = {
    val turns = 30
    val toolRoster: Vector[Tool] =
      Vector(RespondTool, FindCapabilityTool, StopTool) ++ fakeTools(25)

    // Each turn is: user msg → find_capability → ToolResult → tool_5 call → ToolResult → respond.
    val framesByTurn: Vector[Vector[ContextFrame]] = {
      val builder = scala.collection.mutable.ArrayBuffer.empty[Vector[ContextFrame]]
      var current = Vector.empty[ContextFrame]
      (1 to turns).foreach { n =>
        current :+= ProfilerHarness.textFrame(s"User turn $n: please find a tool that can help with task $n.")
        val fcCall = ProfilerHarness.toolCallFrame("find_capability", s"""{"keywords":"task $n action"}""")
        current :+= fcCall
        current :+= ProfilerHarness.toolResultFrame(fcCall.callId,
          s"""{"matches":[{"name":"tool_${n % 25}","description":"${descriptions(n % descriptions.size)}","capabilityType":"Tool","score":${25 - n % 25},"status":{"type":"Ready"}}]}""")
        val toolCall = ProfilerHarness.toolCallFrame(s"tool_${n % 25}", s"""{"value":"input for turn $n"}""")
        current :+= toolCall
        current :+= ProfilerHarness.toolResultFrame(toolCall.callId, s"""{"result":"tool_${n % 25} executed for turn $n"}""")
        current :+= ProfilerHarness.textFrame(s"Agent reply $n: I used tool_${n % 25}; here are the results.", ProfilerHarness.AgentId)
        builder += current
      }
      builder.toVector
    }

    val profiles = framesByTurn.zipWithIndex.map { case (frames, idx) =>
      val turnNumber = idx + 1
      // Suggested tools list grows with discovered names — single-turn ephemeral
      // per `decaySuggestedTools`, so we add only the most recently discovered.
      val suggested = List(ToolName(s"tool_${turnNumber % 25}"))
      val projections: Map[sigil.participant.ParticipantId, ParticipantProjection] = Map(
        ProfilerHarness.AgentId -> ParticipantProjection(suggestedTools = suggested)
      )
      val view = ProfilerHarness.viewWith(frames, projections)
      val req = ProfilerHarness.buildRequest(view, toolRoster)
      ProfilerHarness.profile(req)
    }

    ProfilerHarness.writeReport(
      "tool-heavy",
      "Phase 0 — Tool-Heavy (25 tools, find_capability + tool_call cycles)",
      profiles
    )
  }
}
