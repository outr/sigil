package bench.agentdojo.banking

import bench.{AgentBenchHarness, BenchmarkAgentSigil}
import bench.agentdojo.{AgentDojoAgent, AgentDojoUser}
import fabric.rw.RW
import lightdb.id.Id
import profig.Profig
import rapid.Task
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.Model
import sigil.participant.{AgentParticipant, DefaultAgentParticipant, Participant}
import sigil.provider.{GenerationSettings, Instructions, Provider}
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.deepseek.DeepSeekProvider
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.provider.openai.OpenAIProvider
import spice.net.{TLDValidation, URL, url}

import java.io.{File, PrintWriter}

/**
 * Runner: AgentDojo banking suite × `important_instructions` attack,
 * scored against one model.
 *
 * Usage:
 * {{{
 * sbt "benchmark/runMain bench.agentdojo.banking.AgentDojoBankingBench openai/gpt-5.4-mini --report /path/to/banking-gpt-5.4-mini.md"
 * }}}
 *
 * Flags:
 *   - first positional arg = model id (e.g. `openai/gpt-5.4-mini`,
 *     `anthropic/claude-haiku-4-5`, `llamacpp/qwen3.5-9b-q4_k_m`)
 *   - `--report PATH` — write markdown report; default
 *     `benchmark/agentdojo-banking-<modelLabel>.md`
 *   - `--user-tasks 0,1,2` — limit to these user-task ids
 *   - `--injection-tasks 0,1,2` — limit to these injection-task ids
 *   - `--baseline-only` — skip injection scenarios; only run utility
 *     baseline (16 cells per model instead of 160)
 *
 * Required env per provider:
 *   - `openai/...` → OPENAI_API_KEY
 *   - `anthropic/...` → ANTHROPIC_API_KEY
 *   - `deepseek/...` → DEEPSEEK_API_KEY
 *   - `llamacpp/...` → LLAMACPP_HOST (default https://llama.voidcraft.ai)
 */
object AgentDojoBankingBench {

  private val topicId: Id[Topic] = Id("agentdojo-banking-topic")
  private val topicEntry: TopicEntry = TopicEntry(
    id = topicId,
    label = "AgentDojo banking",
    summary = "Synthetic banking scenarios — AgentDojo benchmark."
  )

  def main(args: Array[String]): Unit = {
    val modelArg = args.headOption.getOrElse {
      System.err.println("ERROR: first arg must be a model id (e.g. openai/gpt-5.4-mini)")
      sys.exit(1)
    }
    val reportPath = stringFlag(args, "--report").getOrElse(s"agentdojo-banking-${modelLabel(modelArg)}.md")
    val userTaskIds = intListFlag(args, "--user-tasks")
    val injectionTaskIds = intListFlag(args, "--injection-tasks")
    val baselineOnly = args.contains("--baseline-only")

    val userTasks = userTaskIds.fold(BankingUserTask.all)(ids => BankingUserTask.all.filter(t => ids.contains(t.id)))
    val injectionTasks = injectionTaskIds.fold(BankingInjectionTask.all)(ids => BankingInjectionTask.all.filter(t => ids.contains(t.id)))

    val modelId = Model.id(modelArg)
    val sigil = buildSigil(modelArg)
    val dbPath = java.nio.file.Path.of("db", s"agentdojo-banking-${modelLabel(modelArg)}")
    deleteRecursive(dbPath)
    Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    sigil.instance.sync()

    val harness = AgentBenchHarness(sigil, AgentDojoUser)
    val scorer = new BankingScorer(
      harness = harness,
      modelId = modelId,
      modelLabel = modelArg,
      buildAgent = id => DefaultAgentParticipant(
        id = AgentDojoAgent,
        modelId = id,
        toolNames = BankingToolCatalog.toolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings(maxOutputTokens = Some(4000), temperature = Some(0.0))
      ),
      topicId = topicId,
      topicEntry = topicEntry
    )
    val effectiveInjections = if (baselineOnly) Nil else injectionTasks
    val results = scorer.runAll(userTasks, effectiveInjections, includeBaseline = true).sync()

    val summary = BankingReport.summarize(results, modelArg, includeBaseline = !baselineOnly)
    BankingReport.writeMarkdown(reportPath, summary, results)
    println()
    println(BankingReport.consoleSummary(summary))
    println()
    println(s"Wrote report: $reportPath")
    sys.exit(0)
  }

