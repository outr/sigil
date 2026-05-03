package bench.contextprofile

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{
  ContextFrame, ContextMemory, ContextSummary, Conversation,
  ConversationView, ParticipantProjection, TopicEntry, Topic, TurnInput
}
import sigil.GlobalSpace
import sigil.conversation.MemorySource
import sigil.diagnostics.{RequestProfile, RequestProfileReport, RequestProfiler}
import sigil.event.Event
import sigil.information.{Information, InformationSummary}
import sigil.participant.{AgentParticipantId, ParticipantId}
import sigil.provider.{
  ConversationMode, GenerationSettings, Instructions, Mode, ResolvedReferences
}
import sigil.role.Role
import sigil.tool.{Tool, ToolInput, ToolName, TypedTool}
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

  /** Default tokenizer for benches: jtokkit cl100k_base — accurate for
    * OpenAI ChatGPT-class models and a fair approximation elsewhere. */
  val tokenizer: Tokenizer = JtokkitTokenizer.OpenAIChatGpt

  // ---- well-known synthetic identities ----

  case object UserId extends ParticipantId      { override val value: String = "bench-user" }
  case object AgentId extends AgentParticipantId { override val value: String = "bench-agent" }

  val ConvId: Id[Conversation] = Conversation.id("bench-conv")
  val TopicId: Id[Topic]       = Id("bench-topic")

  val DefaultTopic: TopicEntry = TopicEntry(TopicId, "Bench Topic", "Synthetic conversation for context profiling.")

  /** A fully-typed dummy ToolInput used by synthetic tools below. */
  case class DummyInput(value: String = "") extends ToolInput derives RW

  /** Synthetic Tool with caller-supplied name + description. Static
    * description (no `descriptionFor` override), so the profiler
    * doesn't need a Sigil reference for these. */
  class FakeTool(toolName: String, toolDescription: String) extends TypedTool[DummyInput](
    name = ToolName(toolName),
    description = toolDescription
  ) {
    override protected def executeTyped(input: DummyInput, context: sigil.TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
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

  def toolResultFrame(callId: Id[Event], content: String): ContextFrame.ToolResult =
    ContextFrame.ToolResult(
      callId = callId,
      content = content,
      sourceEventId = Id[Event]()
    )

  // ---- memory + summary fixtures ----

  def critical(key: String, fact: String): ContextMemory =
    ContextMemory(
      fact = fact,
      source = MemorySource.Critical,
      spaceId = GlobalSpace,
      key = key,
      label = key,
      summary = fact.take(80)
    )

  def memory(key: String, fact: String): ContextMemory =
    ContextMemory(
      fact = fact,
      source = MemorySource.Compression,
      spaceId = GlobalSpace,
      key = key,
      label = key,
      summary = fact.take(80)
    )

  def summary(text: String): ContextSummary =
    ContextSummary(
      text = text,
      conversationId = ConvId,
      tokenEstimate = text.length / 4
    )

  // ---- view + request builders ----

  def viewWith(frames: Vector[ContextFrame],
               projections: Map[ParticipantId, ParticipantProjection] = Map.empty): ConversationView =
    ConversationView(
      conversationId = ConvId,
      frames = frames,
      participantProjections = projections,
      _id = ConversationView.idFor(ConvId)
    )

  def buildRequest(view: ConversationView,
                   tools: Vector[Tool] = Vector.empty,
                   mode: Mode = ConversationMode,
                   roles: List[Role] = Nil,
                   information: Vector[InformationSummary] = Vector.empty,
                   extra: Map[sigil.conversation.ContextKey, String] = Map.empty,
                   chain: List[ParticipantId] = List(UserId, AgentId)
                  ): sigil.provider.ConversationRequest = {
    val turn = TurnInput(
      conversationView = view,
      criticalMemories = Vector.empty,
      memories = Vector.empty,
      summaries = Vector.empty,
      information = information,
      extraContext = extra
    )
    sigil.provider.ConversationRequest(
      conversationId = ConvId,
      modelId = sigil.db.Model.id("openai/gpt-4o"),
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

  /** Resolve memory/summary id buckets directly from the records (no
    * DB lookup). For benches that synthesize records, this is the
    * right abstraction. */
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
