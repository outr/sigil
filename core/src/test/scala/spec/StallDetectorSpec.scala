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

  /**
   * Stub ToolInput carrying a single string payload — easy to
   * vary or repeat to drive the input fingerprint.
   */
  private case class TestInput(query: String = "") extends ToolInput derives RW
  ToolInput.register(RW.static(TestInput()))

  private val convId = sigil.conversation.Conversation.id("stall-spec-conv")
  private val topicId = sigil.conversation.Topic.id("stall-spec-topic")

  private def invoke(name: String, input: TestInput, atMs: Long): ToolInvoke =
    ToolInvoke(
      toolName = ToolName(name),
      participantId = StallAgent,
      conversationId = convId,
      topicId = topicId,
      input = Some(input),
      state = EventState.Complete,
      timestamp = Timestamp(atMs)
    )

  private def results(typed: Json, origin: Id[Event]): ToolResults =
    ToolResults(
      schemas = Nil,
      participantId = StallAgent,
      conversationId = convId,
      topicId = topicId,
      outcome = ToolOutcome.Success,
      typed = Some(typed),
      state = EventState.Complete,
      origin = Some(origin)
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
      sig.reason.get should include("lsp_find_references")
      sig.reason.get should include("same arguments")
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
        record("lsp_diagnostics", TestInput("a"), 1000L, arr()),
        record("bsp_compile", TestInput("b"), 2000L, obj()),
        record("lsp_find_references", TestInput("c"), 3000L, Null),
        record("bsp_test", TestInput("d"), 4000L, arr())
      )
      val sig = StallDetector.evaluate(tail)
      sig.detected shouldBe true
      sig.reason.get should include("empty")
      sig.reason.get should include("lsp_diagnostics") // first distinct name
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
        record("grep", TestInput("password"), 1000L, arr(str("found-1"))),
        record("read_file", TestInput("auth.scala"), 2000L, obj("content" -> str("class Auth"))),
        record("edit_file", TestInput("change-token-ttl"), 3000L, obj("written" -> num(7)))
      )
      StallDetector.evaluate(tail).detected shouldBe false
    }
  }

  "StallDetector — low-information streak heuristic" should {

    // Three confirmation greps over the same files — distinct
    // inputs, every result lists mostly the same hits with one
    // new line. The agent has stopped learning new facts.
    "fire on 3 consecutive grep results with high payload overlap" in {
      def hit(path: String, line: Int, m: String): Json =
        obj("path" -> str(path), "line" -> num(line), "match" -> str(m))
      val sharedHits = List(
        hit("AdminUsersService.scala", 142, "def resetPassword(userId: UserId, newPassword: Password)"),
        hit("AdminUsersService.scala", 178, "case object PasswordResetSuccessful extends PasswordResetResult"),
        hit("AuthController.scala", 78, "post(\"/password-reset\") { passwordResetForm =>"),
        hit("PasswordResetService.scala", 34, "override def resetPassword(id: UserId): Future[Unit]")
      )
      val tail = List(
        record(
          "grep",
          TestInput("password reset"),
          1000L,
          Arr((sharedHits ++ List(hit("UserRoutes.scala", 12, "post path password-reset"))).toVector)),
        record(
          "grep",
          TestInput("resetPassword"),
          2000L,
          Arr((sharedHits ++ List(hit("UserMailer.scala", 56, "sendPasswordResetEmail(user)"))).toVector)),
        record(
          "grep",
          TestInput("reset"),
          3000L,
          Arr((sharedHits ++ List(hit("AdminAudit.scala", 21, "audit('password.reset', userId)"))).toVector))
      )
      val sig = StallDetector.evaluate(tail)
      sig.detected shouldBe true
      sig.reason.get should include("overlapping data")
      sig.reason.get should include("grep")
    }

    "fire across heterogeneous tools when their outputs share most atoms" in {
      // Different tools (grep + read_file) returning the same
      // tokens — agent has the auth files' contents, keeps
      // re-confirming them.
      val authText = "object AuthService def login user password sealed sealed trait LoginResult"
      val tail = List(
        record("grep", TestInput("login"), 1000L, arr(str(authText))),
        record("read_file", TestInput("Auth.scala"), 2000L, obj("content" -> str(authText))),
        record("grep", TestInput("LoginResult"), 3000L, arr(str(authText + " sealed")))
      )
      val sig = StallDetector.evaluate(tail)
      sig.detected shouldBe true
      sig.reason.get should include("overlapping data")
    }

    "NOT fire on truly distinct exploration across the codebase" in {
      val tail = List(
        record(
          "grep",
          TestInput("password reset"),
          1000L,
          arr(
            obj("path" -> str("AdminUsersService.scala"), "match" -> str("def resetPassword"))
          )),
        record(
          "grep",
          TestInput("tracing config"),
          2000L,
          arr(
            obj("path" -> str("Telemetry.scala"), "match" -> str("OpenTelemetrySdk"))
          )),
        record(
          "grep",
          TestInput("billing webhook"),
          3000L,
          arr(
            obj("path" -> str("StripeWebhook.scala"), "match" -> str("InvoicePaid event handler"))
          ))
      )
      StallDetector.evaluate(tail).detected shouldBe false
    }

    "NOT fire on small payloads even when they trivially overlap" in {
      // Tiny success-flag outputs (below minAtoms) — overlap
      // arithmetic would say Jaccard=1, but the heuristic skips
      // them because there's no real content to compare. Use
      // distinct tool names so identical-streak (which can
      // collapse on inputFingerprint when the static RW for the
      // ToolInput subtype writes only the discriminator) can't
      // fire either.
      val tail = List(
        record("save_note", TestInput("a"), 1000L, obj("written" -> num(1))),
        record("flag_item", TestInput("b"), 2000L, obj("written" -> num(1))),
        record("update_tag", TestInput("c"), 3000L, obj("written" -> num(1)))
      )
      StallDetector.evaluate(tail).detected shouldBe false
    }

    "NOT fire when only 2 consecutive non-empty overlapping outputs exist (below threshold)" in {
      def hits(path: String): Json = arr(
        obj("path" -> str(path), "match" -> str("def loginUser"))
      )
      val tail = List(
        record("grep", TestInput("loginUser"), 1000L, hits("AuthA.scala")),
        record("grep", TestInput("LoginUser"), 2000L, hits("AuthA.scala"))
      )
      StallDetector.evaluate(tail).detected shouldBe false
    }
  }

  "StallDetector — atom helpers" should {

    "tokenize text into normalised set, dropping short tokens" in {
      StallDetector.textAtoms("Hello, World! It's the AuthService.") shouldBe Set(
        "hello",
        "world",
        "the",
        "authservice"
      )
    }

    "atomize a JSON array of objects into the union of all leaf tokens" in {
      val j = arr(
        obj("path" -> str("AdminUsersService.scala"), "n" -> num(3)),
        obj("path" -> str("AuthController.scala"), "n" -> num(7))
      )
      val atoms = StallDetector.jsonAtoms(j)
      atoms should contain("adminusersservice")
      atoms should contain("scala")
      atoms should contain("authcontroller")
      atoms should contain("3")
      atoms should contain("7")
    }

    "compute Jaccard for two atom sets" in {
      StallDetector.jaccard(Set("a", "b", "c"), Set("a", "b", "c")) shouldBe 1.0
      StallDetector.jaccard(Set("a", "b"), Set("b", "c")) shouldBe 1.0 / 3.0 +- 0.0001
      StallDetector.jaccard(Set.empty, Set.empty) shouldBe 1.0
      StallDetector.jaccard(Set("a"), Set.empty) shouldBe 0.0
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
