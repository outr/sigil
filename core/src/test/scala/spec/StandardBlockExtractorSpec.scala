package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.ContextFrame
import sigil.conversation.compression.StandardBlockExtractor
import sigil.event.Event
import sigil.information.Information

/**
 * Mechanical coverage of [[StandardBlockExtractor]]. Uses TestSigil's
 * `onPutInformation` hook to capture writes — no real DB, no LLM.
 */
class StandardBlockExtractorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Minimal Information subtype for the spec. */
  case class BlockInfo(id: Id[Information], content: String) extends Information derives RW

  private def textFrame(s: String, id: String): ContextFrame.Text =
    ContextFrame.Text(s, TestUser, Id[Event](id))

  /** Reset TestSigil and wire a fresh recorder for each test. Returns
    * a getter that yields everything captured during the test body. */
  private def recorder(): () => Vector[Information] = {
    TestSigil.reset()
    val puts = new java.util.concurrent.atomic.AtomicReference(Vector.empty[Information])
    TestSigil.onPutInformation(info => puts.updateAndGet(_ :+ info))
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

    "pull long ToolResult content when extractToolResult is on" in {
      val puts = recorder()
      val extractor = StandardBlockExtractor(toInformation = (c, id) => BlockInfo(id, c), minChars = 20)
      val callId = Id[Event]("call-1")
      val longResult = "Y" * 60
      val frames = Vector[ContextFrame](
        ContextFrame.ToolResult(callId, longResult, Id[Event]("res-1"))
      )
      extractor.extract(TestSigil, frames).map { result =>
        val replaced = result.frames.head.asInstanceOf[ContextFrame.ToolResult]
        replaced.content should include("Information[")
        result.information should have size 1
        puts() should have size 1
      }
    }

  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
