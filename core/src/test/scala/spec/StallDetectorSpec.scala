package spec

import fabric.{Arr, Json, Null, Obj, Str, arr, obj, str, num}
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.compression.StallDetector
import sigil.conversation.compression.StallDetector.CallRecord
import sigil.event.{Event, MessageRole, ToolInvoke, ToolOutcome, ToolResults}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName}

/**
 * Coverage for [[StallDetector]] — pure-function unit tests over
 * the heuristic. Sigil's progress-checkpoint reflector (bug #124)
 * folds this into the agent's self-reported `meaningfulProgress`
 * so the framework can override "agent says it's making progress"
 * with objective evidence the conversation tail is stuck.
 *
 * Three scenarios mirror the bug spec:
 *
 *   1. 3+ identical (toolName, input, output) calls → detected.
 *   2. 4+ consecutive empty results across distinct tools → detected.
 *   3. Distinct tools returning structurally novel payloads → NOT
 *      detected (positive control — over-aggressive detection
 *      would false-positive on productive turns).
 */
class StallDetectorSpec extends AnyWordSpec with Matchers {

  private case object StallAgent extends sigil.participant.AgentParticipantId {
    val value: String = "stall-agent"
  }
  ParticipantId.register(RW.static(StallAgent))

  /** Stub ToolInput carrying a single string payload — easy to
    * vary or repeat to drive the input fingerprint. */
  private case class TestInput(query: String = "") extends ToolInput derives RW
  ToolInput.register(RW.static(TestInput()))

  private val convId  = sigil.conversation.Conversation.id("stall-spec-conv")
  private val topicId = sigil.conversation.Topic.id("stall-spec-topic")

  private def invoke(name: String, input: TestInput, atMs: Long): ToolInvoke =
    ToolInvoke(
      toolName       = ToolName(name),
      participantId  = StallAgent,
      conversationId = convId,
      topicId        = topicId,
      input          = Some(input),
      state          = EventState.Complete,
      timestamp      = Timestamp(atMs)
    )

  private def results(typed: Json, origin: Id[Event]): ToolResults =
    ToolResults(
      schemas        = Nil,
      participantId  = StallAgent,
      conversationId = convId,
      topicId        = topicId,
      outcome        = ToolOutcome.Success,
      typed          = Some(typed),
      state          = EventState.Complete,
      origin         = Some(origin)
    )

  private def record(name: String, input: TestInput, atMs: Long, output: Json): CallRecord = {
    val ti = invoke(name, input, atMs)
    CallRecord(invoke = ti, result = Some(results(output, ti._id)), resultMessage = None)
  }

  "StallDetector — identical-call streak heuristic" should {

    "fire on 3+ consecutive (toolName, input, output) matches" in {
      val tail = (1 to 3).map { i =>
        record("lsp_find_references", TestInput("foo"), i * 1000L, arr())
      }.toList
      val sig = StallDetector.evaluate(tail)
      sig.detected shouldBe true
      sig.reason.get should include ("lsp_find_references")
      sig.reason.get should include ("same arguments")
    }

    "NOT fire when 3 calls share toolName but inputs differ" in {
      val tail = List(
        record("grep", TestInput("foo"), 1000L, arr(str("a"))),
        record("grep", TestInput("bar"), 2000L, arr(str("b"))),
        record("grep", TestInput("baz"), 3000L, arr(str("c")))
      )
      StallDetector.evaluate(tail).detected shouldBe false
    }

    "NOT fire when same inputs but outputs differ (legit re-query)" in {
      val tail = List(
        record("lsp_diagnostics", TestInput("x"), 1000L, obj("count" -> num(1))),
        record("lsp_diagnostics", TestInput("x"), 2000L, obj("count" -> num(2))),
        record("lsp_diagnostics", TestInput("x"), 3000L, obj("count" -> num(3)))
      )
      StallDetector.evaluate(tail).detected shouldBe false
    }
  }

  "StallDetector — empty-payload streak heuristic" should {

    "fire on 4 consecutive empty results across distinct tools" in {
      // Different tool each time, different inputs, but every result
      // structurally empty. Wire-log scenario from #124.
      val tail = List(
        record("lsp_diagnostics",     TestInput("a"), 1000L, arr()),
        record("bsp_compile",         TestInput("b"), 2000L, obj()),
        record("lsp_find_references", TestInput("c"), 3000L, Null),
        record("bsp_test",            TestInput("d"), 4000L, arr())
      )
      val sig = StallDetector.evaluate(tail)
      sig.detected shouldBe true
      sig.reason.get should include ("empty")
      sig.reason.get should include ("lsp_diagnostics")  // first distinct name
    }

    "NOT fire when 3 empties followed by a non-empty (reset)" in {
      val tail = List(
        record("a", TestInput("1"), 1000L, arr()),
        record("b", TestInput("2"), 2000L, arr()),
        record("c", TestInput("3"), 3000L, arr()),
        record("d", TestInput("4"), 4000L, arr(str("real-result")))
      )
      // Reverse-walking from the tail: first call is non-empty,
      // streak resets to 0.
      StallDetector.evaluate(tail).detected shouldBe false
    }

    "NOT fire on fewer than 4 trailing empties" in {
      val tail = List(
        record("a", TestInput("1"), 1000L, arr()),
        record("b", TestInput("2"), 2000L, arr()),
        record("c", TestInput("3"), 3000L, arr())
      )
      StallDetector.evaluate(tail).detected shouldBe false
    }
  }

  "StallDetector — positive control" should {

    "NOT fire when distinct tools return structurally novel payloads" in {
      val tail = List(
        record("grep",      TestInput("password"), 1000L, arr(str("found-1"))),
        record("read_file", TestInput("auth.scala"), 2000L, obj("content" -> str("class Auth"))),
        record("edit_file", TestInput("change-token-ttl"), 3000L, obj("written" -> num(7)))
      )
      StallDetector.evaluate(tail).detected shouldBe false
    }
  }

  "StallDetector.evaluate" should {

    "return Empty when the tail is empty" in {
      StallDetector.evaluate(Nil) shouldBe StallDetector.Signal.Empty
    }
  }

  "StallDetector helpers" should {

    "treat null / empty-array / empty-object as empty payloads" in {
      StallDetector.isEmpty(record("t", TestInput(), 0L, Null)) shouldBe true
      StallDetector.isEmpty(record("t", TestInput(), 0L, arr())) shouldBe true
      StallDetector.isEmpty(record("t", TestInput(), 0L, obj())) shouldBe true
    }

    "treat objects whose meaningful fields are themselves empty as empty" in {
      StallDetector.isEmpty(record("t", TestInput(), 0L, obj("items" -> arr(), "count" -> num(0)))) shouldBe false
      // Note: the heuristic checks `values.forall(isEmpty)`. `0` is not empty.
      // Empty array + empty object IS empty.
      StallDetector.isEmpty(record("t", TestInput(), 0L, obj("items" -> arr(), "extra" -> obj()))) shouldBe true
    }

    "produce equal input fingerprints for invokes with same input regardless of timestamp" in {
      val a = invoke("t", TestInput("x"), 1000L)
      val b = invoke("t", TestInput("x"), 9999L)
      StallDetector.inputFingerprint(a) shouldBe StallDetector.inputFingerprint(b)
    }
  }
}
