package bench.contextprofile

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{
  ContextFrame, ContextMemory, ContextSummary, Conversation,
  ParticipantProjection, ToolCallState, TopicEntry, Topic, TurnInput
}
import lightdb.time.Timestamp
import sigil.GlobalSpace
import sigil.conversation.MemorySource
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.diagnostics.{RequestProfile, RequestProfileReport, RequestProfiler}
import sigil.event.Event
import sigil.information.InformationSummary
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.provider.{ConversationMode, GenerationSettings, Instructions, Mode, ResolvedReferences}
import sigil.role.Role
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}
import sigil.tokenize.{JtokkitTokenizer, Tokenizer}

/**
 * Shared utilities for the Phase 0 context-profile benches. Builds
 * synthetic [[sigil.provider.ConversationRequest]]s representative of
 * specific conversation shapes, runs them through
 * [[RequestProfiler.profileWith]], and writes a markdown report via
 * [[RequestProfileReport]] under `benchmark/profiles/<name>.md`.
 *
 * Bypasses the live Sigil to keep benches lean — no DB, no provider,
 * no network. The profiler doesn't need a live framework; only the
 * static parts of `ConversationRequest` + a `Tokenizer`.
 */
object ProfilerHarness {

  /**
   * Default tokenizer for benches: jtokkit cl100k_base — accurate for
   * OpenAI ChatGPT-class models and a fair approximation elsewhere.
   */
  val tokenizer: Tokenizer = JtokkitTokenizer.OpenAIChatGpt

  // ---- well-known synthetic identities ----

  case object UserId extends ParticipantId { override val value: String = "bench-user" }
  case object AgentId extends AgentParticipantId { override val value: String = "bench-agent" }

  val ConvId: Id[Conversation] = Conversation.id("bench-conv")
  val TopicId: Id[Topic] = Id("bench-topic")

  val DefaultTopic: TopicEntry = TopicEntry(TopicId, "Bench Topic", "Synthetic conversation for context profiling.")

