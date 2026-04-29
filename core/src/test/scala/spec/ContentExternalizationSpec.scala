package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, ResponseContentOps}
import sigil.tool.model.ResponseContentOps.dereference

/**
 * Coverage for BUGS.md #19 — content externalization pipeline:
 *   - `Sigil.inlineContentThreshold` + `ContentExternalizationTransform`
 *     rewrite oversized blocks to `StoredFileReference` before persist.
 *   - `ResponseContent.dereference` materializes the bytes back to
 *     the original variant.
 *   - `MessageIndexingEffect` indexes the dereferenced text.
 */
class ContentExternalizationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)
  // Authorize the test viewer so dereference / fetchStoredFile work.
  TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace)))

  private val convId: Id[Conversation] = Conversation.id("externalization-spec")

  "ContentExternalizationTransform" should {

    "leave small Code blocks inline (under threshold)" in {
      val small = ResponseContent.Code("println(\"hi\")", language = Some("scala"))
      val msg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(small),
        state = EventState.Complete
      )
      // Default threshold is 8KB; "println" is far under.
      sigil.pipeline.ContentExternalizationTransform.apply(msg, TestSigil).map {
        case rewritten: Message =>
          rewritten.content.head shouldBe small
        case other => fail(s"Expected Message, got $other")
      }
    }

    "externalize oversized Code blocks to StoredFileReference" in {
      val big = ResponseContent.Code("a" * 9000, language = Some("scala"))
      val msg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(big),
        state = EventState.Complete
      )
      sigil.pipeline.ContentExternalizationTransform.apply(msg, TestSigil).map {
        case rewritten: Message =>
          rewritten.content.head match {
            case ref: ResponseContent.StoredFileReference =>
              ref.size shouldBe 9000L
              ref.contentType shouldBe "text/x-scala"
              ref.language shouldBe Some("scala")
            case other => fail(s"Expected StoredFileReference, got $other")
          }
        case other => fail(s"Expected Message, got $other")
      }
    }

    "round-trip: externalize → dereference materializes original Code" in {
      val original = "a" * 9000
      val big = ResponseContent.Code(original, language = Some("scala"))
      val msg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(big),
        state = EventState.Complete
      )
      for {
        rewritten <- sigil.pipeline.ContentExternalizationTransform.apply(msg, TestSigil).map(_.asInstanceOf[Message])
        ref        = rewritten.content.head.asInstanceOf[ResponseContent.StoredFileReference]
        rehydrated <- ref.dereference(TestSigil, List(TestUser))
      } yield {
        rehydrated match {
          case ResponseContent.Code(c, lang) =>
            c shouldBe original
            lang shouldBe Some("scala")
          case other => fail(s"Expected Code, got $other")
        }
      }
    }

    "externalize Diff blocks larger than threshold" in {
      val diff = ResponseContent.Diff("--- a\n+++ b\n" + ("@@ context\n" * 800), filename = Some("foo.diff"))
      val msg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(diff),
        state = EventState.Complete
      )
      sigil.pipeline.ContentExternalizationTransform.apply(msg, TestSigil).map {
        case rewritten: Message =>
          rewritten.content.head match {
            case ref: ResponseContent.StoredFileReference =>
              ref.contentType shouldBe "text/x-diff"
              ref.title shouldBe "foo.diff"
            case other => fail(s"Expected StoredFileReference, got $other")
          }
        case other => fail(s"Expected Message, got $other")
      }
    }

    "leave non-extern variants (Text, Heading, Markdown) unchanged" in {
      val mixed = Vector(
        ResponseContent.Text("plain"),
        ResponseContent.Heading("section"),
        ResponseContent.Markdown("**bold**")
      )
      val msg = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicId,
        content = mixed,
        state = EventState.Complete
      )
      sigil.pipeline.ContentExternalizationTransform.apply(msg, TestSigil).map {
        case rewritten: Message =>
          rewritten.content shouldBe mixed
        case other => fail(s"Expected Message, got $other")
      }
    }
  }

  "ResponseContentOps.dereferenceAll" should {
    "leave non-reference variants unchanged and dereference references in place" in {
      val original = "println(\"hi\")"
      val plain = ResponseContent.Text("hello")
      for {
        stored <- TestSigil.storeBytes(GlobalSpace, original.getBytes("UTF-8"), "text/x-scala")
        ref     = ResponseContent.StoredFileReference(
                    fileId = stored._id, title = "code.scala", language = Some("scala"),
                    contentType = "text/x-scala", size = original.length.toLong
                  )
        out    <- ResponseContentOps.dereferenceAll(TestSigil, List(TestUser), Vector(plain, ref))
      } yield {
        out should have size 2
        out(0) shouldBe plain
        out(1) match {
          case ResponseContent.Code(c, lang) =>
            c shouldBe original
            lang shouldBe Some("scala")
          case other => fail(s"Expected Code, got $other")
        }
      }
    }
  }
}
