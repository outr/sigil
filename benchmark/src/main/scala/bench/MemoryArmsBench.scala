package bench

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{ContextMemory, Conversation, MemorySource, Topic, TopicEntry}
import sigil.conversation.compression.{ConsultMemoryDistiller, NoOpMemoryRetriever, StandardMemoryRetriever}
import sigil.db.Model
import sigil.embedding.OpenAICompatibleEmbeddingProvider
import sigil.event.Message
import sigil.participant.DefaultAgentParticipant
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.provider.{GenerationSettings, Instructions, ToolPolicy}
import sigil.tool.ToolName
import sigil.vector.InMemoryVectorIndex
import spice.net.{TLDValidation, URL}

import scala.concurrent.duration.*

/**
 * Arms-and-controls memory benchmark, modeled on the discipline of an
 * honest agentic benchmark: the same small runtime model answers the
 * same corpus questions under each memory configuration, scored on
 * the settled conversation trace — with an adversarial tier
 * (unanswerable questions, hedging correct) scored separately from
 * accuracy, and a naive prompt-stuffing control the machinery has to
 * beat on cost while matching on accuracy. Arms are isolated by
 * per-arm memory spaces and per-arm conversations against one durable
 * store — no cross-arm recall is possible by construction.
 *
 * Runs against a llama.cpp host (`SIGIL_LLAMACPP_HOST`, default the
 * public endpoint) — no paid key required. When `OPENAI_API_KEY` is
 * set, the vector leg is wired through OpenAI-compatible embeddings
 * plus an in-memory index; otherwise retrieval runs lexical-only and
 * the report says so.
 *
 * Usage:
 * {{{
 * sbt "benchmark/runMain bench.MemoryArmsBench [--limit N] [--arms baseline,passive,agentic,distilled,stuffed] [--report PATH]"
 * }}}
 */
object MemoryArmsBench {

  private final case class QuestionResult(question: ArmQuestion,
                                          reply: String,
                                          tokens: Long,
                                          searches: Int,
                                          correct: Boolean,
                                          hedged: Boolean)

  private final case class ArmResult(arm: MemoryArm, results: List[QuestionResult]) {
    private def answerable = results.filter(_.question.answerable)
    private def adversarial = results.filterNot(_.question.answerable)
    def accuracy: String = s"${answerable.count(_.correct)}/${answerable.size}"
    def hedgedRate: String = s"${adversarial.count(_.hedged)}/${adversarial.size}"
    def meanTokens: Long = if (results.isEmpty) 0L else results.map(_.tokens).sum / results.size
    def searchCalls: Int = results.map(_.searches).sum
  }

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val limit = argValue(args, "--limit").flatMap(_.toIntOption).getOrElse(Int.MaxValue)
    val verbose = args.contains("--verbose")
    val reportPath = argValue(args, "--report")
    val arms: List[MemoryArm] = argValue(args, "--arms") match {
      case Some(csv) => csv.split(',').toList.map(_.trim.toLowerCase).flatMap(n =>
        MemoryArm.values.find(_.toString.toLowerCase == n))
      case None => MemoryArm.values.toList
    }
    val questions = MemoryArmsCorpus.questions.take(math.max(limit, 1))

    profig.Profig("sigil.dbPath").store(s"db/bench/memory-arms-${System.currentTimeMillis()}")
    val host = new MemoryArmsSigil

    val llamaHost = sys.env.getOrElse("SIGIL_LLAMACPP_HOST", "https://llama.voidcraft.ai")
    val modelName = sys.env.getOrElse("SIGIL_LLAMACPP_MODEL", "qwen3.5-9b-q4_k_m")
    val modelId = Model.id("llamacpp", modelName)
    val provider = LlamaCppProvider(URL.parse(llamaHost), Nil, host)
    host.setProvider(provider)
    host.cache.merge(List(benchModel(modelId, modelName))).sync()

