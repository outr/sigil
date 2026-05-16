package bench

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.Model
import sigil.provider.{
  CallId, ConversationMode, GenerationSettings, OneShotRequest, Provider, ProviderEvent
}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.tool.model.SelectOption
import sigil.tool.{ToolInput, ToolName, TypedTool}

/**
 * Empirical probe — sigil bug #157 unification design decision.
 *
 * The bug proposes collapsing four `respond_*` tools into one
 * `respond` with a tagged-union content slot (Option B). The
 * concern: smaller models may struggle with the nested-union
 * schema vs the flat 4-tool baseline where the tool name is the
 * discriminator.
 *
 * This probe runs four explicit prompts (one per content kind)
 * against the same local llama, twice — once with the 4-tool
 * baseline roster, once with the unified Option B roster — and
 * reports whether the model picks the right kind + emits a
 * well-formed payload.
 *
 * Run:
 *   sbt "benchmark/runMain bench.RespondUnificationProbe"
 *
 * Honors `SIGIL_LLAMACPP_HOST` (default `https://llama.voidcraft.ai`).
 */
object RespondUnificationProbe {

  /** Minimal Sigil for the probe — no agent loop, no signals
    * surface, just enough to satisfy `Provider.apply`'s
    * resolver hooks. */
  case class ProbeSigil() extends Sigil {
    override type DB = sigil.db.DefaultSigilDB
    override protected def buildDB(directory: Option[java.nio.file.Path],
                                   storeManager: lightdb.store.CollectionManager,
                                   appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
      new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)
    override protected def signalRegistrations: List[RW[? <: sigil.signal.Signal]] = Nil
    override protected def participantIds: List[RW[? <: sigil.participant.ParticipantId]] = Nil
    override protected def spaceIds: List[RW[? <: sigil.SpaceId]] = Nil
    override protected def participants: List[RW[? <: sigil.participant.Participant]] = Nil
    override val findTools: sigil.tool.ToolFinder = sigil.tool.InMemoryToolFinder(Nil)
    override def getInformation(id: Id[sigil.information.Information]): Task[Option[sigil.information.Information]] = Task.pure(None)
    override def putInformation(information: sigil.information.Information): Task[Unit] = Task.unit
    override def compressionMemorySpace(conversationId: Id[sigil.conversation.Conversation]): Task[Option[sigil.SpaceId]] = Task.pure(None)
    override def embeddingProvider: sigil.embedding.EmbeddingProvider = sigil.embedding.NoOpEmbeddingProvider
    override def vectorIndex: sigil.vector.VectorIndex = sigil.vector.NoOpVectorIndex
    override def providerFor(modelId: Id[Model], chain: List[sigil.participant.ParticipantId]): Task[Provider] =
      Task.error(new UnsupportedOperationException("probe: providerFor unused"))
  }

  // ---- Option B prototype types (would be the real shape if shipped) ----

  enum UnifiedRespondContent derives RW {
    case Text(content: String)
    case Failure(reason: String, recoverable: Boolean)
    case Field(label: String, value: String, icon: Option[String])
    case Options(prompt: String, options: List[SelectOption], allowMultiple: Boolean)
  }

  case class UnifiedRespondInput(topicLabel: String,
                                 topicSummary: String,
                                 content: UnifiedRespondContent,
                                 endsTurn: Boolean,
                                 keywords: List[String] = Nil) extends ToolInput derives RW

  // ---- Baseline 4-tool inputs (current shape) ----

  case class BaselineRespondInput(topicLabel: String, topicSummary: String,
                                  content: String, endsTurn: Boolean,
                                  keywords: List[String] = Nil) extends ToolInput derives RW
  case class BaselineRespondFailureInput(reason: String, recoverable: Boolean = false) extends ToolInput derives RW
  case class BaselineRespondFieldInput(label: String, value: String, icon: Option[String] = None) extends ToolInput derives RW
  case class BaselineRespondOptionsInput(prompt: String, options: List[SelectOption],
                                         allowMultiple: Boolean) extends ToolInput derives RW

  // ---- Probe tools (just metadata; we never execute) ----

