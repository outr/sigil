package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ContextFrame, Conversation, TurnInput}
import sigil.diagnostics.{ProfileSection, RequestProfiler}
import sigil.event.Event
import sigil.provider.{ContextFeatures, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  ResolvedReferences, SectionContext}
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.ToolContext
import sigil.tool.context.{ContextBreakdownInput, ContextBreakdownTool}
import sigil.tool.model.ResponseContent

/**
 * `context_breakdown` is the agent's answer to "where is my context
 * going?", so its numbers must BE the framework's accounting — the same
 * [[RequestProfiler]] over the same section list — rather than a second
 * hand-rolled tally that drifts from the wire.
 */
class ContextBreakdownAccountingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("context-breakdown-conv")

  private val frames: Vector[ContextFrame] = Vector(
    ContextFrame.Text(
      content = "please sweep the config files and report what you find",
      participantId = TestUser,
      sourceEventId = Event.id()),
    ContextFrame.Text(
      content = "on it — searching now",
      participantId = TestAgent,
      sourceEventId = Event.id())
  )

  private val turn = TurnInput(conversationId = convId, frames = frames)

  private val conversation = Conversation(
    topics = TestTopicStack,
    participants = Nil,
    _id = convId
  )

  private def toolContext: ToolContext = {
    val turnCtx = TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = conversation,
      turnInput = turn,
      model = TestSigil.defaultTestModel
    )
    ToolContext(turnCtx, Event.id(), ContextBreakdownTool.name)
  }

  /** The profile the framework itself would compute for this turn. The
    * turn references no memories or summaries, so the resolved set is
    * empty by construction. */
  private def referenceProfile: Task[sigil.diagnostics.RequestProfile] = {
    val resolved = ResolvedReferences(Vector.empty, Vector.empty, Vector.empty)
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.defaultTestModel,
      instructions = Instructions(),
      turnInput = turn,
      currentMode = ConversationMode,
      currentTopic = conversation.currentTopic,
      previousTopics = conversation.previousTopics,
      generationSettings = GenerationSettings(),
      chain = List(TestUser, TestAgent)
    )
    val base = SectionContext(request, resolved, TestSigil.discoveredCapabilitiesPromptCap,
      promptShape = TestSigil.modelProfileFor(TestSigil.defaultTestModel).promptShape)
    ContextFeatures.evaluate(TestSigil.enabledContextFeatures, base).map { ctx =>
      RequestProfiler.profile(ctx, HeuristicTokenizer, TestSigil, TestSigil.resolvedContextSections)
    }
  }

  "context_breakdown" should {

    "report the profiler's total for the turn" in {
      for {
        expected <- referenceProfile
        out      <- ContextBreakdownTool.invoke(ContextBreakdownInput(), toolContext)
      } yield {
        out.totalTokens shouldBe expected.total
        out.totalTokens should be > 0
      }
    }

    "report the profiler's per-section numbers, section for section" in {
      for {
        expected <- referenceProfile
        out      <- ContextBreakdownTool.invoke(ContextBreakdownInput(), toolContext)
      } yield {
        out.sections.map(s => s.section -> s.tokens).toMap shouldBe expected.sections
      }
    }

    "count the conversation's frames and name the current mode" in {
      ContextBreakdownTool.invoke(ContextBreakdownInput(), toolContext).map { out =>
        out.currentMode shouldBe ConversationMode.name
        out.sections.find(_.section == ProfileSection.Frames).map(_.count) shouldBe Some(frames.size)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