    val vectorWired = sys.env.get("OPENAI_API_KEY").filter(_.nonEmpty) match {
      case Some(key) =>
        val baseUrl = sys.env.get("OPENAI_BASE_URL").filter(_.nonEmpty)
          .flatMap(s => URL.get(s, tldValidation = TLDValidation.Off).toOption)
          .getOrElse(URL.parse("https://api.openai.com"))
        val model = sys.env.getOrElse("SIGIL_EMBEDDING_MODEL", "text-embedding-3-small")
        val dims = sys.env.get("SIGIL_EMBEDDING_DIMENSIONS").flatMap(_.toIntOption).getOrElse(1536)
        host.setEmbedding(OpenAICompatibleEmbeddingProvider(key, baseUrl, model, dims), new InMemoryVectorIndex)
        true
      case None => false
    }

    host.instance.sync()
    println(s"\n=== MemoryArmsBench ===")
    println(s"host: $llamaHost  model: $modelName  vector: ${if (vectorWired) "embeddings + in-memory index" else "OFF (lexical-only)"}")
    println(s"questions: ${questions.size} (${questions.count(_.answerable)} answerable + ${questions.count(!_.answerable)} adversarial)  arms: ${arms.mkString(", ")}\n")

    val results = arms.map { arm =>
      val r = runArm(host, arm, modelId, questions, verbose)
      println(render(List(r), vectorWired))
      r
    }

