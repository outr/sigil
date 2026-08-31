package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.compression.{MemoryRetrievalResult, StandardMemoryRetriever}
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, MemorySource, MemoryStatus, MemoryType}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.{ConversationMode, Mode}

/**
 * Regression for sigil bug #195 — `ContextMemory.modeAffinity`
 * gates per-turn retrieval by the conversation's current mode.
 * Mode-scoped pinned directives ("always create failing unit tests
 * when coding") only load on turns matching their mode set; universal
 * memories (empty affinity) load every turn unchanged. Keeps
 * multi-mode conversations from bloating the prompt budget with
 * directives irrelevant to the current task.
 */
class MemoryModeAffinitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val codingId: Id[Mode] = TestCodingMode.id
  private val conversationId: Id[Mode] = ConversationMode.id
  private val researchId: Id[Mode] = WebResearchMode.id

  private def seedConversation(convId: Id[Conversation], mode: Mode): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, currentMode = mode, _id = convId)
    ))).unit

  private def universalPinned(convId: Id[Conversation]): ContextMemory =
    ContextMemory(
      fact = "Universal: always cite sources.",
      label = "cite-sources",
      summary = "Universal: always cite sources.",
      source = MemorySource.Explicit,
      spaceId = GlobalSpace,
      pinned = true,
      conversationId = Some(convId)
    )

  private def codingPinned(convId: Id[Conversation]): ContextMemory =
    ContextMemory(
      fact = "Coding mode: always create failing unit tests before fixing a bug.",
      label = "tdd-bug-fixes",
      summary = "Coding mode: tests before fixes.",
      source = MemorySource.Explicit,
      spaceId = GlobalSpace,
      pinned = true,
      modeAffinity = Set(codingId),
      conversationId = Some(convId)
    )

  private def multiModePinned(convId: Id[Conversation]): ContextMemory =
    ContextMemory(
      fact = "When working with code or doing research, prefer primary sources.",
      label = "primary-sources",
      summary = "Coding + research: primary sources.",
      source = MemorySource.Explicit,
      spaceId = GlobalSpace,
      pinned = true,
      modeAffinity = Set(codingId, researchId),
      conversationId = Some(convId)
    )

  private def retrieve(convId: Id[Conversation]): Task[MemoryRetrievalResult] =
    StandardMemoryRetriever().retrieve(
      sigil = TestSigil,
      conversationId = convId,
      frames = Vector.empty[ContextFrame],
      chain = List[ParticipantId](TestUser, TestAgent)
    )

  "StandardMemoryRetriever modeAffinity gate (sigil bug #195)" should {

    "surface a universal pinned memory in every mode" in {
      val convId = Conversation.id(s"affinity-universal-${rapid.Unique()}")
      val mem = universalPinned(convId)
      for {
        _ <- seedConversation(convId, TestCodingMode)
        _ <- TestSigil.persistMemoryFor(mem, List(TestUser, TestAgent), convId)
        coding <- retrieve(convId)
        _ <- seedConversation(convId, ConversationMode)
        _ = TestSigil.invalidateMemoryRetrievalCache(convId)
        conversation <- retrieve(convId)
      } yield {
        coding.criticalMemories should contain(mem._id)
        conversation.criticalMemories should contain(mem._id)
      }
    }

    "surface a coding-only pinned memory in coding mode" in {
      val convId = Conversation.id(s"affinity-coding-${rapid.Unique()}")
      val mem = codingPinned(convId)
      for {
        _ <- seedConversation(convId, TestCodingMode)
        _ <- TestSigil.persistMemoryFor(mem, List(TestUser, TestAgent), convId)
        result <- retrieve(convId)
      } yield result.criticalMemories should contain(mem._id)
    }

    "drop a coding-only pinned memory in conversation mode" in {
      val convId = Conversation.id(s"affinity-coding-dropped-${rapid.Unique()}")
      val mem = codingPinned(convId)
      for {
        _ <- seedConversation(convId, ConversationMode)
        _ <- TestSigil.persistMemoryFor(mem, List(TestUser, TestAgent), convId)
        result <- retrieve(convId)
      } yield result.criticalMemories should not contain (mem._id)
    }

    "surface a multi-mode pinned memory in EACH listed mode" in {
      val convId = Conversation.id(s"affinity-multi-${rapid.Unique()}")
      val mem = multiModePinned(convId)
      for {
        _ <- seedConversation(convId, TestCodingMode)
        _ <- TestSigil.persistMemoryFor(mem, List(TestUser, TestAgent), convId)
        coding <- retrieve(convId)
        _ <- seedConversation(convId, WebResearchMode)
        _ = TestSigil.invalidateMemoryRetrievalCache(convId)
        research <- retrieve(convId)
        _ <- seedConversation(convId, ConversationMode)
        _ = TestSigil.invalidateMemoryRetrievalCache(convId)
        conversation <- retrieve(convId)
      } yield {
        coding.criticalMemories should contain(mem._id)
        research.criticalMemories should contain(mem._id)
        conversation.criticalMemories should not contain (mem._id)
      }
    }

    "leave existing memories without a modeAffinity field loading in every mode (backward compat)" in {
      // Same data as a memory persisted by a pre-#195 release: empty
      // modeAffinity. The new retriever filter MUST treat that as
      // "universal" so existing memories don't suddenly drop out.
      val convId = Conversation.id(s"affinity-legacy-${rapid.Unique()}")
      val mem = universalPinned(convId).copy(modeAffinity = Set.empty)
      for {
        _ <- seedConversation(convId, TestCodingMode)
        _ <- TestSigil.persistMemoryFor(mem, List(TestUser, TestAgent), convId)
        result <- retrieve(convId)
      } yield result.criticalMemories should contain(mem._id)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
