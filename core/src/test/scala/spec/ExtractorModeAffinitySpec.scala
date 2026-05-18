package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.compression.extract.{HighSignalFilter, StandardMemoryExtractor}
import sigil.conversation.{Conversation, MemorySource, MemoryStatus, MemoryType}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{
  CallId, GenerationSettings, Provider, ProviderCall, ProviderEvent,
  ProviderType, StopReason
}
import sigil.tool.consult.{ExtractMemoriesInput, ExtractedMemory}
import sigil.{GlobalSpace, SpaceId}
import spice.http.HttpRequest

/**
 * Regression for sigil bug #195 (extractor half) — the per-turn
 * extractor recognises `mode:NAME` tags on returned
 * [[ExtractedMemory]] entries, resolves them against the framework's
 * registered modes, and stamps `ContextMemory.modeAffinity`
 * accordingly. The `mode:` prefix is stripped from the persisted
 * `keywords` so the categorisation token doesn't leak into search
 * indexes.
 *
 * Side-channel approach (rather than a new schema field on
 * `ExtractedMemory`): smaller test models (qwen3.5-9b) consistently
 * dropped `key` emission when extra optional fields appeared in the
 * tool schema; going through the existing `tags` array preserves the
 * extractor's key-emission behaviour while still letting the model
 * scope a directive to a mode.
 */
class ExtractorModeAffinitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val extractorModel: Id[Model] = Model.id("test", "extractor-probe-model")

  /**
   * Provider that emits a scripted `extract_memories` tool_call
   * carrying the supplied [[ExtractedMemory]] list.
   */
  final private class ScriptedExtractorProvider(memories: List[ExtractedMemory]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val payload = ExtractMemoriesInput(memories)
      Stream.emits(List(
        ProviderEvent.ToolCallStart(CallId("extract-1"), "extract_memories"),
        ProviderEvent.ToolCallComplete(CallId("extract-1"), payload),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def runExtractor(memories: List[ExtractedMemory],
                           convId: Id[Conversation]): Task[List[sigil.conversation.ContextMemory]] = {
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    TestSigil.setProvider(Task.pure(new ScriptedExtractorProvider(memories)))
    val extractor = StandardMemoryExtractor(
      // Always-on filter — the test drives extraction directly with
      // crafted ExtractedMemory payloads; the high-signal gate is
      // tested separately in DefaultHighSignalFilterSpec.
      filter = new HighSignalFilter {
        override def isHighSignal(userMessage: String): Boolean = true
      },
      spaceIdFor = (_: Id[Conversation]) => Task.pure(Some(GlobalSpace: SpaceId))
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      out <- extractor.extract(
        sigil = TestSigil,
        conversationId = convId,
        modelId = extractorModel,
        chain = List(TestUser, TestAgent),
        userMessage = "Always create failing unit tests when coding before fixing a bug.",
        agentResponse = "Understood — I'll do that going forward."
      )
    } yield out
  }

  "StandardMemoryExtractor mode:NAME tag side-channel (sigil bug #195)" should {

    "stamp modeAffinity from a single mode:NAME tag and strip the prefix from keywords" in {
      val convId = Conversation.id(s"extractor-mode-single-${rapid.Unique()}")
      val mems = List(ExtractedMemory(
        content = "Always create failing tests before fixing a bug.",
        label = "tdd",
        key = Some("rules.tdd"),
        tags = List("rule", "mode:coding")
      ))
      runExtractor(mems, convId).map { persisted =>
        persisted should have size 1
        val m = persisted.head
        m.modeAffinity shouldBe Set(Id[sigil.provider.Mode]("coding"))
        m.keywords shouldBe Vector("rule")
        m.key shouldBe Some("rules.tdd")
      }
    }

    "stamp multiple modes when multiple mode:NAME tags are present" in {
      val convId = Conversation.id(s"extractor-mode-multi-${rapid.Unique()}")
      val mems = List(ExtractedMemory(
        content = "When coding or doing research, prefer primary sources.",
        label = "primary-sources",
        key = Some("rules.primary_sources"),
        tags = List("mode:coding", "rule", "mode:web-research")
      ))
      runExtractor(mems, convId).map { persisted =>
        val m = persisted.head
        m.modeAffinity shouldBe Set(
          Id[sigil.provider.Mode]("coding"),
          Id[sigil.provider.Mode]("web-research")
        )
        m.keywords shouldBe Vector("rule")
      }
    }

    "leave modeAffinity empty and keywords unchanged when no mode: tag is present" in {
      val convId = Conversation.id(s"extractor-mode-none-${rapid.Unique()}")
      val mems = List(ExtractedMemory(
        content = "User prefers metric units.",
        label = "user-units",
        key = Some("user.units"),
        tags = List("preference", "units")
      ))
      runExtractor(mems, convId).map { persisted =>
        val m = persisted.head
        m.modeAffinity shouldBe empty
        m.keywords shouldBe Vector("preference", "units")
      }
    }

    "drop unknown mode tags (typo / orphan) without losing the memory" in {
      val convId = Conversation.id(s"extractor-mode-typo-${rapid.Unique()}")
      val mems = List(ExtractedMemory(
        content = "Always cite sources.",
        label = "cite",
        key = Some("rules.cite"),
        tags = List("rule", "mode:not-a-real-mode")
      ))
      runExtractor(mems, convId).map { persisted =>
        val m = persisted.head
        // Unknown mode → dropped silently (WARN logged). The memory
        // persists as universal so a typo doesn't lose it entirely.
        m.modeAffinity shouldBe empty
        m.keywords shouldBe Vector("rule")
        m.key shouldBe Some("rules.cite")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
