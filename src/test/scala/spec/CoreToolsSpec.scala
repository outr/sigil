package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.event.{Message, TitleChange}
import sigil.information.{FullInformation, Information}
import sigil.signal.EventState
import sigil.tool.core.{LookupInformationTool, RespondTool}
import sigil.tool.model.{LookupInformationInput, RespondInput}

/**
 * Round-trip coverage for the new framework tools that close framework
 * gaps exposed by the audit:
 *   - [[RespondTool]] — every call carries a required `title`; emits a
 *     [[TitleChange]] when the submitted title differs from the
 *     conversation's current title, and suppresses it otherwise.
 *   - [[LookupInformationTool]] — resolves an Information id via
 *     [[sigil.Sigil.getInformation]] (backed by
 *     [[sigil.information.InMemoryInformation]] in tests) and returns a
 *     Message carrying the resolved content.
 */
class CoreToolsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConversationId(suffix: String): Id[Conversation] =
    Conversation.id(s"core-tools-$suffix-${rapid.Unique()}")

  private def turnContextFor(convId: Id[Conversation],
                             currentTitle: String = Conversation.DefaultTitle): TurnContext = {
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = Conversation(_id = convId, title = currentTitle),
      conversationView = view,
      turnInput = TurnInput(view)
    )
  }

  "RespondTool" should {
    "emit a TitleChange alongside the Message when the submitted title differs from the current title" in {
      val convId = freshConversationId("respond-retitle")
      val input = RespondInput(title = "Refactoring Notes", content = "▶Text\nHello!")
      val events = RespondTool
        .execute(input, turnContextFor(convId, currentTitle = Conversation.DefaultTitle))
        .toList
      events.map { list =>
        // TitleChange first (emitted before Message), then the Message itself.
        list should have size 2
        val tc = list.head.asInstanceOf[TitleChange]
        tc.title shouldBe "Refactoring Notes"
        tc.conversationId shouldBe convId
        tc.participantId shouldBe TestAgent
        tc.state shouldBe EventState.Complete
        list(1) shouldBe a[Message]
      }
    }

    "suppress the TitleChange when the submitted title matches the current title" in {
      val convId = freshConversationId("respond-notitle")
      val input = RespondInput(title = "Ongoing Work", content = "▶Text\nContinuing!")
      val events = RespondTool
        .execute(input, turnContextFor(convId, currentTitle = "Ongoing Work"))
        .toList
      events.map { list =>
        list should have size 1
        list.head shouldBe a[Message]
      }
    }
  }

  "LookupInformationTool" should {
    "emit a Message carrying the resolved information when the store has it" in {
      val convId = freshConversationId("lookup-hit")
      val infoId = Id[Information]("info-hit")
      val full = TestFullInformation(id = infoId, body = "HIT_BODY_42")
      // Register the concrete FullInformation subtype so its poly RW is
      // available to the tool's JSON serialization path. Safe to call
      // repeatedly — PolyType registration is idempotent.
      FullInformation.register(summon[RW[TestFullInformation]])
      TestSigil.information.put(full)

      val events = LookupInformationTool
        .execute(LookupInformationInput(id = infoId), turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val msg = list.head.asInstanceOf[Message]
        val text = msg.content.collectFirst {
          case sigil.tool.model.ResponseContent.Text(t) => t
        }.getOrElse("")
        text should include("HIT_BODY_42")
      }
    }

    "emit a not-found Message when the id doesn't resolve" in {
      val convId = freshConversationId("lookup-miss")
      val missingId = Id[Information]("info-missing")
      val events = LookupInformationTool
        .execute(LookupInformationInput(id = missingId), turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val msg = list.head.asInstanceOf[Message]
        val text = msg.content.collectFirst {
          case sigil.tool.model.ResponseContent.Text(t) => t
        }.getOrElse("")
        text should include("No Information found")
        text should include(missingId.value)
      }
    }
  }
}

/** Synthetic FullInformation subtype used only by CoreToolsSpec. */
case class TestFullInformation(id: Id[Information], body: String) extends FullInformation derives RW