  object UnifiedRespondTool extends TypedTool[UnifiedRespondInput](
    name = ToolName("respond"),
    description = """Emit the agent's reply to the user. The `content` field is a tagged union: pick one of
        |  - `{"type": "Text", "content": "<markdown>"}` for plain text / markdown replies
        |  - `{"type": "Failure", "reason": "<short>", "recoverable": true|false}` when the task can't be completed
        |  - `{"type": "Field", "label": "<l>", "value": "<v>", "icon": null}` for a single labeled key/value
        |  - `{"type": "Options", "prompt": "<q>", "options": [{"label": "...", "value": "...", "description": null, "exclusive": false}, ...], "allowMultiple": false}` for a structured choice""".stripMargin
  ) {
  override def paginate: Boolean = false

    override protected def executeTyped(input: UnifiedRespondInput, ctx: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
      rapid.Stream.empty
  }

  object BaselineRespondTool extends TypedTool[BaselineRespondInput](
    name = ToolName("respond"),
    description = "Emit a plain text / markdown reply to the user."
  ) {
    override def paginate: Boolean = false
    override protected def executeTyped(input: BaselineRespondInput, ctx: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
      rapid.Stream.empty
  }

  object BaselineRespondFailureTool extends TypedTool[BaselineRespondFailureInput](
    name = ToolName("respond_failure"),
    description = "Signal that the agent cannot complete the requested task."
  ) {
    override def paginate: Boolean = false
    override protected def executeTyped(input: BaselineRespondFailureInput, ctx: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
      rapid.Stream.empty
  }

  object BaselineRespondFieldTool extends TypedTool[BaselineRespondFieldInput](
    name = ToolName("respond_field"),
    description = "Emit a single labeled key/value field."
  ) {
    override def paginate: Boolean = false
    override protected def executeTyped(input: BaselineRespondFieldInput, ctx: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
      rapid.Stream.empty
  }

  object BaselineRespondOptionsTool extends TypedTool[BaselineRespondOptionsInput](
    name = ToolName("respond_options"),
    description = "Ask the user to pick from a fixed set of choices."
  ) {
    override def paginate: Boolean = false
    override protected def executeTyped(input: BaselineRespondOptionsInput, ctx: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
      rapid.Stream.empty
  }

  // ---- Probe scenarios ----

  case class Scenario(name: String, prompt: String, expectedKind: String)

  private val scenarios: List[Scenario] = List(
    Scenario(
      name         = "text-reply",
      prompt       = "Reply with exactly the words 'Hello, world!' as plain text. Nothing else.",
      expectedKind = "Text"
    ),
    Scenario(
      name         = "failure-reply",
      prompt       = "The user asked you to do something you cannot do. Reply with a Failure indicating " +
                     "reason=\"This is a demo failure response\" and recoverable=false.",
      expectedKind = "Failure"
    ),
    Scenario(
      name         = "field-reply",
      prompt       = "Reply with a labeled field: label='Status', value='Online'.",
      expectedKind = "Field"
    ),
    Scenario(
      name         = "options-reply",
      prompt       = "The user asked: 'Should I commit this change?' Reply with two single-select options: " +
                     "'Yes' (value=yes) and 'No' (value=no). allowMultiple should be false.",
      expectedKind = "Options"
    )
  )

  // ---- Probe runner ----

  private case class ProbeResult(scenario: String,
                                 roster: String,
                                 toolCalled: Option[String],
                                 emittedKind: Option[String],
                                 expectedKind: String,
                                 success: Boolean,
                                 args: String) {
    def render: String =
      f"  $roster%-9s | $scenario%-15s | tool=${toolCalled.getOrElse("<none>")}%-22s | kind=${emittedKind.getOrElse("?")}%-9s | expected=$expectedKind%-9s | ${if (success) "OK" else "MISS"}"
  }

  def main(args: Array[String]): Unit = {
    val dbPath = s"db/bench/respond-probe-${System.currentTimeMillis()}"
    _root_.profig.Profig("sigil.dbPath").store(dbPath)
    // Register the probe's ToolInput subtypes so fabric's PolyType
    // can round-trip the LLM's tool-call args back into typed
    // values. Without this the provider's ToolCallComplete event
    // raises "Type not found [BaselineRespondInput] ..." when the
    // accumulator tries to write the JSON args into the input poly.
    _root_.sigil.tool.ToolInput.register(
      summon[RW[BaselineRespondInput]],
      summon[RW[BaselineRespondFailureInput]],
      summon[RW[BaselineRespondFieldInput]],
      summon[RW[BaselineRespondOptionsInput]],
      summon[RW[UnifiedRespondInput]]
    )
    val sigil = ProbeSigil()

    val host = sys.env.getOrElse("SIGIL_LLAMACPP_HOST", "https://llama.voidcraft.ai")
    val modelName = sys.env.getOrElse("SIGIL_LLAMACPP_MODEL", "qwen3.5-9b-q4_k_m")
    val modelId   = Model.id("llamacpp", modelName)
    val provider  = LlamaCppProvider(spice.net.URL.parse(host), Nil, sigil)
    sigil.cache.replace(List(
      _root_.sigil.db.Model(
        canonicalSlug       = s"llamacpp/$modelName",
        huggingFaceId       = "",
        name                = modelName,
        displayName         = Some(modelName),
        description         = "",
        contextLength       = 32_768L,
        architecture        = _root_.sigil.db.ModelArchitecture(
          modality = "text->text", inputModalities = List("text"),
          outputModalities = List("text"), tokenizer = "Unknown", instructType = None
        ),
        pricing             = _root_.sigil.db.ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
        topProvider         = _root_.sigil.db.ModelTopProvider(Some(32_768L), None, false),
        perRequestLimits    = None,
        supportedParameters = Set.empty,
        defaultParameters   = _root_.sigil.db.ModelDefaultParameters(),
        knowledgeCutoff     = None,
        expirationDate      = None,
        links               = _root_.sigil.db.ModelLinks(""),
        created             = lightdb.time.Timestamp(),
        modified            = lightdb.time.Timestamp(),
        _id                 = modelId
      )
    )).sync()

    println(s"\n=== RespondUnificationProbe ===")
    println(s"host:  $host")
    println(s"model: $modelName\n")

    val baselineTools = Vector(BaselineRespondTool, BaselineRespondFailureTool, BaselineRespondFieldTool, BaselineRespondOptionsTool)
    val unifiedTools  = Vector(UnifiedRespondTool)

    val results = scenarios.flatMap { sc =>
      List("baseline", "unified").map { roster =>
        val tools = if (roster == "baseline") baselineTools else unifiedTools
        runOne(sigil, provider, modelId, sc, tools, roster)
      }
    }

    println(s"\nResults:")
    println("  roster    | scenario        | tool                   | kind     | expected | outcome")
    println("  " + "-" * 100)
    results.foreach(r => println(r.render))

    val baselineSuccess = results.filter(_.roster == "baseline").count(_.success)
    val unifiedSuccess  = results.filter(_.roster == "unified").count(_.success)
    println(s"\nSummary: baseline=$baselineSuccess/4 unified=$unifiedSuccess/4")
    if (unifiedSuccess < baselineSuccess) {
      println(s"⚠️  unified shape underperformed baseline by ${baselineSuccess - unifiedSuccess} scenario(s)")
    } else if (unifiedSuccess == baselineSuccess) {
      println("✅ unified shape matches baseline")
    } else {
      println(s"✅ unified shape outperformed baseline by ${unifiedSuccess - baselineSuccess} scenario(s)")
    }
  }

  private def runOne(sigil: Sigil,
                     provider: Provider,
                     modelId: Id[Model],
                     scenario: Scenario,
                     tools: Vector[_root_.sigil.tool.Tool],
                     rosterLabel: String): ProbeResult = {
    val request = OneShotRequest(
      modelId            = modelId,
      systemPrompt       = "You are a tool-calling assistant. Use one of the supplied tools to reply. " +
                           "Always call a tool — never reply with plain text outside a tool call.",
      userPrompt         = scenario.prompt,
      generationSettings = GenerationSettings(maxOutputTokens = Some(500), temperature = Some(0.0)),
      tools              = tools
    )
    val events: List[ProviderEvent] = scala.util.Try(provider(request).toList.sync()).getOrElse(Nil)
    val toolCall: Option[(String, ToolInput)] = events.collectFirst {
      case ProviderEvent.ToolCallComplete(_, input) =>
        val toolName = events.collectFirst {
          case ProviderEvent.ToolCallStart(_, n) => n
        }.getOrElse("<unknown>")
        (toolName, input)
    }
    toolCall match {
      case None =>
        ProbeResult(scenario.name, rosterLabel, None, None, scenario.expectedKind, success = false, args = "<no tool call>")
      case Some((tn, input)) =>
        val (kind, success) = (rosterLabel, input) match {
          case ("baseline", _: BaselineRespondInput)         => (Some("Text"),    scenario.expectedKind == "Text")
          case ("baseline", _: BaselineRespondFailureInput)  => (Some("Failure"), scenario.expectedKind == "Failure")
          case ("baseline", _: BaselineRespondFieldInput)    => (Some("Field"),   scenario.expectedKind == "Field")
          case ("baseline", _: BaselineRespondOptionsInput)  => (Some("Options"), scenario.expectedKind == "Options")
          case ("unified",  u: UnifiedRespondInput)          =>
            val k = u.content match {
              case _: UnifiedRespondContent.Text    => "Text"
              case _: UnifiedRespondContent.Failure => "Failure"
              case _: UnifiedRespondContent.Field   => "Field"
              case _: UnifiedRespondContent.Options => "Options"
            }
            (Some(k), k == scenario.expectedKind)
          case _ => (None, false)
        }
        val argSnippet = fabric.io.JsonFormatter.Compact(summon[fabric.rw.RW[ToolInput]].read(input)).take(200)
        ProbeResult(scenario.name, rosterLabel, Some(tn), kind, scenario.expectedKind, success, argSnippet)
    }
  }
}
