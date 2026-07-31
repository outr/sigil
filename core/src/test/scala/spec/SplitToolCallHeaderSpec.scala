package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.TurnContext
import lightdb.id.Id
import rapid.Task
import sigil.event.Event
import sigil.provider.{CallId, ProviderEvent, ToolCallAccumulator}
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}
import sigil.tool.ToolContext
import sigil.tool.ToolRoster

/**
 * Regression for Sigil audit H8 — `ToolCallAccumulator.observeHeader`
 * accepts the tool-call header in pieces (id-only or name-only chunks)
 * rather than requiring both in the same SSE delta. Some OpenAI-compat
 * backends (vLLM versions, SGLang variants) split the header:
 *
 *   chunk 1: { index: 0, id: "call_x" }
 *   chunk 2: { index: 0, function: { name: "foo" } }
 *   chunk 3+: { index: 0, function: { arguments: "..." } }
 *
 * Pre-fix the accumulator's `start` required both id + name in the
 * same chunk; the split-header path silently dropped every tool call,
 * and subsequent arguments deltas accumulated to no `CallState`. Net:
 * the whole tool call vanished.
 */
class SplitToolCallHeaderSpec extends AnyWordSpec with Matchers {

  case class Args(value: String) extends ToolInput derives RW

  private object Foo extends Tool {
    type Input  = Args
    type Output = TextToolOutput
    val inputRW  = summon[RW[Args]]
    val outputRW = summon[RW[TextToolOutput]]
    override val name: ToolName = ToolName("foo")
    override val description: String = "test"
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "foo"))
    )
    override def _id: Id[Tool] = Id[Tool](name.value)
    override def executeResult(input: Args, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(input.value)))
  }

  private def newAcc: ToolCallAccumulator = new ToolCallAccumulator(ToolRoster(Vector(Foo)))

  "Bug audit H8 — split tool-call header chunks" should {

    "emit ToolCallStart when id arrives BEFORE name across chunks" in {
      val acc = newAcc
      val a = acc.observeHeader(0, Some(CallId("call_x")), None)
      a.collect { case s: ProviderEvent.ToolCallStart => s } shouldBe empty

      val b = acc.observeHeader(0, None, Some("foo"))
      val starts = b.collect { case s: ProviderEvent.ToolCallStart => s }
      starts should have size 1
      starts.head.toolName shouldBe "foo"
      starts.head.callId shouldBe CallId("call_x")
    }

    "emit ToolCallStart when name arrives BEFORE id across chunks" in {
      val acc = newAcc
      val a = acc.observeHeader(0, None, Some("foo"))
      a.collect { case s: ProviderEvent.ToolCallStart => s } shouldBe empty

      val b = acc.observeHeader(0, Some(CallId("call_x")), None)
      val starts = b.collect { case s: ProviderEvent.ToolCallStart => s }
      starts should have size 1
      starts.head.toolName shouldBe "foo"
    }

    "buffer arguments that arrive before the header is complete" in {
      val acc = newAcc
      acc.observeHeader(0, Some(CallId("call_x")), None)
      acc.appendArgs(0, """{"value":""")     // arg fragment 1, before name
      acc.appendArgs(0, """"hello"}""")      // arg fragment 2, still no name
      acc.observeHeader(0, None, Some("foo"))  // name arrives last
      val events = acc.complete()
      val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
      completes should have size 1
      completes.head.call.inputFor(Foo) match {
        case Some(args) => args.value shouldBe "hello"
        case None       => fail(s"expected a decoded Foo call, got ${completes.head.call}")
      }
    }

    "still work when both fields arrive in the same chunk (back-compat with start)" in {
      val acc = newAcc
      val events = acc.start(0, CallId("call_x"), "foo")
      events.collect { case s: ProviderEvent.ToolCallStart => s } should have size 1
    }

    "treat a duplicate observeHeader after start as a no-op" in {
      val acc = newAcc
      acc.observeHeader(0, Some(CallId("call_x")), Some("foo"))
      // Some backends re-emit the header in subsequent chunks for safety.
      val again = acc.observeHeader(0, Some(CallId("call_x")), Some("foo"))
      again shouldBe empty
    }

    "surface a diagnostic Error when the stream closes with a partial header" in {
      val acc = newAcc
      acc.observeHeader(0, Some(CallId("call_x")), None)  // name never arrives
      val events = acc.complete()
      val errors = events.collect { case e: ProviderEvent.Error => e }
      errors should have size 1
      errors.head.message should include ("incomplete at stream close")
    }
  }
}
