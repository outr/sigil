package spec

import fabric.*
import fabric.define.{DefType, Definition}
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.event.Event
import sigil.provider.{CallId, ProviderEvent, ToolCallAccumulator}
import sigil.tool.{Tool, ToolInput, ToolName}

/**
 * Regression coverage for bug #72 — when `tool.inputRW.write(json)`
 * threw during arg deserialization, the framework used to pass the
 * exception's `getMessage` through verbatim into a
 * `ProviderEvent.Error`. That message was often a JVM-internal
 * anonymous-class name (e.g. `sigil/script/UpdateScriptToolInput$$anon$3`)
 * with no information about *what* failed; the agent received
 * `"Provider error: Failed to parse args for tool X:
 * sigil/script/X$$anon$3"` and had nothing actionable to do.
 *
 * Post-fix: the catch path produces a structured diagnostic
 * including the exception class name plus a structural-validation
 * hint computed from the same JSON via [[ToolInputValidator]]. Even
 * when the upstream throw is opaque, the validator's
 * human-readable violations give the agent something to act on.
 */
class ToolCallAccumulatorErrorDiagnosticSpec extends AnyWordSpec with Matchers {

  // -- input case classes --

  case class ValidInput(name: String, count: Int) extends ToolInput derives RW

  // -- fixture tool with a stub RW that ALWAYS throws on write --
  //
  // Mirrors the bug's actual failure shape: fabric's RW.write throws
  // with a JVM-internal name as the exception message. The validator
  // (running on the same parsed JSON) still produces useful output if
  // the JSON doesn't structurally match the definition; the test
  // asserts the diagnostic surfaces both the throw class and (when
  // applicable) the validator's hint.

  /** An inputRW that always throws on write, simulating fabric's
    * opaque `$$anon$N` failure mode. */
  private val throwingRW: RW[ToolInput] = {
    val baseDef = summon[RW[ValidInput]].definition.copy(
      className = Some("ThrowingInput"),
      genericTypes = Nil,
      genericName = None
    )
    new RW[ToolInput] {
      override def read(value: ToolInput): Json = obj()
      override def write(value: Json): ToolInput =
        throw new NoClassDefFoundError("sigil/script/ThrowingInput$$anon$3")
      override def definition: Definition = baseDef
    }
  }

  private object StubTool extends Tool {
    override val name: ToolName = ToolName("throwing_tool")
    override def description: String = "Always throws on inputRW.write."
    override def inputRW: RW[? <: ToolInput] = throwingRW
    override def space: SpaceId = GlobalSpace
    override def execute(input: ToolInput, context: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  private object ValidTool extends Tool {
    override val name: ToolName = ToolName("valid_tool")
    override def description: String = "Round-trips ValidInput cleanly."
    override def inputRW: RW[? <: ToolInput] = summon[RW[ValidInput]].asInstanceOf[RW[ToolInput]]
    override def space: SpaceId = GlobalSpace
    override def execute(input: ToolInput, context: TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  // -- helpers --

  private def runComplete(tool: Tool, args: String): Vector[ProviderEvent] = {
    val acc = new ToolCallAccumulator(Vector(tool))
    acc.start(0, CallId("call-test"), tool.name.value)
    acc.appendArgs(0, args)
    acc.complete()
  }

  private def errorMessage(events: Vector[ProviderEvent]): String =
    events.collectFirst { case ProviderEvent.Error(msg) => msg }
      .getOrElse(fail(s"expected a ProviderEvent.Error, got: $events"))

  // -- tests --

  "Bug #72 — opaque RW.write throw should produce a structured diagnostic" should {
    "include the exception's class name AND message in the error string" in {
      // The throw is `NoClassDefFoundError("sigil/script/ThrowingInput$$anon$3")`.
      // Pre-fix the agent saw `"Failed to parse args for tool throwing_tool:
      // sigil/script/ThrowingInput$$anon$3"` — class name as message,
      // no class context. Post-fix the message includes the class
      // name AND the message string distinctly.
      val args = """{"name":"alice","count":42}"""
      val msg = errorMessage(runComplete(StubTool, args))
      msg should include ("Failed to parse args for tool throwing_tool")
      // Class name distinct from the message:
      msg should include ("NoClassDefFoundError")
      msg should include ("sigil/script/ThrowingInput$$anon$3")
    }

    "include a schema-shape summary so the agent can compare its emitted JSON" in {
      // The bug's root cause is that even when RW.write throws
      // opaquely, the agent has no signal what the expected shape
      // was. Post-fix every catch-path diagnostic carries an
      // `Expected shape:` summary listing required + optional
      // fields by name and type. The agent reading the diagnostic
      // can compare its emitted JSON against this list.
      val args = """{"name":"alice","count":42}"""
      val msg = errorMessage(runComplete(StubTool, args))
      msg should include ("Expected shape:")
      msg should include ("name: string")
      msg should include ("count: integer")
    }

    "still produce ToolCallComplete on the happy path (no error)" in {
      val args = """{"name":"alice","count":42}"""
      val events = runComplete(ValidTool, args)
      events.collectFirst { case _: ProviderEvent.Error => true }.isDefined shouldBe false
      events.collectFirst { case _: ProviderEvent.ToolCallComplete => true }.isDefined shouldBe true
    }

    "produce a diagnostic for malformed JSON syntax" in {
      // Unclosed-brace JSON → JsonParser throws (fabric's parser is
      // JSON5-lenient about unquoted keys, but unbalanced braces
      // are unambiguous). The catch path captures the parser
      // exception's class + message + schema summary.
      val args = """{"name":"alice","count":"""
      val msg = errorMessage(runComplete(ValidTool, args))
      msg should include ("Failed to parse args for tool valid_tool")
      // Schema summary is present even on JSON-parse failures so
      // the agent can re-emit cleanly without having to look up the
      // tool's schema separately.
      msg should include ("Expected shape:")
      msg should include ("name: string")
      msg should include ("count: integer")
    }

    "list optional fields separately from required" in {
      case class WithOpt(req: String, opt: Option[Int] = None) extends ToolInput derives RW
      val rw = summon[RW[WithOpt]]
      val baseDef = rw.definition.copy(className = Some("WithOpt"))
      val throwingOptRW: RW[ToolInput] = new RW[ToolInput] {
        override def read(value: ToolInput): Json = obj()
        override def write(value: Json): ToolInput =
          throw new RuntimeException("synthetic")
        override def definition: Definition = baseDef
      }
      object WithOptTool extends Tool {
        override val name: ToolName = ToolName("with_opt_tool")
        override def description: String = "Tool with required + optional fields."
        override def inputRW: RW[? <: ToolInput] = throwingOptRW
        override def space: SpaceId = GlobalSpace
        override def execute(input: ToolInput, context: TurnContext): rapid.Stream[Event] = rapid.Stream.empty
        override def _id: Id[Tool] = Id[Tool](name.value)
      }
      val msg = errorMessage(runComplete(WithOptTool, """{"req":"x"}"""))
      msg should include ("required: [req: string]")
      msg should include ("optional: [opt: integer]")
    }
  }
}
