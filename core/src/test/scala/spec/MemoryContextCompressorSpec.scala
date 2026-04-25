package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextFrame, Conversation, MemorySource}
import sigil.conversation.compression.MemoryContextCompressor
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.tool.consult.{ExtractMemoriesInput, SummarizationInput}
import sigil.vector.InMemoryVectorIndex
import spice.http.HttpRequest

/**
 * End-to-end coverage of the two-pass [[MemoryContextCompressor]]
 * against a stubbed provider: the extraction pass persists facts as
 * [[sigil.conversation.ContextMemory]] records in the configured
 * compression space; the summarization pass persists a
 * [[sigil.conversation.ContextSummary]]. Also covers the fall-through
 * when [[sigil.Sigil.compressionMemorySpace]] is None — extraction is
 * skipped but the summary still lands.
 */
class MemoryContextCompressorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  // Seed a Model record so curator / downstream code can resolve it.
  TestSigil.cache.replace(List(Model(
    canonicalSlug = "test/model",
    huggingFaceId = "",
    name = "Test Model",
    description = "",
    contextLength = 1000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(1000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  ))).sync()

  private def textFrame(s: String, id: String): ContextFrame.Text =
    ContextFrame.Text(s, TestUser, Id[Event](id))

  private class StubProvider(facts: List[String], summary: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("StubProvider"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val toolName = input.tools.headOption.map(_.schema.name.value).getOrElse("")
      toolName match {
        case "extract_memories" =>
          val callId = CallId("extract")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, toolName),
            ProviderEvent.ToolCallComplete(callId, ExtractMemoriesInput(facts)),
            ProviderEvent.Done(StopReason.ToolCall)
          ))
        case "summarize_conversation" =>
          val callId = CallId("summarize")
          Stream.emits(List(
            ProviderEvent.ToolCallStart(callId, toolName),
            ProviderEvent.ToolCallComplete(callId, SummarizationInput(summary, tokenEstimate = 10)),
            ProviderEvent.Done(StopReason.ToolCall)
          ))
        case other =>
          Stream.emits(List(ProviderEvent.Error(s"unexpected tool: $other")))
      }
    }
  }

  "MemoryContextCompressor" should {
    "extract facts into memory space and persist a summary when the space is configured" in {
      TestSigil.reset()
      val convId = Conversation.id(s"mcc-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()
      TestSigil.setCompressionSpace(Some(MemoryTestSpace))
      TestSigil.setProvider(Task.pure(new StubProvider(
        facts = List("User `alice` prefers dark mode.", "Deploy target is staging.us-east-1."),
        summary = "Alice discussed dark-mode preference and deployment target."
      )))
      val compressor = MemoryContextCompressor()
      val frames = (0 until 5).toVector.map(i => textFrame(s"utterance $i", s"ev-$i"))
      for {
        result <- compressor.compress(TestSigil, modelId, chain = List(TestUser, TestAgent), frames, convId)
        memories <- TestSigil.withDB(_.memories.transaction(_.list))
          .map(_.filter(_.spaceId == MemoryTestSpace))
        summariesForConv <- TestSigil.summariesFor(convId)
      } yield {
        result should not be None
        result.get.text should include("dark-mode")
        memories.map(_.fact) should contain("User `alice` prefers dark mode.")
        memories.map(_.fact) should contain("Deploy target is staging.us-east-1.")
        memories.filter(_.source == MemorySource.Compression) should have size 2
        summariesForConv.map(_.text) should contain(result.get.text)
      }
    }

    "make extracted facts semantically retrievable via Sigil.searchMemories" in {
      TestSigil.reset()
      // Vector-wire TestSigil so persistMemory auto-indexes + searchMemories
      // goes through the vector branch (not the Lucene fallback).
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      val convId = Conversation.id(s"mcc-retrieve-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()
      TestSigil.setCompressionSpace(Some(MemoryTestSpace))
      TestSigil.setProvider(Task.pure(new StubProvider(
        facts = List(
          "User `alice` prefers dark mode.",
          "The deployment target is staging.us-east-1.",
          "Maximum response time constraint is 500 milliseconds."
        ),
        summary = "Alice shared preferences and deployment constraints."
      )))
      val compressor = MemoryContextCompressor()
      val frames = Vector(textFrame("preferences conversation", "ev-0"))
      for {
        _ <- compressor.compress(TestSigil, modelId, chain = List(TestUser, TestAgent), frames, convId)
        hitsForDarkMode <- TestSigil.searchMemories("user alice dark mode preference", Set(MemoryTestSpace), limit = 5)
        hitsForDeploy <- TestSigil.searchMemories("deployment staging target", Set(MemoryTestSpace), limit = 5)
      } yield {
        hitsForDarkMode.map(_.fact) should contain("User `alice` prefers dark mode.")
        hitsForDeploy.map(_.fact) should contain("The deployment target is staging.us-east-1.")
      }
    }

    "skip extraction and still produce a summary when compressionMemorySpace is None" in {
      TestSigil.reset()
      val convId = Conversation.id(s"mcc-none-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()
      TestSigil.setCompressionSpace(None)
      TestSigil.setProvider(Task.pure(new StubProvider(
        facts = List("should NOT be extracted"),
        summary = "A short summary."
      )))
      val compressor = MemoryContextCompressor()
      val frames = Vector(textFrame("hello", "ev-0"))
      for {
        result <- compressor.compress(TestSigil, modelId, chain = List(TestUser, TestAgent), frames, convId)
        facts <- TestSigil.withDB(_.memories.transaction(_.list))
          .map(_.filter(_.spaceId == MemoryTestSpace).map(_.fact))
      } yield {
        result.map(_.text) shouldBe Some("A short summary.")
        facts should not contain "should NOT be extracted"
      }
    }
  }
}
