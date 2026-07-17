package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, ToolCallState}
import sigil.conversation.compression.StandardBlockExtractor
import sigil.event.Event
import sigil.information.Information

/**
 * Mechanical coverage of [[StandardBlockExtractor]]. Uses TestSigil's
 * `onPutInformations` hook to capture writes — no real DB, no LLM.
 */
class StandardBlockExtractorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Minimal Information subtype for the spec.
   */
  case class BlockInfo(id: Id[Information], content: String) extends Information derives RW

  private def textFrame(s: String, id: String): ContextFrame.Text =
    ContextFrame.Text(s, TestUser, Id[Event](id))

  /**
   * Reset TestSigil and wire a fresh recorder for each test. Returns
   * a getter that yields everything captured during the test body.
   */
  private def recorder(): () => Vector[Information] = {
    TestSigil.reset()
    val puts = new java.util.concurrent.atomic.AtomicReference(Vector.empty[Information])
    TestSigil.onPutInformations(batch => puts.updateAndGet(_ ++ batch))
    () => puts.get()
  }

  "StandardBlockExtractor" should {
    "leave frames shorter than minChars untouched" in {
      val puts = recorder()
      val extractor = StandardBlockExtractor(toInformation = (c, id) => BlockInfo(id, c), minChars = 100)
      val frames = Vector(textFrame("short", "s1"), textFrame("also short", "s2"))
      extractor.extract(TestSigil, frames).map { result =>
        result.frames shouldBe frames
        result.information shouldBe empty
        puts() shouldBe empty
      }
    }

    "pull a long Text frame's content into an Information record and replace with a placeholder" in {
      val puts = recorder()
      val extractor = StandardBlockExtractor(toInformation = (c, id) => BlockInfo(id, c), minChars = 20)
      val big = "X" * 50
      val frames = Vector(textFrame(big, "big"), textFrame("short", "s"))
      extractor.extract(TestSigil, frames).map { result =>
        result.information should have size 1
        val replaced = result.frames.head.asInstanceOf[ContextFrame.Text]
        replaced.content should not be big
        replaced.content should include("Information[")
        result.frames(1) shouldBe frames(1)
        puts() should have size 1
        puts().head.asInstanceOf[BlockInfo].content shouldBe big
      }
    }

    "leave ToolCall frames untouched regardless of result size (sigil bug #201)" in {
      val puts = recorder()
      val extractor = StandardBlockExtractor(toInformation = (c, id) => BlockInfo(id, c), minChars = 20)
      val callId = Id[Event]("call-1")
      val longResult = "Y" * 4000
      val frames = Vector[ContextFrame](
        ContextFrame.ToolCall(
          toolName = sigil.tool.ToolName("noop"),
          argsJson = "{}",
          callId = callId,
          participantId = TestUser,
          sourceEventId = Id[Event]("res-1"),
          state = ToolCallState.Complete(longResult)
        )
      )
      extractor.extract(TestSigil, frames).map { result =>
        val preserved = result.frames.head.asInstanceOf[ContextFrame.ToolCall]
        preserved.state shouldBe ToolCallState.Complete(longResult)
        result.information shouldBe empty
        puts() shouldBe empty
      }
    }

    // Sigil #393 — the externalized id must be DETERMINISTIC for a given
    // content, so the placeholder bytes are identical across turns (keeping the
    // prompt-cache prefix stable) and the store de-duplicates.
    "mint the SAME Information id for the same content across separate extract passes (#393)" in {
      val puts = recorder()
      val extractor = StandardBlockExtractor(toInformation = (c, id) => BlockInfo(id, c), minChars = 20)
      val big = "Z" * 60
      def placeholderOf(content: String) =
        extractor.extract(TestSigil, Vector(textFrame(content, "f"))).map { r =>
          r.frames.head.asInstanceOf[ContextFrame.Text].content
        }
      for {
        a <- placeholderOf(big)
        b <- placeholderOf(big) // a second context build of the same content
      } yield {
        a shouldBe b // identical placeholder text → cache prefix holds
        // And it matches the deterministic id directly.
        a should include(s"Information[${StandardBlockExtractor.deterministicId(big).value}]")
      }
    }

    "mint DIFFERENT ids for different content" in {
      StandardBlockExtractor.deterministicId("alpha content") should not be
        StandardBlockExtractor.deterministicId("beta content")
      // Same content, same id (pure function).
      StandardBlockExtractor.deterministicId("same") shouldBe StandardBlockExtractor.deterministicId("same")
    }

  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
