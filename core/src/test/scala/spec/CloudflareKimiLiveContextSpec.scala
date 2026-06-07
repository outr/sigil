package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{AgentState, ToolInvoke}
import sigil.participant.DefaultAgentParticipant
import sigil.provider.cloudflare.CloudflareProvider
import sigil.provider.{GenerationSettings, Instructions, ReasoningMode}
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

import scala.concurrent.duration.*

/**
 * Live end-to-end proof that the discovery-relevance fix holds against the
 * real Kimi-K2.6 model — not just against the rendered context.
 *
 * The Sage scenario this reproduces: the user asks to find bug-number
 * references in code. The agent discovers a tool via `find_capability`,
 * then acts. Before the fix, that discovery surfaced `search_conversation`
 * / `semantic_search` as peers of `grep` (they keyword-match the generic
 * "search" / "find" terms); Kimi latched onto `search_conversation` and
 * spammed it dozens of times with empty args. With the relevance trim in
 * [[sigil.Sigil.findCapabilities]], only `grep` surfaces for a code-search
 * query, so the agent greps instead of degenerating.
 *
 * This drives the REAL agent loop — `find_capability` discovery, the
 * provider round-trip, and tool dispatch — over the live model, and asserts
 * the agent grepped and did NOT spam `search_conversation`.
 *
 * **Self-skips** when `SIGIL_LIVE` is unset or the Cloudflare credentials
 * are missing (via [[CloudflareLiveSupport.runGated]]).
 */
class CloudflareKimiLiveContextSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  KimiContextSigil.initFor(getClass.getSimpleName)

  // A full agent turn against live Kimi is multi-iteration (discover →
  // grep → respond) and runs under concurrent forked-JVM load; give it
  // generous headroom over the 1-minute default.
  override protected val testTimeout: FiniteDuration = 5.minutes

  private val apiTokenOpt: Option[String]  = sys.env.get("CLOUDFLARE_AUTH_TOKEN").filter(_.nonEmpty)
  private val accountIdOpt: Option[String] = sys.env.get("CLOUDFLARE_ACCOUNT_ID").filter(_.nonEmpty)

  private val modelId: Id[Model] = Model.id("cloudflare", "@cf/moonshotai/kimi-k2.6")

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    CloudflareLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  /** Create an isolated temp dir seeded with files that carry bug-number
    * references, so a grep over it returns real matches. */
  private def seedWorkspace(): java.nio.file.Path = {
    val dir = java.nio.file.Files.createTempDirectory("kimi-grep-")
    java.nio.file.Files.writeString(
      dir.resolve("Alpha.scala"),
      "object Alpha {\n  // Fix for bug #123 — guard the null case\n  val x = 1\n}\n"
    )
    java.nio.file.Files.writeString(
      dir.resolve("Beta.scala"),
      "object Beta {\n  /* relates to bug #456 */\n  def go(): Int = 2\n}\n"
    )
    java.nio.file.Files.writeString(
      dir.resolve("Clean.scala"),
      "object Clean {\n  def noop(): Unit = ()\n}\n"
    )
    dir
  }

  "Kimi-K2.6, driven through Sigil's real discovery + agent loop" should {
    "discover grep for a code-search task and not spam search_conversation" in {
      if (apiTokenOpt.isEmpty || accountIdOpt.isEmpty)
        cancel("CLOUDFLARE_AUTH_TOKEN / CLOUDFLARE_ACCOUNT_ID not set — skipping live Kimi context harness")

      KimiContextSigil.registerModel(modelId)
      KimiContextSigil.setProvider(CloudflareProvider(apiTokenOpt.get, accountIdOpt.get, KimiContextSigil))

      val workspace = seedWorkspace()

      // The agent starts with only the core roster (respond family +
      // find_capability). `grep` is NOT pre-granted — the agent must
      // DISCOVER it via find_capability, which is exactly the path that
      // used to also surface search_conversation/semantic_search.
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = modelId,
        toolNames = CoreTools.coreToolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings(
          maxOutputTokens = Some(16000),
          temperature     = Some(0.0),
          reasoningMode   = ReasoningMode.Off
        )
      )
      val convId = Conversation.id(s"kimi-live-ctx-${rapid.Unique()}")
      val conv = Conversation(
        topics = List(TestTopicEntry),
        _id = convId,
        participants = List(agent)
      )
      val now = Timestamp()
      val userMsg = sigil.event.Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text(
          s"Search the code under the directory `${workspace.toString}` for any references to bug " +
          s"numbers (e.g. text like \"bug #123\"). List the files and lines where they appear."
        )),
        state = EventState.Complete,
        timestamp = now
      )

      for {
        _ <- KimiContextSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- KimiContextSigil.publish(userMsg)
        _ <- waitForAgentTurn(convId, after = now.value, timeout = 4.minutes)
        all <- KimiContextSigil.withDB(_.events.transaction(_.list))
      } yield {
        deleteRecursive(workspace)
        val window = all.filter(e =>
          e.conversationId == convId
            && e.timestamp.value >= now.value
            && e.state == EventState.Complete
        ).sortBy(_.timestamp.value)
        val toolInvokes = window.collect { case ti: ToolInvoke => ti }
        val byName = toolInvokes.groupBy(_.toolName.value).view.mapValues(_.size).toMap
        val grepCalls = byName.getOrElse("grep", 0)
        val searchConvCalls = byName.getOrElse("search_conversation", 0)
        val semanticCalls = byName.getOrElse("semantic_search", 0)

        withClue(s"tool calls: ${byName.toList.sortBy(-_._2).mkString(", ")}: ") {
          // The fix's core promise: the agent reaches grep for a
          // code-search task...
          grepCalls should be >= 1
          // ...and does NOT degenerate into the conversation-search
          // tools that the poisoned discovery used to surface. A stray
          // single call is tolerable; the failure mode was dozens.
          searchConvCalls should be < 3
          semanticCalls should be < 3
        }
      }
    }
  }

  private def waitForAgentTurn(convId: Id[Conversation], after: Long, timeout: FiniteDuration): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = KimiContextSigil.withDB(_.events.transaction(_.list)).flatMap { all =>
      val settled = all.exists {
        case a: AgentState if a.conversationId == convId && a.timestamp.value >= after && a.state == EventState.Complete => true
        case _ => false
      }
      if (settled) Task.unit
      else if (System.currentTimeMillis() < deadline) Task.sleep(500.millis).flatMap(_ => loop)
      else Task.unit
    }
    loop
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit =
    if (java.nio.file.Files.exists(path)) {
      import scala.jdk.CollectionConverters.*
      if (java.nio.file.Files.isDirectory(path))
        java.nio.file.Files.list(path).iterator().asScala.foreach(deleteRecursive)
      java.nio.file.Files.delete(path)
    }

  "tear down" should {
    "dispose KimiContextSigil" in KimiContextSigil.shutdown.map(_ => succeed)
  }
}
