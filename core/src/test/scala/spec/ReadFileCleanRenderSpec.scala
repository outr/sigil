package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder, ToolCallState, Topic}
import sigil.event.{ToolInvoke, ToolOutcome}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ReadFileOutput

/**
 * Sigil #404 — `read_file` delivers a STRUCTURED `ReadFileOutput`, so its
 * model-facing render went through `JsonFormatter.Compact`, JSON-escaping the
 * file text (`"` → `\"`, `/` → `\/`). An agent copying a line from that output
 * as an `edit_file` `oldString` anchor then copied the escaped form, and the
 * edit searched the real file (bare `"`) and returned `no match`.
 *
 * The fix is a presentation-only opt-in: `ToolOutput.modelText` lets a
 * structured output render a clean primary-text field + a plain metadata
 * trailer instead of escaped JSON. The typed `ReadFileOutput` is unchanged, so
 * programmatic consumers (`OverflowReadBackSpec`, `WorkspaceRoutingSpec`) still
 * read `.content`.
 */
class ReadFileCleanRenderSpec extends AnyWordSpec with Matchers {

  private def renderedFor(out: ReadFileOutput): String = {
    val invoke = ToolInvoke(
      toolName       = ToolName("read_file"),
      participantId  = TestAgent,
      conversationId = Conversation.id("readfile-render"),
      topicId        = Id[Topic]("t"),
      output         = out,
      outcome        = ToolOutcome.Success,
      state          = EventState.Complete
    )
    FrameBuilder.computeFrame(invoke) match {
      case Some(tc: ContextFrame.ToolCall) =>
        tc.state match {
          case ToolCallState.Complete(content, _) => content
          case other                              => fail(s"expected a Complete tool frame, got $other")
        }
      case other => fail(s"expected a ToolCall frame, got $other")
    }
  }

  "read_file's model-facing render (#404)" should {

    "present file content as clean text — quotes and slashes verbatim, never JSON-escaped" in {
      val src = "val x = \"hi\"\nimport a.b.C // see /home/u/project/x.scala"
      val rendered = renderedFor(ReadFileOutput(content = src, totalLines = 2, linesRead = 2, hash = Some("9f3a")))
      withClue(s"rendered:\n$rendered\n") {
        // The exact bytes an edit anchor would need are present verbatim...
        rendered should include ("val x = \"hi\"")
        rendered should include ("/home/u/project/x.scala")
        // ...and the JSON-escaped forms that broke edit anchors are gone.
        rendered should not include ("""\"hi\"""")
        rendered should not include ("""\/home""")
      }
    }

    "carry the read metadata (hash for expectedHash) in a plain trailer, not a JSON envelope" in {
      val rendered = renderedFor(ReadFileOutput(content = "line one", totalLines = 10, linesRead = 3, hash = Some("deadbeef")))
      withClue(s"rendered:\n$rendered\n") {
        // The content leads, unwrapped (not `{"content":"line one",...}`).
        rendered should startWith ("line one")
        rendered should not include ("\"content\":")
        // The hash the agent echoes into `edit_file.expectedHash` still rides along.
        rendered should include ("deadbeef")
      }
    }
  }
}
