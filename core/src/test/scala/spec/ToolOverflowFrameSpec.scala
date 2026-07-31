package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.signal.ToolDelta
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolContext,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

/**
 * When a tool's result overflows `inlineContentThreshold`, the frame must
 * render the BOUNDED head (with the recovery path), not the full output —
 * a 106KB grep result must not bloat the live prompt. The typed output
 * stays the real value on the invoke ([[sigil.tool.ToolResultEnvelope]]
 * semantics); the bounding is carried by `summary` + `overflow`, and
 * `FrameBuilder` renders the summary for the model-facing channel.
 */
class ToolOverflowFrameSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"overflow-${rapid.Unique()}")
  private val ctx: TurnContext = TurnContext(
    sigil = TestSigil,
    chain = List(TestUser),
    conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
    turnInput = TurnInput(ConversationView(conversationId = convId)),
    model = TestSigil.defaultTestModel
  )

  private val bigText = "x" * 50000

  private case object BigOutputTool extends Tool {
    type Input = OverflowProbeInput
    type Output = TextToolOutput
    val io: ToolIO[OverflowProbeInput, TextToolOutput] = ToolIO.derived[OverflowProbeInput, TextToolOutput]
    override val name = ToolName("big_output")
    override val description = "Returns a result far larger than the inline threshold."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "overflow", "output"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

    private def executeResult(input: OverflowProbeInput, c: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(bigText)))
  }

  "A tool whose result overflows inlineContentThreshold" should {
    "settle the invoke with a bounded model-facing frame while preserving the typed output" in {
      val invokeId = Event.id()
      BigOutputTool.execute(OverflowProbeInput(), ctx, invokeId).toList.map { signals =>
        val delta = signals.collect { case d: ToolDelta if d.output.isDefined => d }.last
        // The typed output is PRESERVED — bounding never type-launders.
        delta.output.collect { case t: TextToolOutput => t.text } shouldBe Some(bigText)
        delta.overflow shouldBe defined
        val summary = delta.summary.getOrElse("")
        withClue(s"summary length=${summary.length} (full=${bigText.length}) head=${summary.take(40)}: ") {
          summary.length should be < bigText.length
          summary.toLowerCase should (include("truncated").or(include("written to")))
          // The bounded head must be the UNWRAPPED text, not the JSON
          // envelope — `summarize` previewing `{"text":"…"}` poisons the
          // prompt (inconsistent with non-overflow results, wastes tokens).
          summary should not include "{\"text\""
        }
        // The model-facing frame renders the bounded summary, never the
        // full output — the prompt stays lean.
        val invoke = sigil.event.ToolInvoke(
          toolName = BigOutputTool.name,
          participantId = TestUser,
          conversationId = convId,
          topicId = ctx.conversation.currentTopicId,
          _id = invokeId
        )
        val settled = delta.apply(invoke)
        val frame = sigil.conversation.FrameBuilder.computeFrame(settled)
        val frameText = frame.collect {
          case tc: sigil.conversation.ContextFrame.ToolCall => tc.state
        }.collect {
          case sigil.conversation.ToolCallState.Complete(content, _) => content
        }.getOrElse(fail(s"expected a Complete ToolCall frame, got $frame"))
        frameText.length should be < bigText.length
        frameText shouldBe summary
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

case class OverflowProbeInput() extends sigil.tool.ToolInput derives RW
