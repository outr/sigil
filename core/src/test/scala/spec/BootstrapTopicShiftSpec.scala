package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.Model
import sigil.event.{TopicChange, TopicChangeKind}
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.tool.consult.TopicClassifierInput
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import sigil.tool.consult.TopicClassifierTool

/**
 * A label proposed before any user input reaches the context
 * (greeting turns, agent-initiated openers) relabels the seed topic
 * in place instead of minting a second topic. The seed is a
 * placeholder — "greeting" → "actual subject" is the conversation
 * finding its subject, not a subject change — so topic churn on an
 * empty conversation is pure noise: it fragments the client's
 * per-topic visualisation (colour strips, dividers) across topics
 * that never held distinct subject matter.
 *
 * Verifies:
 *   1. Bootstrap era (single seed topic, no user message): the
 *      proposal renames the seed — same topic id, no classifier
 *      consult.
 *   2. A `labelLocked` seed is left alone (no event, no consult).
 *   3. A user message in context routes through the classifier as
 *      before.
 *   4. A second topic on the stack routes through the classifier
 *      even with no user message.
 */
class BootstrapTopicShiftSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "bootstrap-shift")
  TestSigil.testModel(modelId)

  /**
   * Classifier stub that counts consults; the framework must not
   * reach it on the bootstrap path.
   */
  private class CountingClassifierProvider(scriptedKind: String) extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.incrementAndGet()
      val callId = CallId(s"classify-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "classify_topic_shift"),
        ProviderEvent.toolCall(callId, new TopicClassifierTool(Nil))(TopicClassifierInput(kind = scriptedKind)),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def seedConversation(label: String, labelLocked: Boolean = false, extraTopic: Option[String] = None): Task[Conversation] = {
    val convId = Conversation.id(s"bootstrap-${rapid.Unique()}")
    val seed = Topic(
      conversationId = convId,
      label = label,
      summary = s"$label summary",
      labelLocked = labelLocked,
      createdBy = TestUser)
    val extra = extraTopic.map(l => Topic(conversationId = convId, label = l, summary = s"$l summary", createdBy = TestUser))
    val entries = (seed :: extra.toList).map(t => TopicEntry(t._id, t.label, t.summary))
    for {
      _ <- TestSigil.withDB(_.topics.transaction(tx => (seed :: extra.toList).map(tx.upsert).last))
      conv <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(_id = convId, topics = entries))))
    } yield conv
  }

  private def shift(conv: Conversation, proposed: String, userMessage: String): Task[List[sigil.event.Event]] =
    TestSigil.resolveTopicShift(
      proposedLabel = proposed,
      proposedSummary = s"$proposed summary",
      caller = TestAgent,
      conversation = conv,
      currentTopic = conv.currentTopic,
      previousTopics = conv.previousTopics,
      modelId = modelId,
      chain = List(TestUser, TestAgent),
      userMessage = userMessage
    )

  "resolveTopicShift on a bootstrap-era conversation" should {

    "rename the seed topic in place without consulting the classifier" in {
      val provider = new CountingClassifierProvider("New")
      TestSigil.setProvider(Task.pure(provider))
      for {
        conv <- seedConversation("Sage")
        events <- shift(conv, "sigil project setup", userMessage = "")
        topicRow <- TestSigil.withDB(_.topics.transaction(_.get(conv.currentTopic.id)))
      } yield {
        provider.calls.get() shouldBe 0
        events match {
          case List(tc: TopicChange) =>
            tc.kind shouldBe a[TopicChangeKind.Rename]
            tc.topicId shouldBe conv.currentTopic.id
            tc.newLabel shouldBe "sigil project setup"
          case other => fail(s"expected a single Rename TopicChange, got: $other")
        }
        topicRow.get.label shouldBe "sigil project setup"
      }
    }

    "leave a labelLocked seed alone" in {
      val provider = new CountingClassifierProvider("New")
      TestSigil.setProvider(Task.pure(provider))
      for {
        conv <- seedConversation("Support Desk", labelLocked = true)
        events <- shift(conv, "Greeting", userMessage = "")
      } yield {
        provider.calls.get() shouldBe 0
        events shouldBe empty
      }
    }

    "route through the classifier once a user message is in context" in {
      val provider = new CountingClassifierProvider("New")
      TestSigil.setProvider(Task.pure(provider))
      for {
        conv <- seedConversation("Sage")
        events <- shift(conv, "compiler bug hunt", userMessage = "help me find this compiler bug")
      } yield {
        provider.calls.get() shouldBe 1
        events match {
          case List(tc: TopicChange) =>
            tc.kind shouldBe a[TopicChangeKind.Switch]
            tc.topicId should not be conv.currentTopic.id
          case other => fail(s"expected a single Switch TopicChange, got: $other")
        }
      }
    }

    "route through the classifier once a second topic exists, even with no user message" in {
      val provider = new CountingClassifierProvider("NoChange")
      TestSigil.setProvider(Task.pure(provider))
      for {
        conv <- seedConversation("Sage", extraTopic = Some("compiler bug hunt"))
        events <- shift(conv, "type-class derivation", userMessage = "")
      } yield {
        provider.calls.get() shouldBe 1
        events shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
