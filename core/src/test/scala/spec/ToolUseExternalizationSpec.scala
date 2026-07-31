package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.compression.StandardContextCurator
import sigil.conversation.{Conversation, ToolCallState, Topic, TopicEntry}
import sigil.event.{ToolInvoke, ToolOutcome}
import sigil.signal.EventState
import sigil.tool.{DiscoverySpec, Effect, InMemoryToolFinder, MutationTargeting, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolProfile, ToolSpec}

/**
 * Regression for sigil bug #288 — agent-emitted tool_use content
 * that exceeds [[sigil.Sigil.inlineToolUseContentThreshold]] for a
 * tool-opted-in field gets a short placeholder in subsequent turns'
 * wire prompts. The durable event log stays whole; only the rendered
 * prompt shrinks.
 */
class ToolUseExternalizationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Synthetic tool that opts `content` in for externalization. */
  private case class WriteFileInput(path: String, content: String) extends ToolInput derives RW
  private case object SyntheticWriteFileTool extends Tool {
    type Input  = WriteFileInput
    type Output = TextToolOutput
    val inputRW: RW[WriteFileInput]   = summon[RW[WriteFileInput]]
    val outputRW: RW[TextToolOutput]  = summon[RW[TextToolOutput]]
    override val name = ToolName("synthetic_write_file")
    override val description = "Synthetic test tool that opts `content` in for externalization."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "write", "externalization"))
    )
    override val externalizableInputFields: Set[String] = Set("content")
    override def executeOutput(input: WriteFileInput, context: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput(s"wrote ${input.content.length} chars to ${input.path}"))
  }

  ToolInput.register(RW.static(WriteFileInput("", "")))

  "StandardContextCurator.rewriteOversizedFields (sigil #288)" should {

    "replace over-threshold string field values with a placeholder while keeping the JSON shape intact" in Task {
      val largeContent = "x" * 10_000
      val argsJson = fabric.io.JsonFormatter.Compact(fabric.obj(
        "path" -> fabric.str("sections/page.liquid"),
        "content" -> fabric.str(largeContent)
      ))
      val rewritten = StandardContextCurator.rewriteOversizedFields(
        argsJson = argsJson,
        fields   = Set("content"),
        threshold = 1024L
      )
      rewritten should not equal argsJson
      // Parse-back assertions — the result is still well-formed JSON.
      val parsed = fabric.io.JsonParser(rewritten).asObj.value
      parsed("path").asString shouldBe "sections/page.liquid"
      parsed("content").asString should include ("externalized")
      parsed("content").asString should include ("10000")
      parsed("content").asString.length should be < largeContent.length
    }

    "leave fields not in the opt-in set untouched" in Task {
      val argsJson = fabric.io.JsonFormatter.Compact(fabric.obj(
        "path" -> fabric.str("x" * 10_000),  // 10K path — over threshold but NOT in opt-in set
        "content" -> fabric.str("small")
      ))
      val rewritten = StandardContextCurator.rewriteOversizedFields(
        argsJson = argsJson,
        fields = Set("content"),
        threshold = 1024L
      )
      rewritten shouldBe argsJson  // identity preserved
    }

    "leave under-threshold values untouched even when the field is opt-in" in Task {
      val argsJson = fabric.io.JsonFormatter.Compact(fabric.obj(
        "content" -> fabric.str("small content")
      ))
      val rewritten = StandardContextCurator.rewriteOversizedFields(
        argsJson = argsJson,
        fields = Set("content"),
        threshold = 1024L
      )
      rewritten shouldBe argsJson
    }

    "no-op on malformed JSON without throwing" in Task {
      val malformed = "{not valid json"
      val rewritten = StandardContextCurator.rewriteOversizedFields(
        argsJson = malformed,
        fields = Set("content"),
        threshold = 1024L
      )
      rewritten shouldBe malformed
    }

    "no-op when fields set is empty" in Task {
      val largeJson = fabric.io.JsonFormatter.Compact(fabric.obj(
        "content" -> fabric.str("x" * 10_000)
      ))
      val rewritten = StandardContextCurator.rewriteOversizedFields(
        argsJson = largeJson,
        fields = Set.empty,
        threshold = 1024L
      )
      rewritten shouldBe largeJson
    }
  }

  "Tool.externalizableInputFields hook" should {
    "default to empty (apps opt fields in per-tool)" in Task {
      val tool = new Tool {
        type Input  = WriteFileInput
        type Output = TextToolOutput
        val inputRW: RW[WriteFileInput] = summon[RW[WriteFileInput]]
        val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]
        override val name = ToolName("nothing_externalized")
        override val description = "Test-only tool that externalizes nothing."
        val spec: ToolSpec = ToolSpec(
          name = name,
          description = description,
          profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
          discovery = DiscoverySpec(keywords = Set("test", "externalization"))
        )
        override def executeOutput(input: WriteFileInput, context: ToolContext): Task[TextToolOutput] =
          Task.pure(TextToolOutput(""))
      }
      tool.externalizableInputFields shouldBe Set.empty
    }

    "tool that opts in declares the set" in Task {
      SyntheticWriteFileTool.externalizableInputFields shouldBe Set("content")
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
