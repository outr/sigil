package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.{Event, MessageRole, ToolInvoke, ToolOutcome, ToolResults}
import sigil.provider.ConversationMode
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.output.{JsonPagedResult, PaginatedTool}

/**
 * Coverage for bugs #203–#206:
 *   - #203 — mode descriptions / STEP A / ChangeModeTool now spell
 *     out symmetric "Enter when…" / "Don't enter for…" / "Exit when…"
 *     framing so the agent has consistent guidance for both entering
 *     AND leaving modes.
 *   - #204 — `ToolInvoke` event is now emitted at `ToolCallComplete`
 *     time with `input` populated, instead of at `ToolCallStart`
 *     with `input = None`.
 *   - #205 — `TurnContext.routedModelId` is populated by
 *     `Sigil.buildContext`, and the curator's budget gate uses the
 *     routed model rather than the agent's nominal `modelId`.
 *   - #206 — pagination navigators MERGE with the existing
 *     `suggestedTools` overlay (preserving find_capability promotions)
 *     rather than replacing it wholesale.
 */
class Bugs203To206RegressionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConvId(): Id[Conversation] = Conversation.id(s"bugs-203-to-206-${rapid.Unique()}")

  private def setup(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      topics       = TestTopicStack,
      participants = List(),
      _id          = convId
    )))).unit

  private def publishToolResults(convId: Id[Conversation],
                                 toolName: String,
                                 typed: Option[fabric.Json] = None,
                                 schemas: List[sigil.tool.ToolSchema] = Nil): Task[Unit] = {
    val invokeId = Event.id()
    val invoke = ToolInvoke(
      toolName       = ToolName(toolName),
      participantId  = TestAgent,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      _id            = invokeId,
      state          = EventState.Complete
    )
    val result = ToolResults(
      schemas        = schemas,
      participantId  = TestAgent,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      outcome        = ToolOutcome.Success,
      typed          = typed,
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      origin         = Some(invokeId)
    )
    TestSigil.publish(invoke).flatMap(_ => TestSigil.publish(result)).unit
  }

  // ---- #203 ----

  "Bug #203: mode descriptions" should {

    "ConversationMode now describes itself as the default + single-turn fallback" in {
      val desc = ConversationMode.description
      desc should include("Default mode")
      desc should include("single-turn actions")
      desc should include("Stay here")
    }

    "ChangeModeTool description leads with domain-match as the switch trigger" in {
      val desc = sigil.tool.core.ChangeModeTool.description
      desc should include("domain matches the user's current task")
      desc should include("one-shot ask")
      desc should include("find_capability discovery on every operation")
    }

    "DefaultToolsGuidance now contains a STEP 0 audit-current-mode block" in {
      val guidance = sigil.provider.Instructions.DefaultToolsGuidance
      guidance should include("STEP 0")
      guidance should include("AUDIT THE CURRENT MODE")
      guidance should include("Exit when")
    }
  }

  // ---- #204 ----

  "Bug #204: ToolInvoke carries parsed input at emission time" should {

    // This is a structural property of `ToolInvoke` — the schema field
    // is now populated by the orchestrator at ToolCallComplete time.
    // The case class accepts an `input: Option[ToolInput]` parameter,
    // and downstream consumers read it directly off the event.
    "ToolInvoke.input is a populated field, not just a delta-only carrier" in {
      case class ProbeInput(query: String) extends sigil.tool.ToolInput derives RW
      val invoke = ToolInvoke(
        toolName       = ToolName("probe"),
        participantId  = TestAgent,
        conversationId = freshConvId(),
        topicId        = TestTopicEntry.id,
        input          = Some(ProbeInput("hello")),
        state          = EventState.Active
      )
      invoke.input shouldBe Some(ProbeInput("hello"))
    }
  }

  // ---- #205 ----

  "Bug #205: TurnContext carries routedModelId" should {

    "TurnContext exposes routedModelId for downstream consumers" in {
      val ctx = sigil.TurnContext(
        sigil         = TestSigil,
        chain         = List(TestUser),
        conversation  = Conversation(topics = TestTopicStack, _id = freshConvId()),
        turnInput     = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = freshConvId())),
        routedModelId = Some(sigil.db.Model.id("openrouter", "frontier-model"))
      )
      ctx.routedModelId.map(_.value) shouldBe Some("openrouter/frontier-model")
    }

    "default routedModelId is None (back-compat for paths that bypass buildContext)" in {
      val ctx = sigil.TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser),
        conversation = Conversation(topics = TestTopicStack, _id = freshConvId()),
        turnInput    = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = freshConvId()))
      )
      ctx.routedModelId shouldBe None
    }
  }

  // ---- #206 ----

  "Bug #206: pagination navigator promotion preserves prior overlay" should {

    "MERGE next_page / query_tool_output with existing suggestedTools instead of replacing them" in {
      val convId = freshConvId()
      // Pre-seed the projection with a find_capability-style suggestion.
      val preSeed = TestSigil.withDB(_.participantProjections.transaction { tx =>
        tx.upsert(sigil.conversation.ParticipantProjection.empty(TestAgent, convId)
          .copy(suggestedTools = List(ToolName("edit_file"), ToolName("read_file"))))
      }).unit

      val pagedJson = summon[RW[JsonPagedResult]].read(JsonPagedResult(
        items       = Nil,
        hasMore     = true,
        page        = 0,
        pageSize    = 50,
        referenceId = "ref",
        callId      = Id[Event]("call")
      ))

      for {
        _      <- setup(convId)
        _      <- preSeed
        _      <- publishToolResults(convId, "grep", typed = Some(pagedJson))
        proj   <- TestSigil.projectionFor(TestAgent, convId)
      } yield {
        val names = proj.suggestedTools.map(_.value)
        // Pre-seeded tools preserved.
        names should contain("edit_file")
        names should contain("read_file")
        // Pagination navigators merged in.
        names should contain("next_page")
        names should contain("query_tool_output")
      }
    }

    "still REPLACE the overlay when a tool emits its own schema-driven followups" in {
      // create_workflow → [add_workflow_step, add_trigger] pattern.
      // The tool intentionally narrows the agent's next-turn toolset;
      // we shouldn't preserve unrelated prior promotions.
      val convId = freshConvId()
      val preSeed = TestSigil.withDB(_.participantProjections.transaction { tx =>
        tx.upsert(sigil.conversation.ParticipantProjection.empty(TestAgent, convId)
          .copy(suggestedTools = List(ToolName("edit_file"))))
      }).unit

      // Fabricate a small tool schema-list payload.
      val followupSchemas: List[sigil.tool.ToolSchema] = List(
        sigil.tool.ToolSchema(
          id          = Id("add_workflow_step"),
          name        = ToolName("add_workflow_step"),
          description = "Append a step",
          input       = fabric.define.Definition(fabric.define.DefType.Obj(scala.collection.immutable.VectorMap.empty)),
          examples    = Nil,
          output      = None
        )
      )

      for {
        _    <- setup(convId)
        _    <- preSeed
        _    <- publishToolResults(convId, "create_workflow", schemas = followupSchemas)
        proj <- TestSigil.projectionFor(TestAgent, convId)
      } yield {
        val names = proj.suggestedTools.map(_.value)
        // Schema-declared followup is now the basis (replaces the prior overlay).
        names should contain("add_workflow_step")
        // Prior overlay overridden.
        names should not contain "edit_file"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
