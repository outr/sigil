package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.Event
import sigil.signal.ToolDelta
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolContext, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Proves C — when a tool's result overflows `inlineContentThreshold`, the
 * frame must render the BOUNDED head (with the recovery path), not the full
 * output. The overflow already writes the full result to a file and builds a
 * bounded summary, but `buildResultDelta` left the full value on the invoke's
 * `output`, and `FrameBuilder` renders `output.text` in full — so a 106KB grep
 * result bloated the live prompt to ~63K tokens despite the overflow firing.
 */
class ToolOverflowFrameSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"overflow-${rapid.Unique()}")
  private val ctx: TurnContext = TurnContext(
    sigil        = TestSigil,
    chain        = List(TestUser),
    conversation = Conversation(topics = List(TopicEntry(TestTopicId, "t", "t")), _id = convId),
    turnInput    = TurnInput(ConversationView(conversationId = convId)),
    model        = TestSigil.defaultTestModel
  )

  private val bigText = "x" * 50000

  private case object BigOutputTool extends Tool {
    type Input  = OverflowProbeInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[OverflowProbeInput]]
    val outputRW = summon[RW[TextToolOutput]]
    override val name = ToolName("big_output")
    override val description = "Returns a result far larger than the inline threshold."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "overflow", "output"))
    )
    override def executeResult(input: OverflowProbeInput, c: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(bigText)))
  }

  "A tool whose result overflows inlineContentThreshold" should {
    "settle the invoke with a BOUNDED output, not the full result" in {
      BigOutputTool.execute(OverflowProbeInput(), ctx, Event.id()).toList.map { signals =>
        val delta = signals.collect { case d: ToolDelta if d.output.isDefined => d }.last
        val outText = delta.output.collect { case t: TextToolOutput => t.text }.getOrElse(bigText)
        withClue(s"settled output length=${outText.length} (full=${bigText.length}) head=${outText.take(40)}: ") {
          outText.length should be < bigText.length
          outText.toLowerCase should (include("truncated").or(include("written to")))
          // The bounded head must be the UNWRAPPED text, not the JSON
          // envelope — `summarize` previewing `{"text":"…"}` poisons the
          // prompt (inconsistent with non-overflow results, wastes tokens).
          outText should not include "{\"text\""
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}

case class OverflowProbeInput() extends sigil.tool.ToolInput derives RW