    val table = render(results, vectorWired)
    println("\n=== Final ===")
    println(table)
    reportPath.foreach { p =>
      java.nio.file.Files.writeString(java.nio.file.Path.of(p),
        s"# MemoryArmsBench\n\nmodel: `$modelName`  vector: ${if (vectorWired) "on" else "off (lexical-only)"}\n\n$table\n")
      println(s"report written to $p")
    }
    host.shutdown.sync()
    System.exit(0)
  }

  private def runArm(host: MemoryArmsSigil,
                     arm: MemoryArm,
                     modelId: Id[Model],
                     questions: List[ArmQuestion],
                     verbose: Boolean): ArmResult = {
    val space = ArmSpace(arm.toString.toLowerCase)
    host.setArmSpace(space)
    val debug = sys.env.contains("ARMS_DEBUG")
    host.setRetriever(arm match {
      case MemoryArm.Passive | MemoryArm.Distilled =>
        val base = StandardMemoryRetriever(
          limit = 5,
          queryFrom = Some { (frames, chain) =>
            val q = StandardMemoryRetriever.lastNonAgentMessage(frames, chain)
            if (debug) println(s"  [debug] retrieval question=${q.map(_.take(80))} (frames=${frames.size})")
            q
          }
        )
        if (!debug) base
        else base.copy(pipeline = Some(base.stages.map { stage =>
          new sigil.conversation.compression.retrieval.MemoryRetrievalStage {
            override val name: String = stage.name
            override def run(state: sigil.conversation.compression.retrieval.MemoryRetrievalState,
                             ctx: sigil.conversation.compression.retrieval.MemoryRetrievalContext) =
              stage.run(state, ctx).map { out =>
                println(s"  [debug] stage=${stage.name} lex=${out.lexical.size} vec=${out.vectorHits.size} " +
                  s"kw=${out.keywordHits.size} ranked=${out.ranked.take(3).map(_.fact.take(30)).mkString(" | ")}")
                if (stage.name == "recall") {
                  println(s"  [debug]   lexHead=${out.lexical.take(3).map(_.fact.take(30)).mkString(" | ")}")
                  println(s"  [debug]   vecHead=${out.vectorHits.take(3).map(_.fact.take(30)).mkString(" | ")}")
                  println(s"  [debug]   kwHead=${out.keywordHits.take(3).map(_.fact.take(30)).mkString(" | ")}")
                }
                out
              }
          }
        }))
      case _ => NoOpMemoryRetriever
    })

    // Seed the arm's corpus into its own space. The Distilled arm
    // seeds through the consult distiller (the small runtime model —
    // a deliberate worst case for the ingest upgrade).
    arm match {
      case MemoryArm.Passive | MemoryArm.Agentic =>
        host.persistMemories(MemoryArmsCorpus.facts.map(memory(_, space))).sync()
      case MemoryArm.Distilled =>
        host.setDistiller(ConsultMemoryDistiller(modelId, minFactChars = 60))
        host.persistMemories(MemoryArmsCorpus.facts.map(memory(_, space))).sync()
        host.clearDistiller()
      case MemoryArm.Baseline | MemoryArm.Stuffed => ()
    }

    val toolNames: List[ToolName] = arm match {
      case MemoryArm.Agentic => List(SemanticSearchName)
      case _ => Nil
    }
    val agent = DefaultAgentParticipant(
      id = ArmsBenchAgent,
      modelId = modelId,
      toolNames = toolNames,
      tools = ToolPolicy.ActiveOnly(toolNames),
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(700), temperature = Some(0.0))
    )
    val harness = new AgentBenchHarness(host, ArmsBenchUser)

    val results = questions.map { q =>
      val text = arm match {
        case MemoryArm.Stuffed =>
          s"Here is everything known about you:\n${MemoryArmsCorpus.facts.map("- " + _).mkString("\n")}\n\n${q.question}"
        case _ => q.question
      }
      val trace = harness.runConversation(
        conversationFactory = id => Conversation(
          topics = List(TopicEntry(id = Topic.id(s"arms-${arm.toString.toLowerCase}-${rapid.Unique()}"),
            label = "Persona questions", summary = "")),
          participants = List(agent),
          space = space,
          _id = id
        ),
        userMessages = List(text),
        perTurnTimeout = 180.seconds
      ).sync()
      val result = score(q, trace)
      if (verbose) {
        val flag = if (q.answerable) { if (result.correct) "OK  " else "MISS" }
                   else { if (result.hedged) "HEDGE" else "ASSERT" }
        println(s"  [$arm] $flag ${q.question} -> ${result.reply.replaceAll("\\s+", " ").take(120)}")
      }
      result
    }
    ArmResult(arm, results)
  }

  private def score(q: ArmQuestion, trace: ConversationTrace): QuestionResult = {
    val reply = trace.turns.headOption.map(_.replyText).getOrElse("")
    val lower = reply.toLowerCase
    val tokens = trace.turns.flatMap(_.events).collect {
      case m: Message => m.usage.totalTokens.toLong
    }.sum
    val searches = trace.allToolInvokes.count(_.toolName == SemanticSearchName)
    QuestionResult(
      question = q,
      reply = reply,
      tokens = tokens,
      searches = searches,
      correct = q.answerable && q.gold.exists(lower.contains),
      hedged = !q.answerable && MemoryArmsCorpus.hedgeMarkers.exists(lower.contains)
    )
  }

  private def render(results: List[ArmResult], vectorWired: Boolean): String = {
    val header = "| arm | accuracy | hedged (adversarial) | mean tokens/turn | semantic_search calls |"
    val sep = "|---|--:|--:|--:|--:|"
    val rows = results.map { r =>
      s"| ${r.arm} | ${r.accuracy} | ${r.hedgedRate} | ${r.meanTokens} | ${r.searchCalls} |"
    }
    val note = if (vectorWired) "" else "\n_(vector leg off — lexical-only retrieval)_"
    (header :: sep :: rows).mkString("\n") + note
  }

  private def memory(fact: String, space: ArmSpace): ContextMemory = ContextMemory(
    fact = fact,
    label = fact.take(32),
    summary = fact,
    source = MemorySource.Explicit,
    spaceId = space
  )

  private val SemanticSearchName: ToolName = sigil.tool.util.SemanticSearchTool.name

  private def argValue(args: Array[String], name: String): Option[String] =
    args.indexOf(name) match {
      case i if i >= 0 && i + 1 < args.length => Some(args(i + 1))
      case _ => None
    }

  private def benchModel(modelId: Id[Model], modelName: String): Model = Model(
    canonicalSlug = s"llamacpp/$modelName",
    huggingFaceId = "",
    name = modelName,
    displayName = Some(modelName),
    description = "",
    contextLength = 32768L,
    architecture = sigil.db.ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "Unknown",
      instructType = None
    ),
    pricing = sigil.db.ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
    topProvider = sigil.db.ModelTopProvider(Some(32768L), None, false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    defaultParameters = sigil.db.ModelDefaultParameters(),
    knowledgeCutoff = None,
    expirationDate = None,
    links = sigil.db.ModelLinks(""),
    created = lightdb.time.Timestamp(),
    modified = lightdb.time.Timestamp(),
    _id = modelId
  )
}
