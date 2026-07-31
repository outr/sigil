package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.{Event, ToolInvoke, ToolOutcome}
import sigil.provider.ConversationMode
import sigil.signal.EventState
import sigil.tool.ToolName

/**
 * Coverage for bugs #203–#205:
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

  /** Publish a settled `ToolInvoke` carrying the typed output (or a
    * placeholder TextToolOutput when `typed` is None). Sigil #265
    * collapsed the tool transaction onto the invoke itself, so the
    * projection-side machinery reads `ti.output` directly. */
  private def publishToolResults(convId: Id[Conversation],
                                 toolName: String,
                                 typed: Option[sigil.tool.ToolOutput] = None): Task[Unit] = {
    val invoke = ToolInvoke(
      toolName       = ToolName.parse(toolName).fold(sys.error, identity),
      participantId  = TestAgent,
      conversationId = convId,
      topicId        = TestTopicEntry.id,
      output         = typed.getOrElse(sigil.tool.TextToolOutput("")),
      outcome        = ToolOutcome.Success,
      state          = EventState.Complete
    )
    TestSigil.publish(invoke).unit
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

    "ChangeModeTool.description carries the audit-current-mode / per-turn-fit guidance" in {
      val desc = sigil.tool.core.ChangeModeTool.description
      desc should include("per-turn fits")
      desc should include("switch away")
    }

    "the system prompt's tools guidance stays tool-agnostic (no change_mode triage)" in {
      val guidance = sigil.provider.Instructions.DefaultToolsGuidance
      guidance should not include "change_mode"
      guidance should not include "STEP 0"
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

  "Bug #205: TurnContext carries the routed Model" should {

    "TurnContext exposes the routed Model record for downstream consumers" in {
      // Sigil #277 — TurnContext.routedModelId was replaced by the
      // required `model: Model` field. The boundary resolves the
      // routed id to a Model record; the modelId accessor reflects it.
      val routedModelId = sigil.db.Model.id("openrouter", "frontier-model")
      val ctx = sigil.TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser),
        conversation = Conversation(topics = TestTopicStack, _id = freshConvId()),
        turnInput    = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = freshConvId())),
        model        = TestSigil.testModel(routedModelId)
      )
      ctx.modelId.value shouldBe "openrouter/frontier-model"
    }

    "TurnContext default model is the synthetic test fixture" in {
      val ctx = sigil.TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser),
        conversation = Conversation(topics = TestTopicStack, _id = freshConvId()),
        turnInput    = sigil.conversation.TurnInput(sigil.conversation.ConversationView(conversationId = freshConvId())),
        model        = TestSigil.defaultTestModel
      )
      ctx.modelId shouldBe TestSigil.defaultTestModel._id
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
