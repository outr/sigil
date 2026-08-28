package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.SpaceId
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, MemorySource, TopicEntry}
import sigil.conversation.compression.StandardMemoryRetriever
import sigil.embedding.EmbeddingProvider
import sigil.event.Event
import sigil.vector.InMemoryVectorIndex

/**
 * Bug #413 — the passive-recall query must not dilute specific-fact
 * retrieval. The scenario mirrors the field repro: a persona corpus
 * (many general-character memories) plus ONE memory answering a
 * specific factual question. Composing the retrieval query as
 * `topic label + topic summary + keywords + question` biases both
 * legs toward the persona theme and crowds the fact out; the fixed
 * default queries the question ALONE on the vector + lexical legs and
 * carries the conversational context through a separate keyword leg.
 *
 * The embedder is deterministic: a text's vector is the normalized
 * count of persona-themed vs. fact-themed terms, so a composite query
 * (many persona terms, one fact term) lands near the persona centroid
 * exactly the way a real embedding does.
 */
class MemoryRecallDilutionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private object PersonaEmbedding extends EmbeddingProvider {
    override val id: String = "persona-controlled"
    override val dimensions: Int = 3
    private val personaWords = Set(
      "sherlock", "holmes", "detective", "deduction", "observation", "character",
      "converse", "investigate", "problems", "logic", "violin", "baker")
    private val factWords = Set("tobacco", "slipper", "persian", "mantelpiece")
    override def embed(text: String): Task[Vector[Double]] = Task {
      val tokens = text.toLowerCase.split("[^a-z]+").iterator.filter(_.nonEmpty).toList
      val p = tokens.count(personaWords.contains).toDouble
      val t = tokens.count(factWords.contains).toDouble
      val other = if (p == 0.0 && t == 0.0) 1.0 else 0.05
      val v = Array(t, p, other)
      val norm = math.sqrt(v.map(x => x * x).sum)
      v.map(_ / norm).toVector
    }
    override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
      Task.sequence(texts.map(embed))
  }

  private val personaFacts: List[String] = List(
    "Identity: a consulting detective who solves problems by observation and deduction.",
    "Voice: speaks with precise logic and characteristic detective phrasing.",
    "Method: careful observation of small details drives every deduction.",
    "Demonstrated knowledge: chemistry, anatomy, and the criminal character.",
    "Habit: plays the violin while working through a difficult deduction.",
    "Setting: consulting rooms where clients present their problems.",
    "Approach: investigate problems from observation before forming theories.",
    "Reputation: the foremost consulting detective in matters of deduction.",
    "Manner: converse with clients to draw out every observation.",
    "Principle: eliminate the impossible through logic and deduction.",
    "Practice: the detective tests each observation against the evidence.",
    "Style: in character at all times, reasoning aloud through deduction.",
    // Decoys that MENTION the fact's rare term inside persona-themed text —
    // without them a 13-document BM25 index makes "tobacco" so rare that
    // the lexical leg rescues the fact even under the diluted composite,
    // hiding the regression this spec pins.
    "Observation: the detective wrote a monograph on tobacco ash as a deduction exercise.",
    "Character note: tobacco smoke fills the room while the detective reasons through problems.",
    "Habit: the detective packs tobacco thoughtfully while weighing each observation."
  )

  private val slipperFact =
    "He keeps his tobacco in the toe end of a Persian slipper upon the mantelpiece."

  private val question = "Where do you keep your tobacco?"

  private val compositeQuery =
    "Sherlock Holmes. Converse in character as Sherlock Holmes; investigate problems by " +
      s"observation and deduction. $question"

  private val chain = List[sigil.participant.ParticipantId](TestUser, TestAgent)
  @volatile private var slipperId: Id[ContextMemory] = ContextMemory.id("unset")

  private def seedConv(suffix: String, keywords: List[String]): Task[Id[Conversation]] = {
    val topic = TopicEntry(
      id = sigil.conversation.Topic.id(s"dilution-$suffix"),
      label = "Sherlock Holmes",
      summary = "Converse in character as Sherlock Holmes; investigate problems by observation and deduction."
    )
    val conv = Conversation(
      topics = List(topic),
      currentKeywords = keywords.toVector,
      _id = Conversation.id(s"dilution-$suffix")
    )
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_._id)
  }

  private def questionFrames: Vector[ContextFrame] =
    Vector(ContextFrame.Text(question, TestUser, Id[Event]("dilution-q")))

  private def retrievedIds(retriever: StandardMemoryRetriever,
                           convId: Id[Conversation],
                           frames: Vector[ContextFrame]): Task[Vector[Id[ContextMemory]]] =
    retriever.retrieve(TestSigil, convId, frames, chain).map(_.memories)

  override protected def afterAll(): Unit = {
    TestSigil.reset()
    super.afterAll()
  }

  "passive memory recall" should {
    "seed the persona corpus" in {
      TestSigil.setEmbeddingProvider(PersonaEmbedding)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set[SpaceId](TestSpace)))
      val memories = (slipperFact :: personaFacts).map { fact =>
        ContextMemory(
          fact = fact,
          label = fact.take(24),
          summary = fact,
          source = MemorySource.Explicit,
          spaceId = TestSpace
        )
      }
      TestSigil.persistMemories(memories).map { stored =>
        slipperId = stored.head._id
        stored should have size 16
      }
    }

    "surface the specific fact for a specific question under the default composition" in {
      for {
        convId <- seedConv("default", List("sherlock", "holmes"))
        ids <- retrievedIds(StandardMemoryRetriever(limit = 5), convId, questionFrames)
      } yield {
        withClue(s"retrieved=$ids slipper=$slipperId: ") {
          ids should contain (slipperId)
        }
      }
    }

    "lose the fact under the pre-#413 composite query (the regression this spec pins)" in {
      val composite = StandardMemoryRetriever(
        limit = 5,
        queryFrom = Some((_, _) => Some(compositeQuery)),
        keywordWeight = 0.0
      )
      for {
        convId <- seedConv("composite", List("sherlock", "holmes"))
        ids <- retrievedIds(composite, convId, questionFrames)
      } yield {
        withClue(s"retrieved=$ids slipper=$slipperId: ") {
          // The diluted query still returns memories — just not the one
          // that answers the question. If this ever starts passing the
          // slipper through, the fixture no longer discriminates and
          // the spec must be re-tuned.
          ids should not be empty
          ids should not contain slipperId
        }
      }
    }

    "reach the fact through the keyword leg when the turn has no user message" in {
      for {
        convId <- seedConv("keywords", List("tobacco"))
        ids <- retrievedIds(StandardMemoryRetriever(limit = 5), convId, Vector.empty)
      } yield {
        withClue(s"retrieved=$ids slipper=$slipperId: ") {
          ids should contain (slipperId)
        }
      }
    }

    "still serve thematic questions from the persona corpus" in {
      val thematic = Vector[ContextFrame](
        ContextFrame.Text("Tell me about your observation and deduction methods.", TestUser, Id[Event]("dilution-t")))
      for {
        convId <- seedConv("thematic", List("sherlock", "holmes"))
        ids <- retrievedIds(StandardMemoryRetriever(limit = 5), convId, thematic)
      } yield {
        withClue(s"retrieved=$ids: ") {
          // A theme-shaped question retrieves theme memories — the
          // undiluted composition helps specific questions without
          // starving general ones.
          ids should not be empty
          ids should not contain slipperId
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
