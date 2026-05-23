package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, Topic, TopicEntry, TurnInput}
import sigil.event.{Stop, ToolOutcome}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.core.CancelTool
import sigil.tool.model.CancelInput

/**
 * Coverage for [[CancelTool]]'s reason validation. Reasons that
 * read as turn-flow transitions ("starting metals", "need to read
 * grep output", "wait for results", "next step") cause the tool to
 * resolve a `Failure` block instead of emitting the [[Stop]] event,
 * pushing the agent back to picking `respond` / `no_response` /
 * the actual next tool on its next turn.
 *
 * Legitimate cancel reasons (user halted, unrecoverable failure)
 * pass through and emit the Stop event as an ancillary durable event.
 */
class CancelToolValidationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConversation(suffix: String): Task[Conversation] = {
    val convId = Conversation.id(s"$suffix-${rapid.Unique()}")
    val topic = Topic(
      conversationId = convId,
      label          = "spec",
      summary        = "spec",
      createdBy      = TestUser
    )
    val conv = Conversation(
      topics = List(TopicEntry(topic._id, topic.label, topic.summary)),
      _id    = convId
    )
    for {
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic))).unit
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).unit
    } yield conv
  }

  private def ctx(conv: Conversation): TurnContext =
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser, TestAgent),
      conversation = conv,
      turnInput    = TurnInput(conversationId = conv.id)
    )

  /** Run `cancel` and return the framework result signals the tool's
    * `execute` stream emits — a settling ToolDelta carrying outcome,
    * plus any ancillary events emitted via `ctx.emit` (e.g. Stop). */
  private def runCancel(conv: Conversation, reason: String): Task[List[Signal]] =
    CancelTool.execute(CancelInput(force = true, reason = Some(reason)), ctx(conv)).toList

  /** The Stop event `cancel` emits ancillary-style via `ctx.emit` is
    * drained into the tool's `execute` stream — not published to
    * `db.events` by a direct `execute` call. Assert on the stream. */
  private def emittedStops(signals: List[Signal]): List[Stop] =
    signals.collect { case s: Stop => s }

  private def failureText(signals: List[Signal]): Option[String] =
    signals.collectFirst {
      case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) =>
        d.summary.getOrElse(d.outcome.collect { case ToolOutcome.Failure(r, _) => r }.getOrElse(""))
    }

  // --- detectTransition (heuristic in isolation) ---------------------------

  "CancelTool.detectTransition" should {
    "match transition-shaped reasons" in Task {
      // Case 1 from the bug report — "Starting Metals server".
      CancelTool.detectTransition("Starting Metals server — user requested it")  should not be empty
      // Case 2 from the bug report — "Need to read grep output".
      CancelTool.detectTransition("Need to read grep output")                    should not be empty
      // Other transition shapes.
      CancelTool.detectTransition("waiting for tool output")                     should not be empty
      CancelTool.detectTransition("transitioning to coding mode")                should not be empty
      CancelTool.detectTransition("next step is to compile")                     should not be empty
      CancelTool.detectTransition("then I will analyze the diff")                should not be empty
      CancelTool.detectTransition("yielding to user input")                      should not be empty
      CancelTool.detectTransition("checkpoint before continuing")                should not be empty
      succeed
    }

    "let legitimate cancel reasons through" in Task {
      CancelTool.detectTransition("user requested halt via stop button")     shouldBe None
      CancelTool.detectTransition("unrecoverable failure: db connection lost") shouldBe None
      CancelTool.detectTransition("aborting per user instruction")           shouldBe None
      CancelTool.detectTransition("user clicked stop")                       shouldBe None
      CancelTool.detectTransition("")                                        shouldBe None
    }
  }

  // --- end-to-end through execute -------------------------------------------

  "CancelTool.execute" should {

    "refuse the 'Starting Metals server' reason with a Failure block" in {
      for {
        conv    <- freshConversation("refuse-start")
        signals <- runCancel(conv, "Starting Metals server — user requested it")
      } yield {
        emittedStops(signals) shouldBe empty
        val text = failureText(signals).getOrElse("")
        text should include ("refused")
        text should include ("start")
      }
    }

    "refuse the 'Need to read grep output' reason with a Failure block" in {
      for {
        conv    <- freshConversation("refuse-need-to-read")
        signals <- runCancel(conv, "Need to read grep output")
      } yield {
        emittedStops(signals) shouldBe empty
        failureText(signals).getOrElse("") should include ("need-to")
      }
    }

    "emit a Stop event for a legitimate user-halt reason" in {
      for {
        conv    <- freshConversation("legit-halt")
        signals <- runCancel(conv, "User requested halt via Stop button")
      } yield {
        val stops = emittedStops(signals)
        stops.size shouldBe 1
        stops.head.force shouldBe true
        stops.head.reason shouldBe Some("User requested halt via Stop button")
      }
    }

    "emit a Stop event for an unrecoverable-failure reason" in {
      for {
        conv    <- freshConversation("legit-failure")
        signals <- runCancel(conv, "Unrecoverable failure: provider returned 500 on retry 3")
      } yield {
        emittedStops(signals).size shouldBe 1
      }
    }

    "emit a Stop event when no reason is supplied" in {
      for {
        conv    <- freshConversation("no-reason")
        signals <- CancelTool.execute(CancelInput(), ctx(conv)).toList
      } yield {
        emittedStops(signals).size shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
