package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId, TurnContext}
import lightdb.id.Id
import sigil.event.Event
import sigil.provider.{CallId, ProviderEvent, ToolCallAccumulator}
import sigil.tool.{Tool, ToolInput, ToolName}

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
  override def paginate: Boolean = false
    override val name: ToolName = ToolName("foo")
    override def description: String = "test"
    override def inputRW: RW[? <: ToolInput] = summon[RW[Args]].asInstanceOf[RW[ToolInput]]
    override def space: SpaceId = GlobalSpace
    override def execute(input: ToolInput, context: TurnContext): rapid.Stream[Event] = rapid.Stream.empty
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  private def newAcc: ToolCallAccumulator = new ToolCallAccumulator(Vector(Foo))

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
      completes.head.input shouldBe a [Args]
      completes.head.input.asInstanceOf[Args].value shouldBe "hello"
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
