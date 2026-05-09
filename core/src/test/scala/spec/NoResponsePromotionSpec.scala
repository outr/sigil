package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, Topic, TopicEntry, TurnInput}
import sigil.event.Message
import sigil.tool.core.NoResponseTool
import sigil.tool.model.{NoResponseInput, ResponseContent}

/**
 * Coverage for sigil bug #79 — when a small/mid model misroutes
 * user-directed prose (refusal / apology / explanation) into the
 * `no_response.reason` debug field, the framework auto-promotes
 * it to a [[Message]] so the user sees what the agent intended
 * to deliver, instead of silence.
 *
 * Verifies:
 *   1. Empty / absent reason → no Message emitted (canonical
 *      `no_response` path preserved).
 *   2. Short third-person debug reason → no Message emitted.
 *   3. Long prose → promoted to a Message.
 *   4. First-person / refusal-shaped reason → promoted regardless
 *      of length.
 *   5. Multi-sentence reason → promoted regardless of length.
 */
class NoResponsePromotionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def runWith(reason: Option[String]): Task[List[Message]] = {
    val convId = Conversation.id(s"no-resp-${rapid.Unique()}")
    val topic  = TopicEntry(id = Topic.id(s"topic-$convId"), label = "test", summary = "test")
    val conv   = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).flatMap { stored =>
      val ctx = TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser, TestAgent),
        conversation = stored,
        turnInput    = TurnInput(conversationId = stored._id)
      )
      NoResponseTool.execute(NoResponseInput(reason = reason), ctx)
        .toList
        .map(_.collect { case m: Message => m })
    }
  }

  "no_response" should {

    "emit no Message when reason is absent (canonical path preserved)" in {
      runWith(None).map(_ shouldBe empty)
    }

    "emit no Message when reason is a short third-person debug breadcrumb" in {
      runWith(Some("off-topic for this agent")).map(_ shouldBe empty)
    }

    "promote a long prose reason to a respond Message (#79)" in {
      val long =
        "I don't have the ability to switch between different AI models. " +
          "I'm running on a specific model that's configured by the system I'm operating on. " +
          "If you need a different AI model, you'd need to reconfigure the deployment."
      runWith(Some(long)).map { messages =>
        messages should have size 1
        val text = messages.head.content.collect {
          case ResponseContent.Text(t)     => t
          case ResponseContent.Markdown(t) => t
        }.mkString("\n")
        text should include("don't have the ability")
      }
    }

    "promote a first-person refusal regardless of length (#79)" in {
      runWith(Some("I cannot do that.")).map { messages =>
        messages should have size 1
      }
    }

    "promote an apology regardless of length (#79)" in {
      runWith(Some("Sorry, that's outside my scope.")).map { messages =>
        messages should have size 1
      }
    }

    "promote a multi-sentence reason regardless of length (#79)" in {
      runWith(Some("Done. The job is complete.")).map { messages =>
        messages should have size 1
      }
    }

    "leave a single-sentence imperative debug reason untouched" in {
      runWith(Some("triggered by stop event")).map(_ shouldBe empty)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
