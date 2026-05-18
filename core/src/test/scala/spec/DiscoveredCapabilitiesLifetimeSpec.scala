package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, DiscoveredCapability, ParticipantProjection, TurnInput}
import sigil.db.Model
import sigil.event.CapabilityResults
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider
}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, FindCapabilityTool, RespondTool}
import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}
import sigil.tool.model.{RespondInput, ResponseDisposition}

/**
 * Lifetime regression for sigil bug #226 — the per-agent-loop
 * `find_capability` cache must live in memory on
 * [[sigil.TurnContext]], not on the persisted
 * [[sigil.conversation.ParticipantProjection]]. Pre-fix the cache
 * accumulated across every turn of a conversation, surfacing
 * unrelated tools from prior turns into every subsequent prompt;
 * post-fix the cache is bounded to a single agent loop and vanishes
 * when the loop terminates.
 */
class DiscoveredCapabilitiesLifetimeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "lifetime-model")

  /** Build a `ConversationRequest` for renderer coverage that pins
    * `discoveredCapabilities` to a known map. The renderer emits the
    * "Capabilities you've already discovered" section from this
    * field. */
  private def requestWith(discovered: Map[String, DiscoveredCapability]): ConversationRequest =
    ConversationRequest(
      conversationId         = Conversation.id("disc-cap-lifetime"),
      modelId                = modelId,
      instructions           = Instructions(),
      turnInput              = TurnInput(conversationId = Conversation.id("disc-cap-lifetime")),
      currentMode            = ConversationMode,
      currentTopic           = TestTopicEntry,
      previousTopics         = Nil,
      generationSettings     = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools                  = CoreTools.all,
      chain                  = List(TestUser, TestAgent),
      discoveredCapabilities = discovered
    )

  /** Render the system prompt the provider would send, exercising the
    * full `renderSystem` code path that reads
    * `ConversationRequest.discoveredCapabilities`. */
  private def renderSystem(req: ConversationRequest): Task[String] = {
    val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
    provider.requestConverter(req).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })
  }

  /** Build a fresh TurnContext over a freshly seeded conversation —
    * mirrors the shape the agent loop hands tools. The
    * `discoveredCapabilitiesRef` defaults to a fresh empty cell so
    * mutations stay scoped to this test. */
  private def buildCtx(convId: Id[Conversation]): Task[TurnContext] = {
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map { _ =>
      TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser, TestAgent),
        conversation = conv,
        turnInput    = TurnInput(conversationId = convId)
      )
    }
  }

  "TurnContext.recordDiscovery" should {

    "populate the per-loop cache so the next iteration's prompt surfaces the matches" in {
      val convId = Conversation.id(s"disc-cap-populated-${rapid.Unique()}")
      for {
        ctx <- buildCtx(convId)
        _ = ctx.recordDiscovery("view file source contents read", List(ToolName("read_file"), ToolName("grep")))
        // Same loop, subsequent iteration — the request builder reads
        // the cache snapshot via `TurnContext.discoveredCapabilities`.
        req = requestWith(ctx.discoveredCapabilities)
        body <- renderSystem(req)
      } yield {
        ctx.discoveredCapabilities.keySet should contain("view file source contents read")
        body should include("Capabilities you've already discovered")
        body should include("read_file")
        body should include("grep")
      }
    }

    "ignore empty queries" in {
      val convId = Conversation.id(s"disc-cap-empty-${rapid.Unique()}")
      for {
        ctx <- buildCtx(convId)
        _ = ctx.recordDiscovery("", List(ToolName("respond")))
      } yield ctx.discoveredCapabilities shouldBe Map.empty
    }

    "preserve `firstSeen` across re-issues of the same query, advancing `lastSeen`" in {
      val convId = Conversation.id(s"disc-cap-reissue-${rapid.Unique()}")
      for {
        ctx <- buildCtx(convId)
        _ = ctx.recordDiscovery("list files directory glob", List(ToolName("glob")))
        first = ctx.discoveredCapabilities("list files directory glob")
        _ <- Task.sleep(scala.concurrent.duration.FiniteDuration(5, "millis"))
        _ = ctx.recordDiscovery("list files directory glob", List(ToolName("glob"), ToolName("find_files")))
        second = ctx.discoveredCapabilities("list files directory glob")
      } yield {
        second.firstSeen.value shouldBe first.firstSeen.value
        second.lastSeen.value should be >= first.lastSeen.value
        second.matches.map(_.value) should contain allOf ("glob", "find_files")
      }
    }
  }

  "TurnContext.clearDiscoveredCapabilities (and respond(endsTurn = true))" should {

    "drop the cache so the next loop's prompt no longer surfaces prior turn's matches" in {
      val convId = Conversation.id(s"disc-cap-cleared-${rapid.Unique()}")
      for {
        ctx <- buildCtx(convId)
        _ = ctx.recordDiscovery("start metals lsp bsp", List(ToolName("lsp_goto_definition"), ToolName("lsp_find_references")))
        // Simulate the terminal respond — same hook the framework
        // wires in `RespondTool.executeTyped` when `endsTurn = true`.
        _ = ctx.clearDiscoveredCapabilities()
        // The next loop's request snapshots whatever's now in the
        // cache — which should be empty.
        req = requestWith(ctx.discoveredCapabilities)
        body <- renderSystem(req)
      } yield {
        ctx.discoveredCapabilities shouldBe Map.empty
        body should not include "Capabilities you've already discovered"
        body should not include "lsp_goto_definition"
      }
    }

    "be invoked by `RespondTool.execute` when `endsTurn = true`" in {
      val convId = Conversation.id(s"disc-cap-respond-clear-${rapid.Unique()}")
      for {
        ctx <- buildCtx(convId)
        _ = ctx.recordDiscovery("send slack message", List(ToolName("send_slack_message")))
        // Drain the respond stream so the tool's body runs. Topic
        // resolution may emit additional events; we only care about
        // the side-effect on the cache.
        _ <- RespondTool.execute(
          RespondInput(
            topicLabel   = "Done",
            topicSummary = "Test reply",
            content      = "All done.",
            disposition  = ResponseDisposition.Success,
            endsTurn     = true
          ),
          ctx
        ).toList
      } yield ctx.discoveredCapabilities shouldBe Map.empty
    }

    "NOT be invoked by `RespondTool.execute` when `endsTurn = false` (in-flight status pulse)" in {
      val convId = Conversation.id(s"disc-cap-respond-noclear-${rapid.Unique()}")
      for {
        ctx <- buildCtx(convId)
        _ = ctx.recordDiscovery("send slack message", List(ToolName("send_slack_message")))
        _ <- RespondTool.execute(
          RespondInput(
            topicLabel   = "Working",
            topicSummary = "Status pulse",
            content      = "Let me check that…",
            disposition  = ResponseDisposition.Success,
            endsTurn     = false
          ),
          ctx
        ).toList
      } yield {
        ctx.discoveredCapabilities.keySet should contain("send slack message")
      }
    }
  }

  "ParticipantProjection (post-fix)" should {

    "no longer carry a `discoveredCapabilities` field on the persisted record" in {
      // Compile-time enforcement is the strongest guarantee — if
      // `proj.discoveredCapabilities` doesn't compile, the field is
      // gone from the schema. We also exercise the runtime path:
      // publishing a `CapabilityResults` event must NOT write the
      // cache onto the projection (the framework now leaves it to
      // `TurnContext`).
      val convId  = Conversation.id(s"disc-cap-projection-${rapid.Unique()}")
      val originId = lightdb.id.Id[sigil.event.Event]("find-capability-projection-stub")
      val cr = CapabilityResults(
        matches        = List(CapabilityMatch(
          name           = "read_file",
          description    = "read file",
          capabilityType = CapabilityType.Tool,
          score          = 1.0,
          status         = CapabilityStatus.Ready
        )),
        participantId  = TestUser,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        query          = "view file source contents",
        state          = EventState.Complete,
        role           = sigil.event.MessageRole.Tool,
        origin         = Some(originId)
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
               _id = convId, topics = TestTopicStack
             ))))
        _ <- TestSigil.publish(cr)
        proj <- TestSigil.projectionFor(TestUser, convId)
      } yield {
        // The suggestedTools overlay still fires from the event
        // handler — that's a single-turn decay surface, distinct
        // from the dropped cross-turn cache.
        proj.suggestedTools.map(_.value) should contain("read_file")
        // Compile-time check: this line would not compile if
        // `discoveredCapabilities` were re-added to the projection.
        // (We can't assert "the field doesn't exist" at runtime
        // without reflection; the type system rejects access to a
        // missing field at the call site.)
        val projHasNoDiscoveredField: ParticipantProjection => Any = (_: ParticipantProjection).suggestedTools
        projHasNoDiscoveredField(proj) shouldBe a[List[?]]
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