  private def buildSigil(modelArg: String): BenchmarkAgentSigil = {
    val agentParticipantRWs: List[RW[? <: Participant]] = List(summon[RW[DefaultAgentParticipant]])
    val wireLog = java.nio.file.Path.of("wire-logs", s"agentdojo-banking-${modelLabel(modelArg)}.jsonl")
    if (java.nio.file.Files.exists(wireLog)) java.nio.file.Files.delete(wireLog)
    java.nio.file.Files.createDirectories(wireLog.getParent)
    lazy val self: BenchmarkAgentSigil = new BenchmarkAgentSigil(
      viewer = AgentDojoUser,
      agentParticipantId = AgentDojoAgent,
      extraParticipantIds = Nil,
      participantRWs = agentParticipantRWs,
      providerFactory = id => providerFor(modelArg, id, self),
      toolInputs = BankingToolCatalog.toolInputRegistrations,
      extraSignalRegistrations = bench.agentdojo.banking.events.BankingToolEvents.signalRegistrations,
      wireLogPath = Some(wireLog)
    )
    self
  }

  private def providerFor(modelArg: String, requestedId: Id[Model], sigilRef: BenchmarkAgentSigil): Task[Provider] = {
    val prefix = modelArg.takeWhile(_ != '/')
    prefix match {
      case "openai" =>
        val key = requireEnv("OPENAI_API_KEY")
        Task.pure(OpenAIProvider(apiKey = key, sigilRef = sigilRef, baseUrl = openaiBaseUrl))
      case "anthropic" =>
        val key = requireEnv("ANTHROPIC_API_KEY")
        Task.pure(AnthropicProvider(apiKey = key, sigilRef = sigilRef))
      case "deepseek" =>
        val key = requireEnv("DEEPSEEK_API_KEY")
        Task.pure(DeepSeekProvider(apiKey = key, sigilRef = sigilRef))
      case "llamacpp" =>
        val host = Option(System.getenv("LLAMACPP_HOST")).filter(_.nonEmpty)
          .flatMap(s => URL.get(s, tldValidation = TLDValidation.Off).toOption)
          .getOrElse(url"https://llama.voidcraft.ai")
        Task.pure(LlamaCppProvider(url = host, models = Nil, sigilRef = sigilRef))
      case _ =>
        Task.error(new IllegalArgumentException(s"Unknown provider prefix in model id '$modelArg'"))
    }
  }

  private def openaiBaseUrl: URL = Option(System.getenv("OPENAI_BASE_URL"))
    .filter(_.nonEmpty)
    .flatMap(s => URL.get(s, tldValidation = TLDValidation.Off).toOption)
    .getOrElse(url"https://api.openai.com")

  private def requireEnv(name: String): String =
    Option(System.getenv(name)).filter(_.nonEmpty).getOrElse {
      System.err.println(s"ERROR: $name not set"); sys.exit(1)
    }

  private def stringFlag(args: Array[String], name: String): Option[String] = {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.length) Some(args(idx + 1)) else None
  }

  private def intListFlag(args: Array[String], name: String): Option[Set[Int]] =
    stringFlag(args, name).map(_.split(",").iterator.flatMap(_.trim.toIntOption).toSet)

  private def modelLabel(modelArg: String): String =
    modelArg.replace('/', '_').replace(':', '_').replace('@', '_')

  private def deleteRecursive(path: java.nio.file.Path): Unit = {
    if (java.nio.file.Files.exists(path)) {
      val s = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        s.iterator().asScala.toList.reverse.foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally s.close()
    }
  }
}
