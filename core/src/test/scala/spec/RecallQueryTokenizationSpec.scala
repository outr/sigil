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
 * Bug #415 — the BM25 legs must tokenize the QUERY the way the indexed
 * content is tokenized: punctuation split off, stopwords dropped.
 *
 * Two compounding failures the old whitespace-split produced, both
 * pinned end-to-end here on a LEXICAL-ONLY host (no vector index, so
 * nothing masks a broken lexical leg — which is exactly how the field
 * repro looked once the vector leg couldn't carry the run):
 *
 *   1. the one discriminative term arrived carrying its punctuation
 *      (`"tobacco?"`) and matched no indexed term;
 *   2. surviving stopwords (`where`, `do`, `you`, `your`) OR-matched
 *      swathes of unrelated facts and — at `lexicalWeight = 2.0` —
 *      outvoted a vector leg that had ranked the answer first.
 */
class RecallQueryTokenizationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val chain = List[sigil.participant.ParticipantId](TestUser, TestAgent)

  /**
   * The answer, plus decoys that share the question's STOPWORDS (and
   * one that shares a surviving content word) but not its subject.
   */
  private val corpus: List[String] = List(
    "Sherlock Holmes keeps his tobacco in the toe end of a Persian slipper.",
    "Keep your hands off the evidence, Watson, as you value your reason.",
    "You were in danger of your life, and you did not know where to turn.",
    "As you value your life or your reason, keep away from the moor.",
    "Where do you suppose the inspector had gone with your notes?",
    "How do you keep your notes in order when the cases pile up?",
    "What do you do when a client will not tell you what they know?"
  )

  override protected def afterAll(): Unit = {
    TestSigil.reset()
    super.afterAll()
  }

  private def seedConv(suffix: String): Task[Id[Conversation]] = {
    val topic = TopicEntry(
      id = sigil.conversation.Topic.id(s"tok-$suffix"),
      label = "Persona questions",
      summary = "In character as a consulting detective."
    )
    val conv = Conversation(topics = List(topic), _id = Conversation.id(s"tok-$suffix"))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_._id)
  }

  private def ask(convId: Id[Conversation], question: String): Task[List[String]] =
    StandardMemoryRetriever(limit = 5)
      .retrieve(
        TestSigil,
        convId,
        Vector(ContextFrame.Text(question, TestUser, Id[Event](s"tok-q-${rapid.Unique()}"))),
        chain)
      .flatMap { result =>
        Task.sequence(result.memories.toList.map(id =>
          TestSigil.withDB(_.memories.transaction(_.get(id)))))
      }
      .map(_.flatten.map(_.fact))

  private val answer = "Sherlock Holmes keeps his tobacco in the toe end of a Persian slipper."

  "passive recall on a lexical-only host" should {
    "seed the corpus" in {
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set[SpaceId](TestSpace)))
      TestSigil.persistMemories(corpus.map { fact =>
        ContextMemory(
          fact = fact,
          label = fact.take(24),
          summary = fact,
          source = MemorySource.Explicit,
          spaceId = TestSpace)
      }).map(_ should have size corpus.size)
    }

    "surface the answer for a naturally-phrased question full of stopwords" in {
      for {
        convId <- seedConv("natural")
        facts <- ask(convId, "Where do you keep your tobacco?")
      } yield withClue(s"retrieved=$facts: ") {
        facts.headOption shouldBe Some(answer)
      }
    }

    "not be defeated by trailing punctuation alone" in {
      for {
        convId <- seedConv("punct")
        withMark <- ask(convId, "tobacco?")
        without <- ask(convId, "tobacco")
      } yield withClue(s"withMark=$withMark without=$without: ") {
        withMark.headOption shouldBe Some(answer)
        withMark shouldBe without
      }
    }

    "rank the discriminative match above decoys sharing only a common word" in {
      for {
        convId <- seedConv("flood")
        facts <- ask(convId, "How do you keep your tobacco fresh?")
      } yield withClue(s"retrieved=$facts: ") {
        // "keep" survives stopword filtering and matches three decoys;
        // the answer matches "keep" AND "tobacco", so BM25 must rank it
        // first rather than letting the common-word hits flood it out.
        facts.headOption shouldBe Some(answer)
      }
    }

    "still retrieve nothing when no discriminative term matches" in {
      for {
        convId <- seedConv("nomatch")
        facts <- ask(convId, "Where do you keep your zeppelin?")
      } yield withClue(s"retrieved=$facts: ") {
        // "keep" matches decoys, but the subject term matches nothing —
        // the answer must not appear merely because it shares a
        // function word with the question.
        facts should not contain answer
      }
    }

    "not flood recall for a pure-stopword question" in {
      for {
        convId <- seedConv("stopwords")
        facts <- ask(convId, "What do you do?")
      } yield withClue(s"retrieved=$facts: ") {
        // The fallback keeps the leg alive, so results are allowed —
        // but a question with no subject must not promote the
        // subject-specific answer.
        facts should not contain answer
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
