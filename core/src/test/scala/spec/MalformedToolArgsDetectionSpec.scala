package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId, TurnContext}
import lightdb.id.Id
import sigil.event.Event
import sigil.provider.{CallId, ProviderEvent, ProviderStreamException, ToolCallAccumulator}
import sigil.tool.{Tool, ToolInput, ToolName}

/**
 * Regression for sigil bug #171 part B — when the model emits a JSON
 * array as a tool's arguments AND the schema requires an object root,
 * the accumulator throws [[ProviderStreamException]] (signaling
 * "model emitted malformed structured output") instead of returning a
 * generic parse-failure error. The throw surfaces through the agent
 * loop's failure path as a user-visible Failure Message AND lets
 * [[sigil.provider.ProviderStrategy.errorClassifier]] route the next
 * attempt to a different candidate.
 *
 * Background: DeepInfra silently ignores `strict: true` on Kimi-K2.5
 * (verified against captured Sage wire bodies), so the framework
 * cannot rely on grammar-constrained decoding alone. This detection
 * fires when the upstream backend ships malformed output that
 * structurally couldn't have come from a schema-respecting decoder.
 */
class MalformedToolArgsDetectionSpec extends AnyWordSpec with Matchers {

  case class RespondLike(topicLabel: String, content: String) extends ToolInput derives RW

  private object RespondTool extends Tool {
    override val name: ToolName = ToolName("respond")
    override def description: String = "Object-rooted respond schema."
    override def inputRW: RW[? <: ToolInput] = summon[RW[RespondLike]].asInstanceOf[RW[ToolInput]]
    override def space: SpaceId = GlobalSpace
    override def execute(input: ToolInput, context: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  private def feed(acc: ToolCallAccumulator, args: String): Unit = {
    acc.start(0, CallId("call-test"), RespondTool.name.value)
    acc.appendArgs(0, args)
  }

  "Bug #171.B — degenerate-args detection" should {

    "throw ProviderStreamException when the model emits a JSON array for an object-rooted tool" in {
      val acc = new ToolCallAccumulator(Vector(RespondTool), providerKey = "deepinfra")
      val args = """[{"topicLabel":"A","content":"a"},{"topicLabel":"B","content":"b"}]"""
      feed(acc, args)
      val ex = intercept[ProviderStreamException](acc.complete())
      ex.providerKey shouldBe "deepinfra"
      ex.code shouldBe 200
      ex.typ shouldBe "malformed_tool_args"
      ex.getMessage should (include ("JSON array") and include ("respond"))
    }

    "carry the provider key so error attribution survives the throw" in {
      val acc = new ToolCallAccumulator(Vector(RespondTool), providerKey = "DigitalOcean")
      feed(acc, """[{}, {}]""")
      val ex = intercept[ProviderStreamException](acc.complete())
      ex.providerKey shouldBe "DigitalOcean"
    }

    "fall through to the generic Error path for non-degenerate parse failures" in {
      // Object root that fails for a different reason (missing required
      // fields, type mismatch) — should NOT be classified as degenerate.
      // Falls to the existing structured-diagnostic path.
      val acc = new ToolCallAccumulator(Vector(RespondTool), providerKey = "deepinfra")
      feed(acc, """{"topicLabel":42,"content":[]}""")  // wrong field types
      val events = acc.complete()
      // No exception thrown; classified as a regular parse failure.
      val errs = events.collect { case e: ProviderEvent.Error => e.message }
      errs should not be empty
      errs.head should include ("Failed to parse args")
    }

    "fall through to the generic Error path for invalid JSON (not even a parseable array)" in {
      val acc = new ToolCallAccumulator(Vector(RespondTool), providerKey = "deepinfra")
      feed(acc, """{garbage""")
      val events = acc.complete()
      val errs = events.collect { case e: ProviderEvent.Error => e.message }
      errs should not be empty
    }

    "stay on the happy path for a well-formed object" in {
      val acc = new ToolCallAccumulator(Vector(RespondTool), providerKey = "deepinfra")
      feed(acc, """{"topicLabel":"A","content":"hello"}""")
      val events = acc.complete()
      events.collectFirst { case _: ProviderEvent.ToolCallComplete => true }.isDefined shouldBe true
      events.collectFirst { case _: ProviderEvent.Error           => true }.isDefined shouldBe false
    }
  }
}
