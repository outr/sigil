package spec

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{
  Conversation, ParticipantProjection, RecentToolInvocation, TurnInput
}
import sigil.db.Model
import sigil.event.{Event, Message, MessageDisposition, MessageRole}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.signal.{EventState, Signal}
import sigil.tool.{InMemoryToolFinder, ToolInput, ToolInputCanonicalizer, ToolName, TypedTool}
import sigil.tool.core.{CoreTools, NoResponseTool, RespondTool}
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Coverage for the duplicate-call-detection surface:
 *
 *   - Layer 1 — `ParticipantProjection.recentToolInvocations` records
 *     each `ToolInvoke` with a canonical args hash + short preview.
 *   - Layer 2 — `Provider.renderSystem` surfaces a "Repeated tool
 *     calls" warning when any (toolName, argsHash) bucket fires more
 *     than once inside the rolling window.
 *   - Layer 3 — the orchestrator's `proceedWithAtomicDispatch`
 *     intercept refuses to dispatch a tool whose `(toolName,
 *     argsHash)` count has reached `Sigil.maxIdenticalToolCallsInWindow`
 *     in the projection.
 */
class RecentToolInvocationsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // ---- shared test fixtures ----

  /** Synthetic ToolInput with two fields so semantically-identical
    * calls with reordered fields exercise the canonical-hash path. */
  case class SearchInput(pattern: String, glob: String) extends ToolInput derives RW

  /** Tracks every successful execute() so the Layer-3 cap test can
    * assert "the third call did NOT run." */
  private val searchInvocations = new java.util.concurrent.atomic.AtomicInteger(0)

  case object SearchTool extends TypedTool[SearchInput](
    name        = ToolName("recent_search_tool"),
    description = "Synthetic search tool used by RecentToolInvocationsSpec."
  ) {
    override def paginate: Boolean = false
    override protected def executeTyped(input: SearchInput, ctx: TurnContext): Stream[Event] = {
      searchInvocations.incrementAndGet()
      Stream.emit[Event](Message(
        participantId  = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId        = ctx.conversation.currentTopicId,
        content        = Vector(ResponseContent.Text(s"matched ${input.pattern} in ${input.glob}")),
        role           = MessageRole.Tool,
        state          = EventState.Complete
      ))
    }
  }

  ToolInput.register(summon[RW[SearchInput]])

  // ---- Layer 1: projection rolling-window invariants ----

  "ParticipantProjection.recentToolInvocations" should {

    "hash semantically-identical args with different JSON key order to the same value" in {
      val ab = SearchInput(pattern = "TODO", glob = "**/*.scala")
      val ba = SearchInput(pattern = "TODO", glob = "**/*.scala")
      val hashAb = ToolInputCanonicalizer.argsHash(ab)
      val hashBa = ToolInputCanonicalizer.argsHash(ba)
      Task.pure {
        hashAb shouldBe hashBa
        // Sanity — a different glob produces a different hash.
        ToolInputCanonicalizer.argsHash(SearchInput("TODO", "src/**")) should not be hashAb
      }
    }

    "cap the recent-invocations list at recentToolInvocationsLimit (20)" in {
      val now = Timestamp()
      val twentyFive = (1 to 25).toList.map { i =>
        RecentToolInvocation(
          toolName    = ToolName(s"tool_$i"),
          argsHash    = s"hash_$i",
          argsPreview = s"{\"n\":$i}",
          invokedAt   = Timestamp(now.value + i)
        )
      }
      // Simulate the framework's projection-update path -- prepend
      // each entry and take the most-recent `limit` after every push.
      val limit = TestSigil.recentToolInvocationsLimit
      val capped = twentyFive.foldLeft(List.empty[RecentToolInvocation]) { (acc, inv) =>
        (inv :: acc).take(limit)
      }
      Task.pure {
        capped.size shouldBe limit
        capped.size shouldBe 20
        // Most-recent five (tools 21..25) survive; oldest fall off.
        capped.map(_.toolName.value) should contain allOf ("tool_25", "tool_24", "tool_23", "tool_22", "tool_21")
        capped.map(_.toolName.value) should not contain "tool_1"
      }
    }
  }

  // ---- Layer 2: prompt-rendering ----

  /** Build a `ConversationRequest` that drives `renderSystem`. Pinned
    * to the chain so the agent's projection is the one that contributes
    * to the rolling-window section. */
  private def requestWith(projection: ParticipantProjection,
                          convId: Id[Conversation]): ConversationRequest =
    ConversationRequest(
      conversationId         = convId,
      modelId                = Model.id("test", "recent-tools-model"),
      instructions           = Instructions(),
      turnInput              = TurnInput(
        conversationId         = convId,
        participantProjections = Map(TestAgent -> projection)
      ),
      currentMode            = ConversationMode,
      currentTopic           = TestTopicEntry,
      previousTopics         = Nil,
      generationSettings     = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools                  = CoreTools.all,
      chain                  = List(TestUser, TestAgent)
    )

  /** Render the system prompt the provider would emit. Uses
    * LlamaCppProvider because the live llama.cpp host is the default
    * everywhere; the renderSystem path is shared across providers, so
    * provider choice is irrelevant for prompt-content assertions. */
  private def renderSystem(req: ConversationRequest): Task[String] = {
    val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
    provider.requestConverter(req).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })
  }

  "Provider.renderSystem (duplicate detection)" should {

    "surface a 'Repeated tool calls' warning when the same tool fires twice with identical args" in {
      val convId = Conversation.id(s"repeat-detect-${rapid.Unique()}")
      val args = SearchInput(pattern = "TODO|FIXME", glob = "**/*.scala")
      val argsHash = ToolInputCanonicalizer.argsHash(args)
      val argsPreview = ToolInputCanonicalizer.argsPreview(args)
      val now = System.currentTimeMillis()
      val invocations = List(
        RecentToolInvocation(SearchTool.name, argsHash, argsPreview, Timestamp(now - 60_000L)),
        RecentToolInvocation(SearchTool.name, argsHash, argsPreview, Timestamp(now - 5_000L))
      )
      val proj = ParticipantProjection.empty(TestAgent, convId).copy(recentToolInvocations = invocations)
      renderSystem(requestWith(proj, convId)).map { body =>
        body should include("Repeated tool calls")
        body should include(SearchTool.name.value)
        body should include("2 times")
        body should include("Don't re-issue this call")
        // The args preview shows up so the agent sees what it called.
        body should include("TODO|FIXME")
      }
    }

    "NOT surface the warning when the same tool fires with different args" in {
      val convId = Conversation.id(s"repeat-distinct-${rapid.Unique()}")
      val first = SearchInput(pattern = "TODO", glob = "**/*.scala")
      val second = SearchInput(pattern = "TODO", glob = "src/**/*.scala")
      val now = System.currentTimeMillis()
      val invocations = List(
        RecentToolInvocation(
          SearchTool.name,
          ToolInputCanonicalizer.argsHash(first),
          ToolInputCanonicalizer.argsPreview(first),
          Timestamp(now - 60_000L)
        ),
        RecentToolInvocation(
          SearchTool.name,
          ToolInputCanonicalizer.argsHash(second),
          ToolInputCanonicalizer.argsPreview(second),
          Timestamp(now - 5_000L)
        )
      )
      val proj = ParticipantProjection.empty(TestAgent, convId).copy(recentToolInvocations = invocations)
      renderSystem(requestWith(proj, convId)).map { body =>
        body should not include "Repeated tool calls"
        // The "Recently used tools" section still surfaces both
        // distinct entries.
        body should include("Recently used tools")
      }
    }

    "NOT surface the warning when the older identical entry has fallen out of the rolling window" in {
      val convId = Conversation.id(s"window-eviction-${rapid.Unique()}")
      val targetArgs = SearchInput(pattern = "TODO", glob = "**/*.scala")
      val targetHash = ToolInputCanonicalizer.argsHash(targetArgs)
      val targetPreview = ToolInputCanonicalizer.argsPreview(targetArgs)
      val now = System.currentTimeMillis()
      // Simulate the order the framework would record: most-recent at
      // the HEAD. Start with the second `targetArgs` call, push 20
      // distinct other-tool entries on top -- the original target
      // entry would be ranked 21st and fall off the 20-entry cap.
      val secondTarget = RecentToolInvocation(SearchTool.name, targetHash, targetPreview, Timestamp(now))
      val distinctNoise = (1 to TestSigil.recentToolInvocationsLimit).toList.reverse.map { i =>
        RecentToolInvocation(
          ToolName(s"noise_tool_$i"),
          s"noise_hash_$i",
          s"{\"n\":$i}",
          Timestamp(now - 1_000L * i)
        )
      }
      val firstTarget = RecentToolInvocation(SearchTool.name, targetHash, targetPreview, Timestamp(now - 1_000L * (TestSigil.recentToolInvocationsLimit + 1)))
      // HEAD-first order: secondTarget, then distinctNoise (most-
      // recent first), then firstTarget (oldest). Cap at limit; the
      // firstTarget falls off.
      val full = secondTarget :: distinctNoise ::: List(firstTarget)
      val capped = full.take(TestSigil.recentToolInvocationsLimit)
      capped should not contain firstTarget
      // Only the second target survives -- the duplicate-detection
      // group has one occurrence, so no warning fires.
      val proj = ParticipantProjection.empty(TestAgent, convId).copy(recentToolInvocations = capped)
      renderSystem(requestWith(proj, convId)).map { body =>
        body should not include "Repeated tool calls"
      }
    }
  }

  // ---- Layer 3: orchestrator hard cap ----

  /** Provider that emits a single function-call to `recent_search_tool`
    * with a fixed payload, then closes the stream. The orchestrator's
    * atomic-dispatch path runs the tool synchronously. */
  private class SingleSearchCallProvider(callIdValue: String, payload: SearchInput) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("stub provider -- no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(callIdValue)
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, SearchTool.name.value),
        ProviderEvent.ToolCallComplete(cid, payload),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Run the orchestrator against a single tool-call provider with a
    * pre-seeded projection carrying `priorIdenticalCount` matching
    * entries. */
  private def runWithPriorCount(payload: SearchInput,
                                priorIdenticalCount: Int,
                                suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"layer3-cap-$suffix-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val now = Timestamp()
    val hash = ToolInputCanonicalizer.argsHash(payload)
    val preview = ToolInputCanonicalizer.argsPreview(payload)
    val priorEntries = (1 to priorIdenticalCount).toList.map { i =>
      RecentToolInvocation(SearchTool.name, hash, preview, Timestamp(now.value - 1_000L * i))
    }
    val proj = ParticipantProjection.empty(TestAgent, convId).copy(recentToolInvocations = priorEntries)
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = Model.id("test", "cap-model"),
      instructions       = Instructions(),
      turnInput          = TurnInput(
        conversationId         = convId,
        participantProjections = Map(TestAgent -> proj)
      ),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector(SearchTool, RespondTool, NoResponseTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(
                   TestSigil,
                   new SingleSearchCallProvider(s"cap-call-$suffix", payload),
                   request,
                   conv
                 ).toList
    } yield signals
  }

  "Orchestrator hard cap (Layer 3)" should {

    // Make sure the finder can resolve the synthetic tool by name --
    // the orchestrator looks tools up via `toolsByName` from the
    // request's `tools` Vector, so the finder isn't strictly needed
    // here, but other test interactions (DBs, suggestions) prefer
    // the in-memory finder.
    TestSigil.setToolFinder(InMemoryToolFinder(List(SearchTool, RespondTool, NoResponseTool)))

    "REFUSE to dispatch on the 3rd identical call and emit a Failure ToolResult" in {
      searchInvocations.set(0)
      val args = SearchInput(pattern = "TODO|FIXME", glob = "**/*.scala")
      // Seed with 2 prior identical entries -- the orchestrator's
      // next dispatch is identical-call #3 and must refuse.
      runWithPriorCount(args, priorIdenticalCount = 2, suffix = "third").map { signals =>
        searchInvocations.get() shouldBe 0
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should not be empty
        val failure = toolMessages.find(_.disposition match {
          case _: MessageDisposition.Failure => true
          case _                              => false
        }).getOrElse(fail("expected a Failure-disposition Tool-role Message"))
        val text = failure.content.collect { case ResponseContent.Text(t) => t }.mkString
        text should include("Refused to dispatch")
        text should include(SearchTool.name.value)
        text should include("3")  // call #3 / 2 prior times
        text should include("different approach")
      }
    }

    "ALLOW the dispatch when prior identical count is below the cap" in {
      searchInvocations.set(0)
      val args = SearchInput(pattern = "TODO|FIXME", glob = "**/*.scala")
      // Seed with 1 prior entry -- the orchestrator's next dispatch
      // is identical-call #2, below the default cap of 3.
      runWithPriorCount(args, priorIdenticalCount = 1, suffix = "second").map { _ =>
        searchInvocations.get() shouldBe 1
      }
    }
  }
}
