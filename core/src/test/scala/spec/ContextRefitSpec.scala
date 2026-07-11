package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ToolCallState, TurnInput}
import sigil.conversation.compression.StandardContextCurator
import sigil.db.Model
import sigil.event.Event
import sigil.tool.ToolName

/**
 * Sigil #100 — when a turn routes to a model whose context window is
 * smaller than the one its context was curated against (complexity
 * reclassification, tier degrade, a credential that doesn't grant the
 * catalog window), the budget must be re-fit to the served model's
 * window. This is the SAME reduction flow as hitting the context limit
 * on the curated model — it just sizes down to a smaller one — so it
 * inherits the curator's non-lossy-first cascade: oversized frames
 * elide to a recoverable `reload_content` pointer rather than being
 * dropped, and pinned (critical) memories are never shed.
 */
class ContextRefitSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * A small-window model (cap = 0.8 * 20000 = 16000 tokens).
   */
  private val smallId: Id[Model] = Model.id("test", "refit-small")
  TestSigil.cache.merge(List(TestSigil.testModel(smallId).copy(contextLength = 20000L))).sync()

  private val curator = StandardContextCurator(TestSigil)
  private val chain = List(TestUser, TestAgent)

  /**
   * A TurnInput built for a large window: a single oversized tool
   * result (~200K chars, well over both the 2000-char elision
   * threshold and the small model's 16K-token budget) plus one pinned
   * memory. Comfortable on a 1M model; must be re-fit for a 20K one.
   */
  private def oversizedTurn(convId: Id[Conversation]): TurnInput = {
    val giant = ContextFrame.ToolCall(
      toolName = ToolName("big_tool"),
      argsJson = "{}",
      callId = Id[Event]("refit-big-call"),
      participantId = TestAgent,
      sourceEventId = Id[Event]("refit-big-evt"),
      state = ToolCallState.Complete("PAYLOAD-" + ("x" * 200000))
    )
    TurnInput(
      conversationId = convId,
      frames = Vector(giant),
      criticalMemories = Vector(Id[ContextMemory]("refit-pinned"))
    )
  }

  "Re-fitting a curated TurnInput to a smaller window (#100)" should {

    "elide the oversized frame to a recoverable reload_content pointer (non-lossy), not drop it" in {
      val convId = Conversation.id(s"refit-${rapid.Unique()}")
      curator.refit(oversizedTurn(convId), smallId, chain).map { out =>
        val frame = out.frames.collectFirst {
          case tc: ContextFrame.ToolCall if tc.toolName == ToolName("big_tool") => tc
        }.getOrElse(fail(s"expected the big_tool frame to survive; saw ${out.frames.map(_.getClass.getSimpleName)}"))
        val content = frame.state match {
          case ToolCallState.Complete(c, _) => c
          case other => fail(s"expected a Complete tool frame, got $other")
        }
        withClue(s"refit frame content (${content.length} chars): ${content.take(120)}: ") {
          // The full payload is gone from the prompt...
          content should not include ("x" * 200000)
          content.length should be < 2000
          // ...replaced by a durable pointer the agent can reload on demand.
          content should include("reload_content")
          content should include("refit-big-evt")
        }
      }
    }

    "never shed pinned (critical) memories when sizing down" in {
      val convId = Conversation.id(s"refit-${rapid.Unique()}")
      val input = oversizedTurn(convId)
      curator.refit(input, smallId, chain).map { out =>
        out.criticalMemories shouldBe input.criticalMemories
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
