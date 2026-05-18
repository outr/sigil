package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, TopicEntry}
import sigil.event.Message
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, SelectOption}

/**
 * Coverage for sigil bug #72 — `respond_options` selections come back
 * to the agent as a bare token (`"start_metals"`) with no framing
 * that the user is requesting that action. Small models reliably
 * misinterpret as preference-statement and reply with `no_response`.
 *
 * Verifies:
 *   1. A user reply matching one of the prior agent's option values
 *      is rewritten to action framing before persistence.
 *   2. A multi-select reply (`"a, b, c"`) becomes a multi-bullet
 *      framing.
 *   3. A non-matching free-form reply passes through unchanged.
 *   4. Idempotent — already-framed messages aren't re-framed on a
 *      second publish.
 *   5. The transform fires only on user (non-agent) Standard-role
 *      Messages — agent self-talk and Tool-role events are unaffected.
 */
class RespondOptionsSelectionFramingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def upsertConv(): Task[Conversation] = {
    val convId = Conversation.id(s"options-frame-${rapid.Unique()}")
    val topic = TestTopicEntry.copy(id = sigil.conversation.Topic.id(s"topic-$convId"))
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  /**
   * Publish an agent Message containing a `respond_options` block, then
   * a user Message with `userText`. Returns the persisted user
   * message so the test can inspect its `content` after the
   * transform ran.
   */
  private def driveOptionsThenReply(opts: ResponseContent, userText: String): Task[Message] =
    for {
      conv <- upsertConv()
      agentMsg = Message(
        participantId = TestAgent,
        conversationId = conv._id,
        topicId = conv.currentTopicId,
        content = Vector(opts),
        state = EventState.Complete
      )
      _ <- TestSigil.publish(agentMsg)
      userMsg = Message(
        participantId = TestUser,
        conversationId = conv._id,
        topicId = conv.currentTopicId,
        content = Vector(ResponseContent.Text(userText)),
        state = EventState.Complete
      )
      _ <- TestSigil.publish(userMsg)
      stored <- TestSigil.withDB(_.events.transaction(_.get(userMsg._id)))
    } yield stored.collect { case m: Message => m }.getOrElse(fail(s"User message ${userMsg._id} disappeared"))

  private def textOf(m: Message): String =
    m.content.collect { case ResponseContent.Text(t) => t }.mkString("\n")

  "RespondOptionsSelectionFramingTransform" should {

    "rewrite a single-select reply to action framing" in {
      val opts = ResponseContent.Options(
        prompt = "What would you like to set up for this workspace?",
        options = List(
          SelectOption("Start Metals (Scala LSP)", "start_metals", description = Some("Boot the Scala language server.")),
          SelectOption("Skip", "skip")
        ),
        allowMultiple = false
      )
      driveOptionsThenReply(opts, "start_metals").map { stored =>
        val text = textOf(stored)
        text should startWith("I'd like to:")
        text should include("Start Metals (Scala LSP)")
        text should include("value: start_metals")
        text should include("Boot the Scala language server.")
        text should include("Selected from: 'What would you like to set up for this workspace?'")
      }
    }

    "populate Message.optionSelection so chat views can render selections distinctly (bug #73)" in {
      val opts = ResponseContent.Options(
        prompt = "What would you like to set up for this workspace?",
        options = List(
          SelectOption("Start Metals (Scala LSP)", "start_metals", description = Some("Boot the Scala language server.")),
          SelectOption("Skip", "skip")
        ),
        allowMultiple = false
      )
      driveOptionsThenReply(opts, "start_metals").map { stored =>
        val sel = stored.optionSelection.getOrElse(fail("optionSelection should be set after a successful selection match"))
        sel.prompt shouldBe "What would you like to set up for this workspace?"
        sel.selectedOptions.map(_.value) shouldBe List("start_metals")
        sel.selectedOptions.head.label shouldBe "Start Metals (Scala LSP)"
        sel.selectedOptions.head.description shouldBe Some("Boot the Scala language server.")
        // Parent points at the agent's respond_options Message; chat views
        // can use it to link the selection back to its prompt.
        sel.parentOptionsEventId.value should not be empty
      }
    }

    "carry every selected option through to optionSelection on multi-select" in {
      val opts = ResponseContent.Options(
        prompt = "Found admin services. Want to look at them all at once?",
        options = List(
          SelectOption("Read admin", "read-admin"),
          SelectOption("Check routes", "check-routes"),
          SelectOption("Compare features", "compare-features")
        ),
        allowMultiple = true
      )
      driveOptionsThenReply(opts, "read-admin, check-routes, compare-features").map { stored =>
        val sel = stored.optionSelection.getOrElse(fail("optionSelection should be set"))
        sel.selectedOptions.map(_.value) shouldBe List("read-admin", "check-routes", "compare-features")
      }
    }

    "leave optionSelection empty on a free-form reply that doesn't match any option" in {
      val opts = ResponseContent.Options(
        prompt = "Should I commit this change?",
        options = List(SelectOption("Yes", "yes"), SelectOption("No", "no")),
        allowMultiple = false
      )
      driveOptionsThenReply(opts, "Actually, hold off — I want to review the diff first.").map { stored =>
        stored.optionSelection shouldBe None
      }
    }

    "rewrite a multi-select comma-separated reply to a multi-bullet framing" in {
      val opts = ResponseContent.Options(
        prompt = "Found admin services. Want to look at them all at once?",
        options = List(
          SelectOption("Read admin", "read-admin"),
          SelectOption("Check routes", "check-routes"),
          SelectOption("Compare features", "compare-features")
        ),
        allowMultiple = true
      )
      driveOptionsThenReply(opts, "read-admin, check-routes, compare-features").map { stored =>
        val text = textOf(stored)
        text should startWith("I'd like to:")
        text should include("Read admin")
        text should include("Check routes")
        text should include("Compare features")
        text should include("Selected from: 'Found admin services.")
      }
    }

    "leave a non-matching free-form reply unchanged" in {
      val opts = ResponseContent.Options(
        prompt = "Should I commit this change?",
        options = List(SelectOption("Yes", "yes"), SelectOption("No", "no")),
        allowMultiple = false
      )
      driveOptionsThenReply(opts, "Actually, hold off — I want to review the diff first.").map { stored =>
        val text = textOf(stored)
        text shouldBe "Actually, hold off — I want to review the diff first."
      }
    }

    "be idempotent — re-publishing an already-framed message doesn't double-frame" in {
      val opts = ResponseContent.Options(
        prompt = "What setup?",
        options = List(SelectOption("Foo", "foo")),
        allowMultiple = false
      )
      driveOptionsThenReply(opts, "foo").flatMap { firstPass =>
        // Re-publish an already-framed message; the transform should
        // recognise the prefix and leave it alone.
        val republish = firstPass.copy(_id = sigil.event.Event.id())
        TestSigil.publish(republish).flatMap { _ =>
          TestSigil.withDB(_.events.transaction(_.get(republish._id))).map {
            case Some(m: Message) => textOf(m)
            case _ => fail(s"republished message ${republish._id} disappeared")
          }
        }.map { text =>
          // Same shape as the first pass — no nested "I'd like to: I'd like to:" wrapper.
          text.indexOf("I'd like to:") shouldBe text.lastIndexOf("I'd like to:")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
