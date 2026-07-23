package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextSummary, Conversation}
import sigil.conversation.compression.{MemoryContextCompressor, StandardContextCurator}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.consult.SummarizationInput
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The Summaries stream must not thrash the prompt cache or grow
 * unbounded. Measured live: a rolling intra-turn summary appended a
 * near-duplicate paragraph into the cacheable system prefix every
 * iteration — the whole prompt re-cached at creation rates each turn
 * (~$45 of a $54 turn was cache creation) while the section grew 1.7K
 * → 22K tokens with ~zero new information.
 *
 * Verifies:
 *   1. The system prompt (the cacheable prefix) is byte-identical
 *      across a multi-iteration turn — summaries ride the volatile
 *      tail, never the prefix.
 *   2. A fresh rolling summary SUPERSEDES earlier summaries whose
 *      event coverage it subsumes: one evolving artifact, not an
 *      append log.
 *   3. The curator's summary selection is subsumption-filtered and
 *      token-budgeted — the section plateaus instead of growing
 *      unbounded, and always keeps at least the newest summary.
 */
class SummaryCacheStabilitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "summary-cache")
  TestSigil.testModel(modelId)

  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[_root_.sigil.participant.ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }

  // ---- 1. stable system prefix across iterations ----

  /** Captures each primary call's system string; iterations 1..2 are
    * tool calls, 3 responds. */
  private final class SystemCapturingProvider extends Provider {
    val systems = new ConcurrentLinkedQueue[String]()
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = calls.incrementAndGet()
      systems.add(input.system)
      val callId = CallId(s"call-${rapid.Unique()}")
      val emits: List[ProviderEvent] =
        if (n < 3)
          List(
            ProviderEvent.ToolCallStart(callId, GetMagicNumberTool.name.value),
            ProviderEvent.ToolCallComplete(callId, GetMagicNumberInput()),
            ProviderEvent.Done(StopReason.ToolCall)
          )
        else
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.ToolCallComplete(callId, RespondInput(
              topicLabel   = TestTopicEntry.label,
              topicSummary = TestTopicEntry.summary,
              content      = "Done.",
              endsTurn     = true
            )),
            ProviderEvent.Done(StopReason.ToolCall)
          )
      Stream.emits(emits)
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames :+ GetMagicNumberTool.name,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def waitFor(timeout: FiniteDuration)(cond: => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] =
      if (cond || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    loop
  }

  "the system prompt across a multi-iteration turn" should {

    "stay byte-identical — summaries never enter the cacheable prefix" in {
      val provider = new SystemCapturingProvider
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setMemoryExtractor(NoExtraction)
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(sigil.GlobalSpace)))
      // Memory surfacing is an app-level curate decision (the default
      // curator uses NoOpMemoryRetriever) — wire the standard
      // retriever so pinned directives reach the prompt.
      TestSigil.setCurate((cid, mid, chain) =>
        sigil.conversation.compression.StandardContextCurator(TestSigil,
          memoryRetriever = sigil.conversation.compression.StandardMemoryRetriever()).curate(cid, mid, chain))
      val convId = Conversation.id(s"sys-stable-${rapid.Unique()}")
      val conv   = Conversation(topics = TestTopicStack, participants = List(makeAgent()), _id = convId)
      val longFact = "The team's Scala backend standardizes on rapid Streams for all concurrency: " +
        ("every service, worker, and batch job composes rapid Tasks; " * 8) +
        "Futures are forbidden outside interop shims."
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        // A persisted summary exists BEFORE the turn — the shape that
        // previously rendered into the prefix.
        _ <- TestSigil.persistSummary(ContextSummary(
               text = "Earlier the user set up the project workspace.",
               conversationId = convId,
               tokenEstimate = 12
             ))
        // Two pinned directives: one whose summary elides a materially
        // longer fact (gets a lookup handle), one short (stays clean).
        _ <- TestSigil.persistMemory(sigil.conversation.ContextMemory(
               fact = longFact,
               label = "scala-concurrency",
               summary = "Backend concurrency uses rapid Streams.",
               source = sigil.conversation.MemorySource.Explicit,
               pinned = true,
               spaceId = sigil.GlobalSpace,
               key = Some("scala-concurrency"),
               conversationId = Some(convId)
             ))
        _ <- TestSigil.persistMemory(sigil.conversation.ContextMemory(
               fact = "The user's name is Matt.",
               label = "user-name",
               summary = "The user's name is Matt.",
               source = sigil.conversation.MemorySource.Explicit,
               pinned = true,
               spaceId = sigil.GlobalSpace,
               key = Some("user-name"),
               conversationId = Some(convId)
             ))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("Keep working.")),
               state          = EventState.Complete
             ))
        _ <- waitFor(20.seconds)(provider.calls.get() >= 3)
      } yield {
        TestSigil.reset()
        val systems = provider.systems.asScala.toList
        withClue(s"captured ${systems.size} system prompts; lengths=${systems.map(_.length)}: ") {
          systems.size should be >= 3
          systems.distinct should have size 1
          // The summary is genuinely out of the prefix, not just stable.
          systems.head should not include "Earlier the user set up the project workspace."
          // A summary-elided memory carries its drill-down handle; a
          // short one renders as a clean line without one.
          systems.head should include ("""[full: lookup("scala-concurrency")]""")
          systems.head should not include """[full: lookup("user-name")]"""
        }
      }
    }
  }

  // ---- 2. supersede at the writer ----

  private final class ScriptedSummarizer extends Provider {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = calls.incrementAndGet()
      val callId = CallId(s"sum-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "summarize_conversation"),
        ProviderEvent.ToolCallComplete(callId, SummarizationInput(s"Rolling summary v$n.", tokenEstimate = 10)),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  "compressCovering" should {

    "supersede earlier summaries whose coverage the fresh one subsumes" in {
      val summarizer = new ScriptedSummarizer
      TestSigil.setProvider(Task.pure(summarizer))
      val convId = Conversation.id(s"supersede-${rapid.Unique()}")
      val eventIds = (1 to 6).toList.map(_ => Event.id())
      val frames = eventIds.map { id =>
        sigil.conversation.ContextFrame.Text(content = s"frame-${id.value.take(4)}",
          participantId = TestUser, sourceEventId = id)
      }.toVector
      val compressor = MemoryContextCompressor()
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(topics = TestTopicStack, _id = convId))))
        first <- compressor.compressCovering(TestSigil, modelId, List(TestAgent),
                   frames.take(3), convId, eventIds.take(3))
        between <- TestSigil.summariesFor(convId)
        second <- compressor.compressCovering(TestSigil, modelId, List(TestAgent),
                    frames, convId, eventIds)
        after <- TestSigil.summariesFor(convId)
      } yield {
        first should not be empty
        between should have size 1
        second should not be empty
        withClue(s"calls=${summarizer.calls.get()} after=${after.map(s => (s.text, s.coversEventIds.size))}: ") {
          after should have size 1
          after.head.text should include ("v2")
          after.head.coversEventIds should have size 6
        }
      }
    }
  }

  // ---- 3. curator selection: subsumption + budget ----

  "selectSummaries" should {

    def summary(convId: Id[Conversation], text: String, tokens: Int,
                covers: List[Id[Event]] = Nil, createdAt: Long = 0L): ContextSummary =
      ContextSummary(text = text, conversationId = convId, tokenEstimate = tokens,
        coversEventIds = covers, created = Timestamp(createdAt))

    "drop subsumed restatements and budget newest-first, keeping at least the newest" in Task {
      val convId = Conversation.id("select")
      val e = (1 to 4).toList.map(_ => Event.id())
      val stale    = summary(convId, "old restatement", 10, covers = e.take(2), createdAt = 1000L)
      val superset = summary(convId, "current rolling", 10, covers = e, createdAt = 2000L)
      val other    = summary(convId, "unrelated narrative", 10, createdAt = 1500L)
      val selected = StandardContextCurator.selectSummaries(List(stale, superset, other), tokenBudget = 4096)
      selected.map(_.text) should contain theSameElementsAs List("current rolling", "unrelated narrative")

      // Budget: 3 non-overlapping summaries of 2000 tokens each with a
      // 4096 budget → newest two kept, oldest dropped.
      val s1 = summary(convId, "oldest", 2000, createdAt = 1L)
      val s2 = summary(convId, "middle", 2000, createdAt = 2L)
      val s3 = summary(convId, "newest", 2000, createdAt = 3L)
      val budgeted = StandardContextCurator.selectSummaries(List(s1, s2, s3), tokenBudget = 4096)
      budgeted.map(_.text) shouldBe List("middle", "newest")

      // A single over-budget summary is still kept — an empty section
      // would orphan its elided frames.
      val huge = summary(convId, "huge", 10000, createdAt = 5L)
      StandardContextCurator.selectSummaries(List(huge), tokenBudget = 4096).map(_.text) shouldBe List("huge")
    }.map(identity)
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
