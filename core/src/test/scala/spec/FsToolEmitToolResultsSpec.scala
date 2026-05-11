package spec

import fabric.{arr, num, obj, str}
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.event.{Event, ToolOutcome, ToolResults}
import sigil.tool.ToolName

/**
 * Coverage for sigil bug #134 — `FsToolEmit` used to return a
 * `Message(role=Tool)` whose `content` was a JSON-stringified blob.
 * Clients merging invoke + result into one chip via `origin` linkage
 * fell through and rendered the result as a separate bubble.
 *
 * Post-fix: returns a `ToolResults` event with `typed = Some(payload)`,
 * `origin = ctx.currentToolInvokeId`, and a chip-friendly `summary`
 * inferred from common payload shapes.
 */
class FsToolEmitToolResultsSpec extends AnyWordSpec with Matchers {

  private val convId = Conversation.id("fs-tool-emit-spec")
  private val invokeId: Id[Event] = Id[Event]("inv-1")

  private def ctx(): TurnContext = TurnContext(
    sigil               = TestSigil,
    chain               = List(TestUser, TestAgent),
    conversation        = Conversation(topics = TestTopicStack, participants = Nil, _id = convId),
    turnInput           = TurnInput(conversationId = convId, frames = Vector.empty, participantProjections = Map.empty),
    currentToolInvokeId = Some(invokeId),
    currentToolName     = Some(ToolName("git_diff"))
  )

  "FsToolEmit" should {

    "emit a ToolResults event, not a Message" in {
      val out = sigil.tool.fs.FsToolEmit(obj("text" -> str("hello")), ctx())
      out shouldBe a [ToolResults]
    }

    "carry the originating ToolInvoke id in `origin`" in {
      val out = sigil.tool.fs.FsToolEmit(obj("ok" -> str("yes")), ctx())
      out.origin shouldBe Some(invokeId)
    }

    "preserve the typed payload structure in `typed` — newlines stay as newlines" in {
      val diff = "diff --git a/Foo.scala b/Foo.scala\n--- a/Foo.scala\n+++ b/Foo.scala\n@@ -1 +1 @@\n-old\n+new"
      val out = sigil.tool.fs.FsToolEmit(obj("text" -> str(diff)), ctx())
      val typed = out.typed.get.asInstanceOf[fabric.Obj]
      typed.value("text").asInstanceOf[fabric.Str].value shouldBe diff
      // Critically: the raw string is preserved — no JSON escape
      // sequences, no `\/` slashes, no `\n` chars. Clients can
      // pluck `.text` and render as a diff code block.
      typed.value("text").asInstanceOf[fabric.Str].value should include("\n")
      typed.value("text").asInstanceOf[fabric.Str].value should not include "\\n"
    }

    "infer a 'N lines' summary for {text} payloads" in {
      val out = sigil.tool.fs.FsToolEmit(obj("text" -> str("a\nb\nc")), ctx())
      out.summary shouldBe Some("3 lines")
    }

    "infer a 'N hunks' summary for {hunks: [...]} payloads" in {
      val out = sigil.tool.fs.FsToolEmit(obj("hunks" -> arr(obj(), obj())), ctx())
      out.summary shouldBe Some("2 hunks")
    }

    "infer a 'N files' summary for {files: [...]} payloads" in {
      val out = sigil.tool.fs.FsToolEmit(obj("files" -> arr(obj(), obj(), obj())), ctx())
      out.summary shouldBe Some("3 files")
    }

    "mark outcome as Failure when payload carries an `error` key" in {
      val out = sigil.tool.fs.FsToolEmit(obj("error" -> str("non-fast-forward")), ctx())
      out.outcome shouldBe a [ToolOutcome.Failure]
      out.outcome.asInstanceOf[ToolOutcome.Failure].reason should include("non-fast-forward")
      out.summary.get should include("failed")
    }

    "default outcome to Success on payloads without an `error` key" in {
      val out = sigil.tool.fs.FsToolEmit(obj("pushed" -> fabric.bool(true)), ctx())
      out.outcome shouldBe ToolOutcome.Success
    }

    "leave origin = None when the caller's TurnContext has no currentToolInvokeId (off-tool dispatch)" in {
      val ctxNoInvoke = ctx().copy(currentToolInvokeId = None)
      val out = sigil.tool.fs.FsToolEmit(obj("ok" -> str("y")), ctxNoInvoke)
      out.origin shouldBe None
    }
  }
}
