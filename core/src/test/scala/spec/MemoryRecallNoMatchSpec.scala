package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.SpaceId
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, MemorySource, TopicEntry}
import sigil.conversation.compression.StandardMemoryRetriever
import sigil.event.Event

/**
 * A question that matches NOTHING must retrieve NOTHING. Two noise
 * sources previously filled the injected set anyway on a lexical-only
 * host (no vector index wired):
 *
 *   - the lexical leg's token group AND'd with the space group
 *     degenerated to space-only matching (a `Filter.Multi` combinator
 *     defect — any in-space memory "matched" every query, in listing
 *     order);
 *   - the pipeline's vector leg fell back to `searchMemories`'s
 *     unranked space listing when vector search wasn't wired.
 *
 * Either one silently spent the whole memory budget on arbitrary
 * rows every turn.
 */
class MemoryRecallNoMatchSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val chain = List[sigil.participant.ParticipantId](TestUser, TestAgent)

  override protected def afterAll(): Unit = {
    TestSigil.reset()
    super.afterAll()
  }

  private def seedConv(suffix: String): Task[Id[Conversation]] = {
    val topic = TopicEntry(
      id = sigil.conversation.Topic.id(s"nomatch-$suffix"),
      label = "General chat",
      summary = "Any topic at all."
    )
    val conv = Conversation(topics = List(topic), _id = Conversation.id(s"nomatch-$suffix"))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_._id)
  }

  "lexical-only passive recall" should {
    "seed a small corpus" in {
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set[SpaceId](TestSpace)))
      val memories = List(
        "Prefers dark roast coffee in the morning.",
        "Works from the Berlin office on Tuesdays.",
        "The staging deploy target is cluster seven."
      ).map { fact =>
        ContextMemory(fact = fact, label = fact.take(20), summary = fact,
          source = MemorySource.Explicit, spaceId = TestSpace)
      }
      TestSigil.persistMemories(memories).map(_ should have size 3)
    }

    "retrieve nothing for a question sharing no vocabulary with the corpus" in {
      for {
        convId <- seedConv("nonsense")
        result <- StandardMemoryRetriever(limit = 5).retrieve(
          TestSigil, convId,
          Vector(ContextFrame.Text("xyzzy plugh quux?", TestUser, Id[Event]("nomatch-q"))),
          chain)
      } yield {
        withClue(s"retrieved=${result.memories}: ") {
          result.memories shouldBe empty
        }
      }
    }

    "still retrieve a genuine lexical match without a vector index" in {
      for {
        convId <- seedConv("match")
        result <- StandardMemoryRetriever(limit = 5).retrieve(
          TestSigil, convId,
          Vector(ContextFrame.Text("staging deploy target cluster?", TestUser, Id[Event]("nomatch-m"))),
          chain)
        hydrated <- Task.sequence(result.memories.toList.map(id =>
          TestSigil.withDB(_.memories.transaction(_.get(id)))))
      } yield {
        val facts = hydrated.flatten.map(_.fact)
        withClue(s"retrieved=$facts: ") {
          facts.exists(_.contains("cluster seven")) shouldBe true
          // And only genuine matches — the coffee/Berlin rows share no
          // vocabulary with the question and must not ride along.
          facts.exists(_.contains("dark roast")) shouldBe false
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
