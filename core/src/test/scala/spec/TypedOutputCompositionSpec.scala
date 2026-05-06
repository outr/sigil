package spec

import fabric.io.JsonParser
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TopicEntry, TurnInput}
import sigil.event.{ToolResults, Event}
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}
import sigil.tool.fs.{BashTool, LocalFileSystemContext}
import sigil.tool.model.{BashInput, BashOutput}

/**
 * Coverage for the typed-output authoring shape — `TypedOutputTool[I, O]`
 * + `Tool.invoke` for tool-to-tool composition. Three asserts:
 *
 *   1. `BashTool` (now `TypedOutputTool[BashInput, BashOutput]`)
 *      emits a `ToolResults` with `typed: Some(json)` carrying the
 *      structured payload, and the JSON round-trips back to a
 *      `BashOutput` via the registered RW.
 *   2. `outputDefinition` is populated for the typed tool — agents
 *      can reason about the output shape ahead of calling.
 *   3. A higher-order tool's `executeTyped` body composes
 *      `BashTool.invoke(...)` and pattern-matches on the typed
 *      result directly, no JSON parsing required.
 */
class TypedOutputCompositionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"typed-output-${rapid.Unique()}")

  private def turnContext(): TurnContext = {
    val conv = Conversation(
      topics       = List(TopicEntry(TestTopicId, "test", "test")),
      participants = Nil,
      _id          = convId
    )
    TurnContext(
      sigil            = TestSigil,
      chain            = List(TestUser),
      conversation     = conv,
      turnInput        = TurnInput(ConversationView(conversationId = convId))
    )
  }

  private val fs   = new LocalFileSystemContext(None)
  private val bash = new BashTool(fs)

  "TypedOutputTool.execute" should {
    "emit a ToolResults with typed JSON that round-trips through BashOutput's RW" in {
      for {
        _      <- TestSigil.withDB(_.conversations.transaction(_.upsert(turnContext().conversation)))
        events <- bash.execute(BashInput(command = "echo typed-output-marker"), turnContext()).toList
      } yield {
        val tr = events.collectFirst { case t: ToolResults => t }
        tr should not be empty
        tr.get.typed should not be empty
        val rw  = summon[RW[BashOutput]]
        val out = rw.write(tr.get.typed.get)
        out.stdout should include("typed-output-marker")
        out.exitCode shouldBe 0
      }
    }

    "populate outputDefinition so find_capability surfaces the output shape" in {
      bash.outputDefinition should not be empty
      bash.schema.output should not be empty
      // The schema's input + output definitions are distinct shapes.
      bash.schema.output.get should not equal bash.schema.input
      Task.pure(succeed)
    }
  }

  "Tool.invoke (typed-output composition)" should {
    "let one tool call another tool and pattern-match on the typed result" in {
      // Higher-order tool: runs `echo` via BashTool.invoke and
      // builds a typed wrapper output capturing whether the echo
      // succeeded and the captured marker.
      case class WrapperInput(marker: String) extends ToolInput derives RW
      case class WrapperOutput(succeeded: Boolean, captured: String) derives RW

      object WrapperTool extends TypedOutputTool[WrapperInput, WrapperOutput](
        name = ToolName("wrapper_echo"),
        description = "Test-only tool that composes BashTool.invoke and pattern-matches the typed BashOutput.",
        keywords = Set("test", "wrapper")
      ) {
        override protected def executeTyped(input: WrapperInput, ctx: TurnContext): Task[WrapperOutput] =
          bash.invoke(BashInput(command = s"echo ${input.marker}"), ctx).map { bashOutput =>
            // Pattern-matching on the typed BashOutput — no JSON parsing.
            WrapperOutput(
              succeeded = bashOutput.exitCode == 0,
              captured  = bashOutput.stdout.trim
            )
          }
      }

      for {
        _      <- TestSigil.withDB(_.conversations.transaction(_.upsert(turnContext().conversation)))
        events <- WrapperTool.execute(WrapperInput(marker = "compose-marker"), turnContext()).toList
      } yield {
        val tr = events.collectFirst { case t: ToolResults => t }
        tr should not be empty
        tr.get.typed should not be empty
        val rw      = summon[RW[WrapperOutput]]
        val wrapped = rw.write(tr.get.typed.get)
        wrapped.succeeded shouldBe true
        wrapped.captured shouldBe "compose-marker"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
