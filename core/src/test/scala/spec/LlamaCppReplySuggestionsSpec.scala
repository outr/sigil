package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ReplySuggestionsConfig}
import sigil.db.Model
import sigil.event.{AgentState, Message}
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{GenerationSettings, Instructions, ReasoningMode}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.signal.{EventState, Signal, SuggestedReplies}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * End-to-end reply suggestions against a real model: the agent answers
 * a concrete question with `respond`, the turn settles, and the
 * post-settle consult predicts what the person types next. Fixtures
 * under `core/src/test/resources/provider-cache/LlamaCppReplySuggestionsSpec/`
 * replay deterministically; `CACHE_MODE=record` re-records against
 * [[TestSigil.llamaCppHost]].
 *
 * Assertions are shape-and-sanity, not exact text — the point is that a
 * live model, given the framework's prompt, returns usable composer
 * text rather than markup, a quoted transcript, or an essay.
 */
class LlamaCppReplySuggestionsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  implicit override protected val testTimeout: FiniteDuration = 5.minutes

  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setProvider(CachedProviderFixtures.wrap(this, LlamaCppProvider(TestSigil, TestSigil.llamaCppHost)))

  private val modelId: Id[Model] = Model.id("qwen3.5-9b-q4_k_m")
  TestSigil.testModel(modelId)

  /**
   * The per-turn extractor issues its own live call after each turn;
   * this suite is scoped to the suggestion consult.
   */
  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[_root_.sigil.participant.ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }
  TestSigil.setMemoryExtractor(NoExtraction)

  private def makeAgent() = DefaultAgentParticipant(
    id = TestAgent,
    modelId = modelId,
    toolNames = CoreTools.coreToolNames,
    instructions = Instructions(),
    generationSettings = GenerationSettings(
      maxOutputTokens = Some(2000),
      temperature = Some(0.0),
      reasoningMode = ReasoningMode.Off
    )
  )

  private def subscribe(): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signals
      .takeWhile(_ => running)
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  private def awaitSuggestions(recorded: ConcurrentLinkedQueue[Signal],
                               convId: Id[Conversation],
                               timeout: FiniteDuration): Task[List[SuggestedReplies]] = {
    def snapshot: List[SuggestedReplies] = recorded.iterator().asScala.collect {
      case s: SuggestedReplies if s.conversationId == convId => s
    }.toList
    def loop(remainingMs: Long): Task[List[SuggestedReplies]] =
      if (snapshot.nonEmpty || remainingMs <= 0) Task.pure(snapshot)
      else Task.sleep(200.millis).flatMap(_ => loop(remainingMs - 200))
    loop(timeout.toMillis)
  }

  private def waitForAgentTurn(convId: Id[Conversation], after: Long, timeout: FiniteDuration): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = TestSigil.withDB(_.events.transaction(_.list)).flatMap { all =>
      val settled = all.exists {
        case a: AgentState if a.conversationId == convId && a.timestamp.value >= after && a.state == EventState.Complete => true
        case _ => false
      }
      if (settled) Task.unit
      else if (System.currentTimeMillis() < deadline) Task.sleep(200.millis).flatMap(_ => loop)
      else Task.unit
    }
    loop
  }

  /**
   * Sanity a composer can rely on: something to type, short enough to
   * sit in an input, and free of the markup a model reaches for when
   * it forgets it is writing AS the user.
   */
  private def beComposerReady(suggestion: String): org.scalatest.Assertion =
    withClue(s"suggestion: '$suggestion' — ") {
      suggestion.trim should not be empty
      suggestion.length should be < 300
      suggestion should not include "```"
      suggestion should not startWith "\""
      suggestion should not startWith "- "
      suggestion should not startWith "* "
      suggestion.linesIterator.size should be(1)
    }

  private def runTurn(convId: Id[Conversation], question: String): Task[Timestamp] = {
    val conv = Conversation(topics = List(TestTopicEntry), _id = convId, participants = List(makeAgent()))
    val now = Timestamp()
    val userMsg = Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(question)),
      state = EventState.Complete,
      timestamp = now
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(userMsg)
      _ <- waitForAgentTurn(convId, after = now.value, timeout = 3.minutes)
    } yield now
  }

  "reply suggestions against a live model" should {
    "predict a single composer-ready next message" in {
      TestSigil.setReplySuggestions(ReplySuggestionsConfig(fallbackModelId = modelId))
      val (recorded, stop) = subscribe()
      val convId = Conversation.id(s"live-suggest-single-${rapid.Unique()}")
      for {
        _ <- runTurn(convId, "What is the capital of France? Answer in one sentence.")
        notices <- awaitSuggestions(recorded, convId, timeout = 2.minutes)
        events <- TestSigil.withDB(_.conversationEvents(convId))
      } yield {
        stop()
        notices should not be empty
        val notice = notices.head
        notice.suggestions should have size 1
        beComposerReady(notice.suggestions.head)
        val agentReplies = events.collect {
          case m: Message if m.participantId == TestAgent && m.state == EventState.Complete => m._id
        }
        agentReplies should contain(notice.forMessageId)
      }
    }

    "predict three composer-ready candidates" in {
      TestSigil.setReplySuggestions(ReplySuggestionsConfig(fallbackModelId = modelId, count = 3))
      val (recorded, stop) = subscribe()
      val convId = Conversation.id(s"live-suggest-multi-${rapid.Unique()}")
      for {
        _ <- runTurn(convId, "How do I reverse a List in Scala? Answer in one sentence.")
        notices <- awaitSuggestions(recorded, convId, timeout = 2.minutes)
      } yield {
        stop()
        notices should not be empty
        val suggestions = notices.head.suggestions
        suggestions should not be empty
        suggestions.size should be <= 3
        suggestions.distinct should have size suggestions.size
        suggestions.foreach(beComposerReady)
        succeed
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
