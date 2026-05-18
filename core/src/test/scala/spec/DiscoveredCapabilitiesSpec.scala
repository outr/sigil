package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ConversationView, DiscoveredCapability, ParticipantProjection, TopicEntry, TurnInput}
import sigil.event.{CapabilityResults, Event, MessageRole}
import sigil.signal.EventState
import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}

/**
 * Coverage for the `discoveredCapabilities` field — a per-conversation
 * cache of `find_capability` matches. The applyParticipantProjection
 * path writes here on every `CapabilityResults`; the system prompt
 * surfaces the union so the agent doesn't re-search for tools it's
 * already seen.
 */
class DiscoveredCapabilitiesSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("disc-cap-spec")
  private val topicId = sigil.conversation.Topic.id("disc-cap-topic")

  private def fakeMatch(name: String): CapabilityMatch =
    CapabilityMatch(
      name = name,
      description = s"description for $name",
      capabilityType = CapabilityType.Tool,
      score = 1.0,
      status = CapabilityStatus.Ready
    )

  private def cap(results: List[String], query: String): CapabilityResults = {
    val originId = lightdb.id.Id[Event]("find-capability-stub")
    CapabilityResults(
      matches = results.map(fakeMatch),
      participantId = TestUser,
      conversationId = convId,
      topicId = topicId,
      query = query,
      state = EventState.Complete,
      role = MessageRole.Tool,
      origin = Some(originId)
    )
  }

  override def withFixture(test: NoArgAsyncTest) =
    super.withFixture(test)

  "applyParticipantProjectionFor(CapabilityResults)" should {

    "record discovered capabilities under the normalised query" in {
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
          _id = convId,
          topics = List(TopicEntry(topicId, "test", "test"))
        ))))
        _ <- TestSigil.publish(cap(List("read_file", "grep"), "view file source contents read"))
        proj <- TestSigil.projectionFor(TestUser, convId)
      } yield {
        proj.discoveredCapabilities.keySet should contain("view file source contents read")
        val entry = proj.discoveredCapabilities("view file source contents read")
        entry.matches.map(_.value) should contain allOf ("read_file", "grep")
      }
    }

    "merge matches when the same query fires again, refreshing `lastSeen`" in {
      val q = "list files directory glob"
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
          _id = convId,
          topics = List(TopicEntry(topicId, "test", "test"))
        ))))
        _ <- TestSigil.publish(cap(List("glob"), q))
        first <- TestSigil.projectionFor(TestUser, convId)
        _ <- Task.sleep(scala.concurrent.duration.FiniteDuration(5, "millis"))
        _ <- TestSigil.publish(cap(List("glob", "find_files"), q))
        second <- TestSigil.projectionFor(TestUser, convId)
      } yield {
        val firstEntry = first.discoveredCapabilities(q)
        val secondEntry = second.discoveredCapabilities(q)
        // firstSeen preserved across re-issues; lastSeen advances.
        secondEntry.firstSeen.value shouldBe firstEntry.firstSeen.value
        secondEntry.lastSeen.value should be >= firstEntry.lastSeen.value
        secondEntry.matches.map(_.value) should contain allOf ("glob", "find_files")
      }
    }

    "leave discoveredCapabilities untouched when query is empty" in {
      // Fresh conversation id so the empty-query assertion isn't
      // polluted by entries the earlier tests in this spec recorded.
      val emptyQueryConvId = Conversation.id(s"disc-cap-empty-${rapid.Unique()}")
      val originId = lightdb.id.Id[Event]("find-capability-stub-empty")
      val emptyQueryResults = CapabilityResults(
        matches = List(fakeMatch("respond")),
        participantId = TestUser,
        conversationId = emptyQueryConvId,
        topicId = topicId,
        query = "",
        state = EventState.Complete,
        role = MessageRole.Tool,
        origin = Some(originId)
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
          _id = emptyQueryConvId,
          topics = List(TopicEntry(topicId, "test", "test"))
        ))))
        _ <- TestSigil.publish(emptyQueryResults)
        proj <- TestSigil.projectionFor(TestUser, emptyQueryConvId)
      } yield {
        proj.discoveredCapabilities shouldBe Map.empty
        // suggestedTools still updates regardless of query presence.
        proj.suggestedTools.map(_.value) should contain("respond")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
