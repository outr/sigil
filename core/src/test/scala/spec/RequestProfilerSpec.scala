package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.GlobalSpace
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, Conversation, ConversationView, MemorySource, TopicEntry, Topic, TurnInput}
import sigil.db.Model
import sigil.diagnostics.{ProfileSection, RequestProfiler}
import sigil.event.Event
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ResolvedReferences}
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.Tool

/**
 * Sanity coverage for [[RequestProfiler]] — the profile's per-section
 * sums match the full request total, and the per-frame breakdown
 * accounts for every frame.
 */
class RequestProfilerSpec extends AnyWordSpec with Matchers {

  private def buildRequest(frames: Vector[ContextFrame],
                           tools: Vector[Tool] = Vector.empty): ConversationRequest = {
    val convId = Conversation.id(s"profiler-${rapid.Unique()}")
    val view = ConversationView(conversationId = convId, frames = frames, _id = ConversationView.idFor(convId))
    ConversationRequest(
      conversationId = convId,
      modelId = Id[Model]("test/profiler"),
      instructions = Instructions(),
      turnInput = TurnInput(view),
      currentMode = ConversationMode,
      currentTopic = TopicEntry(Id[Topic]("topic"), "Test", "Synthetic"),
      previousTopics = Nil,
      generationSettings = GenerationSettings(),
      tools = tools,
      chain = List(TestUser, TestAgent)
    )
  }

  "RequestProfiler.profileWith" should {

    "produce per-section sums that equal `total`" in {
      val frames: Vector[ContextFrame] = Vector(
        ContextFrame.Text("user message", TestUser, Id[Event]()),
        ContextFrame.Text("agent reply", TestAgent, Id[Event]())
      )
      val request = buildRequest(frames)
      val resolved = ResolvedReferences(Vector.empty, Vector.empty, Vector.empty)
      val profile = RequestProfiler.profileWith(request, resolved, HeuristicTokenizer, _.description)
      profile.sections.values.sum shouldBe profile.total
    }

    "produce one FrameProfile per frame, summing to the Frames section" in {
      val frames: Vector[ContextFrame] = Vector(
        ContextFrame.Text("hello", TestUser, Id[Event]()),
        ContextFrame.Text("there", TestAgent, Id[Event]()),
        ContextFrame.Text("hi", TestUser, Id[Event]())
      )
      val request = buildRequest(frames)
      val resolved = ResolvedReferences(Vector.empty, Vector.empty, Vector.empty)
      val profile = RequestProfiler.profileWith(request, resolved, HeuristicTokenizer, _.description)
      profile.frames.size shouldBe 3
      profile.frames.iterator.map(_.tokens).sum shouldBe profile.sections(ProfileSection.Frames)
    }

    "include CriticalMemories section when resolved.criticalMemories is non-empty" in {
      val mem = ContextMemory(
        fact = "Be concise.",
        label = "Terse",
        summary = "Be concise.",
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace,
        key = Some("directive.terse")
      )
      val request = buildRequest(Vector.empty)
      val resolved = ResolvedReferences(Vector(mem), Vector.empty, Vector.empty)
      val profile = RequestProfiler.profileWith(request, resolved, HeuristicTokenizer, _.description)
      profile.sections.contains(ProfileSection.CriticalMemories) shouldBe true
      profile.sections(ProfileSection.CriticalMemories) should be > 0
    }

    "respect `summary || fact` policy in CriticalMemories cost" in {
      val mem = ContextMemory(
        fact = "x" * 400,           // 100 heuristic tokens
        label = "Short directive",
        summary = "y" * 40,         // 10 heuristic tokens
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace,
        key = Some("directive.short")
      )
      val request = buildRequest(Vector.empty)
      val resolved = ResolvedReferences(Vector(mem), Vector.empty, Vector.empty)
      val profile = RequestProfiler.profileWith(request, resolved, HeuristicTokenizer, _.description)
      // Section cost should reflect summary, not fact.
      profile.sections(ProfileSection.CriticalMemories) should be < 100
    }
  }
}