  /**
   * Sigil #277 — synthetic Model record used by harness fixtures.
   * Carries OpenAI gpt-4o defaults (context length / pricing) so any
   * profiler heuristic that reads model facts gets representative
   * numbers without booting a Sigil instance.
   */
  val BenchModel: Model = {
    val now = Timestamp()
    Model(
      canonicalSlug = "openai/gpt-4o",
      huggingFaceId = "",
      name = "gpt-4o",
      description = "Synthetic gpt-4o stand-in for context-profile benches.",
      contextLength = 128000L,
      architecture = ModelArchitecture(
        modality = "text+image->text",
        inputModalities = List("text", "image"),
        outputModalities = List("text"),
        tokenizer = "GPT",
        instructType = None
      ),
      pricing = ModelPricing(prompt = BigDecimal("0.0000025"), completion = BigDecimal("0.00001"), webSearch = None, inputCacheRead = None),
      topProvider = ModelTopProvider(contextLength = Some(128000L), maxCompletionTokens = Some(16384L), isModerated = false),
      perRequestLimits = None,
      supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = ""),
      created = now,
      _id = Model.id("openai", "gpt-4o")
    )
  }

  /**
   * A fully-typed dummy ToolInput used by synthetic tools below.
   */
  case class DummyInput(value: String = "") extends ToolInput derives RW

  /**
   * Synthetic Tool with caller-supplied name + description. Static
   * description (no `descriptionFor` override), so the profiler
   * doesn't need a Sigil reference for these.
   */
  class FakeTool(toolName: String, toolDescription: String) extends Tool {
    type Input = DummyInput
    type Output = TextToolOutput

    val inputRW: RW[DummyInput] = summon[RW[DummyInput]]
    val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

    val name: ToolName = ToolName(toolName)
    val description: String = toolDescription

    override def executeResult(input: DummyInput, context: sigil.tool.ToolContext): rapid.Task[ToolResult[TextToolOutput]] =
      rapid.Task.pure(ToolResult.Success(TextToolOutput("")))
  }

  // ---- frame builders ----

  def textFrame(content: String, participantId: ParticipantId = UserId): ContextFrame.Text =
    ContextFrame.Text(content = content, participantId = participantId, sourceEventId = Id[Event]())

  def toolCallFrame(toolName: String, args: String, participantId: ParticipantId = AgentId): ContextFrame.ToolCall = {
    val callId = Id[Event]()
    ContextFrame.ToolCall(
      toolName = ToolName(toolName),
      argsJson = args,
      callId = callId,
      participantId = participantId,
      sourceEventId = callId
    )
  }

  /**
   * Sigil #261 / #265 — `ContextFrame.ToolCall` carries the result
   * inline via `state = ToolCallState.Complete(content)`. Helper
   * builds a fresh ToolCall in the completed state for profiling
   * fixtures (replaces the pre-#261 separate `ContextFrame.ToolResult`
   * frame the harness emitted).
   */
  def completedToolCallFrame(call: ContextFrame.ToolCall, content: String): ContextFrame.ToolCall =
    call.copy(state = ToolCallState.Complete(content))

  // ---- memory + summary fixtures ----

  def critical(key: String, fact: String): ContextMemory =
    ContextMemory(
      fact = fact,
      source = MemorySource.Explicit,
      pinned = true,
      spaceId = GlobalSpace,
      key = Some(key),
      label = key,
      summary = fact.take(80)
    )

  def memory(key: String, fact: String): ContextMemory =
    ContextMemory(
      fact = fact,
      source = MemorySource.Compression,
      spaceId = GlobalSpace,
      key = Some(key),
      label = key,
      summary = fact.take(80)
    )

  def summary(text: String): ContextSummary =
    ContextSummary(
      text = text,
      conversationId = ConvId,
      tokenEstimate = text.length / 4
    )

  // ---- request builders ----

  def buildRequest(frames: Vector[ContextFrame],
                   projections: Map[ParticipantId, ParticipantProjection] = Map.empty,
                   tools: Vector[Tool] = Vector.empty,
                   mode: Mode = ConversationMode,
                   roles: List[Role] = Nil,
                   information: Vector[InformationSummary] = Vector.empty,
                   extra: Map[sigil.conversation.ContextKey, String] = Map.empty,
                   chain: List[ParticipantId] = List(UserId, AgentId)): sigil.provider.ConversationRequest = {
    val turn = TurnInput(
      conversationId = ConvId,
      frames = frames,
      participantProjections = projections,
      criticalMemories = Vector.empty,
      memories = Vector.empty,
      summaries = Vector.empty,
      information = information,
      extraContext = extra
    )
    sigil.provider.ConversationRequest(
      conversationId = ConvId,
      model = BenchModel,
      instructions = Instructions(),
      turnInput = turn,
      currentMode = mode,
      currentTopic = DefaultTopic,
      previousTopics = Nil,
      generationSettings = GenerationSettings(),
      tools = tools,
      chain = chain,
      roles = roles
    )
  }

  /**
   * Resolve memory/summary id buckets directly from the records (no
   * DB lookup). For benches that synthesize records, this is the
   * right abstraction.
   */
  def resolved(critical: Vector[ContextMemory] = Vector.empty,
               retrieved: Vector[ContextMemory] = Vector.empty,
               summaries: Vector[ContextSummary] = Vector.empty): ResolvedReferences =
    ResolvedReferences(
      criticalMemories = critical,
      memories = retrieved,
      summaries = summaries
    )

  // ---- profile + report ----

  def profile(request: sigil.provider.ConversationRequest,
              refs: ResolvedReferences = resolved()): RequestProfile =
    RequestProfiler.profileWith(request, refs, tokenizer, _.description)

  def writeReport(name: String, title: String, profiles: Seq[RequestProfile]): Unit = {
    // sbt's `benchmark/runMain` forks with cwd=benchmark/ — anchor to that.
    val path = java.nio.file.Paths.get("profiles", s"$name.md")
    RequestProfileReport.writeTo(path, title, profiles)
    println(s"[$name] wrote ${profiles.size} profiles → ${path.toAbsolutePath}")
  }
}
