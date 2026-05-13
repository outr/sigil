package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, Topic, TopicEntry, TurnInput}
import sigil.event.{Message, Stop}
import sigil.tool.core.CancelTool
import sigil.tool.model.{CancelInput, ResponseContent}

/**
 * Coverage for [[CancelTool]]'s reason validation. Reasons that
 * read as turn-flow transitions ("starting metals", "need to read
 * grep output", "wait for results", "next step") cause the tool to
 * emit a `Failure`-block Message instead of the [[Stop]] event,
 * pushing the agent back to picking `respond` / `no_response` /
 * the actual next tool on its next turn.
 *
 * Legitimate cancel reasons (user halted, unrecoverable failure)
 * pass through and emit the Stop event as usual.
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

  private def runCancel(conv: Conversation, reason: String): Task[List[sigil.event.Event]] =
    CancelTool.execute(CancelInput(force = true, reason = Some(reason)), ctx(conv)).toList

  private def failureText(events: List[sigil.event.Event]): Option[String] =
    events.collectFirst { case m: Message if m.isFailure => m.failureReason }.flatten

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

  // --- end-to-end through executeTyped --------------------------------------

  "CancelTool.execute" should {

    "refuse the 'Starting Metals server' reason with a Failure block" in {
      for {
        conv   <- freshConversation("refuse-start")
        events <- runCancel(conv, "Starting Metals server — user requested it")
      } yield {
        events.size shouldBe 1
        events.head shouldBe a [Message]
        events.exists {
          case _: Stop => true
          case _       => false
        } shouldBe false
        val text = failureText(events).getOrElse("")
        text should include ("refused")
        text should include ("start")
      }
    }

    "refuse the 'Need to read grep output' reason with a Failure block" in {
      for {
        conv   <- freshConversation("refuse-need-to-read")
        events <- runCancel(conv, "Need to read grep output")
      } yield {
        events.size shouldBe 1
        failureText(events).getOrElse("") should include ("need-to")
      }
    }

    "emit a Stop event for a legitimate user-halt reason" in {
      for {
        conv   <- freshConversation("legit-halt")
        events <- runCancel(conv, "User requested halt via Stop button")
      } yield {
        events.size shouldBe 1
        events.head shouldBe a [Stop]
        events.head.asInstanceOf[Stop].force shouldBe true
        events.head.asInstanceOf[Stop].reason shouldBe Some("User requested halt via Stop button")
      }
    }

    "emit a Stop event for an unrecoverable-failure reason" in {
      for {
        conv   <- freshConversation("legit-failure")
        events <- runCancel(conv, "Unrecoverable failure: provider returned 500 on retry 3")
      } yield {
        events.size shouldBe 1
        events.head shouldBe a [Stop]
      }
    }

    "emit a Stop event when no reason is supplied" in {
      for {
        conv   <- freshConversation("no-reason")
        events <- CancelTool.execute(CancelInput(), ctx(conv)).toList
      } yield {
        events.size shouldBe 1
        events.head shouldBe a [Stop]
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
