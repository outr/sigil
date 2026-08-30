package bench

import fabric.*
import fabric.io.JsonParser
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.conversation.{ContextKey, ContextMemory, Conversation, MemorySource, Topic, TopicEntry}
import sigil.conversation.compression.{NoOpMemoryRetriever, StandardMemoryRetriever}
import sigil.db.Model
import sigil.embedding.OpenAICompatibleEmbeddingProvider
import sigil.event.Message
import sigil.participant.DefaultAgentParticipant
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.provider.{GenerationSettings, Instructions, ReasoningMode, ToolPolicy}
import sigil.vector.InMemoryVectorIndex
import spice.net.{TLDValidation, URL}

import java.io.File
import scala.concurrent.duration.*
import scala.io.{Codec, Source}

/**
 * LongMemEval scored the way the rest of the world scores it:
 * **end-to-end QA accuracy under an LLM judge**, not retrieval recall.
 *
 * [[LongMemEvalBench]] measures whether the answer session reaches the
 * top-k — a retrieval diagnostic, and a good one (99.4% R@5). It is not
 * the number memory systems publish. The published figure (SOTA ~94.4%
 * at ~6.9k tokens/query) is: put the question to a model that can see
 * only what the memory system surfaced, and judge the answer. This
 * runner does that through Sigil's real per-turn path — haystack
 * persisted as [[ContextMemory]], passive recall injecting into the
 * prompt, the runtime model answering, [[BenchJudge]] grading — so the
 * score reflects the retriever, the curator, and the prompt together
 * rather than the vector index alone.
 *
 * Arms (`--arms`, default `sigil`):
 *   - `sigil` — memories persisted, passive recall injects them.
 *   - `norag` — no memory at all. The floor: whatever the model can
 *     answer from its weights, which for a synthetic haystack is
 *     near-nothing. Isolates how much of any score is memory.
 *   - `longcontext` — the honest control and the expensive one: the
 *     whole haystack stuffed into the user message (~115k tokens/
 *     question), i.e. what a memory system has to beat. Run it locally
 *     in full; on a paid model, sample it.
 *
 * Every question gets its own [[ArmSpace]], so no question can recall
 * another's haystack.
 *
 * Usage:
 * {{{
 * sbt "benchmark/runMain bench.LongMemEvalQABench <longmemeval_s_cleaned.json>
 *      [--limit N] [--arms sigil,norag,longcontext] [--memories N]
 *      [--reasoning] [--max-output N] [--report PATH] [--verbose]"
 * }}}
 *
 * Runtime + judge default to the local llama.cpp host
 * (`SIGIL_LLAMACPP_HOST`, `SIGIL_LLAMACPP_MODEL`); `OPENAI_API_KEY`
 * supplies embeddings for the vector leg.
 */
object LongMemEvalQABench {

  private final case class QaResult(index: Int,
                                    questionType: String,
                                    question: String,
                                    gold: String,
                                    answer: String,
                                    correct: Boolean,
                                    judgeFailed: Boolean,
                                    goldRetrieved: Boolean,
                                    tokens: Long,
                                    ingestMs: Long,
                                    turnMs: Long,
                                    judgeMs: Long,
                                    memoryCount: Int,
                                    errored: Boolean = false)

  private final case class ArmSummary(arm: String, results: List[QaResult]) {
    def accuracy: Double = if (scored.isEmpty) 0.0 else scored.count(_.correct).toDouble / scored.size
    /** Retrieval diagnostic — meaningless for an arm that retrieves
      * nothing, hence the Option. */
    def retrieval: Option[Double] =
      if (arm == "norag" || scored.isEmpty) None
      else Some(scored.count(_.goldRetrieved).toDouble / scored.size)
    def judgeFailures: Int = results.count(_.judgeFailed)
    def errors: Int = results.count(_.errored)
    private def scored = results.filterNot(_.errored)
    def meanTokens: Long = if (results.isEmpty) 0L else results.map(_.tokens).sum / results.size
  }

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val dataPath = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: LongMemEvalQABench <longmemeval_s_cleaned.json> [--limit N] [--arms sigil,norag,longcontext]")
      println("       [--memories N] [--model ID] [--judge-model ID] [--report PATH] [--verbose]")
      sys.exit(1)
    }
    val limit = RetrievalFlags.flagInt(args, "--limit").getOrElse(Int.MaxValue)
    val memoryLimit = RetrievalFlags.flagInt(args, "--memories").getOrElse(10)
    val verbose = args.contains("--verbose")
    // Qwen3-family models served by llama.cpp emit `reasoning_content`
    // BEFORE any answer, and an unbounded reasoning pass against a
    // modest output cap ends in `finish_reason: length` with no content
    // at all — the turn never settles. Reasoning is therefore OFF by
    // default (the provider flips Qwen3's `enable_thinking` chat-template
    // kwarg); `--reasoning` turns it back on with a budget that can
    // actually accommodate it. What this benchmark measures is memory,
    // not deliberation.
    val reasoning = args.contains("--reasoning")
    val outputCap = RetrievalFlags.flagInt(args, "--max-output").getOrElse(if (reasoning) 4000 else 600)
    val turnTimeout = RetrievalFlags.flagInt(args, "--turn-timeout").getOrElse(600)
    // On by default: without it the temporal-reasoning category is
    // unanswerable by construction. `--no-timestamps` reproduces the
    // dateless behaviour for an A/B.
    val timestamps = !args.contains("--no-timestamps")
    // Every judged record, appended as JSONL. This is the artifact
    // `JudgeAgreementBench` re-judges with a stronger model — judging a
    // stored (question, gold, answer) triple costs one cheap call and
    // needs no re-run of the expensive part.
    val judgeLog = RetrievalFlags.flagString(args, "--judge-log")
    val reportPath = RetrievalFlags.flagString(args, "--report")
    val arms = RetrievalFlags.flagString(args, "--arms")
      .map(_.split(',').toList.map(_.trim.toLowerCase).filter(_.nonEmpty))
      .getOrElse(List("sigil"))

    profig.Profig("sigil.dbPath").store(s"db/bench/lme-qa-${System.currentTimeMillis()}")
    val host = new MemoryArmsSigil

    val llamaHost = sys.env.getOrElse("SIGIL_LLAMACPP_HOST", "https://llama.voidcraft.ai")
    val modelName = sys.env.getOrElse("SIGIL_LLAMACPP_MODEL", "qwen3.5-9b-q4_k_m")
    val modelId = Model.id("llamacpp", modelName)
    host.setProvider(LlamaCppProvider(URL.parse(llamaHost), Nil, host))
    host.cache.merge(List(BenchModels.llamaCpp(modelId, modelName))).sync()
    val judge = BenchJudge(modelId)

    val embeddingKey = sys.env.get("OPENAI_API_KEY").filter(_.nonEmpty).getOrElse {
      System.err.println("ERROR: OPENAI_API_KEY required (embeddings for the vector leg)")
      sys.exit(1)
    }
    val embedBaseUrl = sys.env.get("OPENAI_BASE_URL").filter(_.nonEmpty)
      .flatMap(s => URL.get(s, tldValidation = TLDValidation.Off).toOption)
      .getOrElse(URL.parse("https://api.openai.com"))
    // One index, cleared between questions: every question carries its
    // own haystack, so points from a prior question are dead weight in
    // every subsequent cosine scan (space filters already keep them from
    // being *retrieved* — this is about not paying for them).
    val vectorIndex = new InMemoryVectorIndex
    host.setEmbedding(
      OpenAICompatibleEmbeddingProvider(
        embeddingKey, embedBaseUrl,
        sys.env.getOrElse("SIGIL_EMBEDDING_MODEL", "text-embedding-3-small"),
        sys.env.get("SIGIL_EMBEDDING_DIMENSIONS").flatMap(_.toIntOption).getOrElse(1536)
      ),
      vectorIndex
    )
    host.instance.sync()

    println("\n=== LongMemEval — end-to-end QA ===")
    println(s"runtime+judge model: $modelName @ $llamaHost")
    println(s"arms: ${arms.mkString(", ")}   memories/turn: $memoryLimit   timestamps: ${if (timestamps) "on" else "off"}   reasoning: ${if (reasoning) "on" else "off"}   max output: $outputCap")

    val raw = Source.fromFile(new File(dataPath))(using Codec.UTF8).mkString
    val entries = JsonParser(raw).asVector
    val count = math.min(entries.size, limit)
    println(s"questions: $count of ${entries.size}\n")

    val summaries = arms.map { arm =>
      val results = (0 until count).toList.map { i =>
        vectorIndex.clear()
        // Containment: a question that hangs or throws is recorded as a
        // failure and the run continues. A 500-question overnight job
        // that dies on question 1 produces nothing; one that loses a
        // question produces 499 results and a visible error count.
        val r =
          try runQuestion(host, judge, modelId, entries(i), i, arm, memoryLimit, reasoning, outputCap, turnTimeout, timestamps)
          catch {
            case e: Throwable =>
              println(s"  [$arm] ERROR  q$i ${e.getClass.getSimpleName}: ${e.getMessage.take(120)}")
              QaResult(i, "error", "", "", "", correct = false, judgeFailed = false,
                goldRetrieved = false, tokens = 0L, ingestMs = 0L, turnMs = 0L, judgeMs = 0L,
                memoryCount = 0, errored = true)
          }
        judgeLog.foreach(p => appendJudgeRecord(p, arm, r))
        val mark = if (r.judgeFailed) "JUDGE?" else if (r.correct) "OK  " else "MISS"
        println(f"  [$arm] $mark%-6s q$i%-4d mem=${r.memoryCount}%-5d ingest=${r.ingestMs / 1000}%4ds turn=${r.turnMs / 1000}%4ds judge=${r.judgeMs / 1000}%3ds" +
          (if (verbose) f"  ${r.answer.replaceAll("\\s+", " ").take(80)}" else ""))
        r
      }
      val s = ArmSummary(arm, results)
      println(render(List(s)))
      s
    }

    val table = render(summaries)
    println("\n=== Final ===")
    println(table)
    reportPath.foreach { p =>
      java.nio.file.Files.writeString(java.nio.file.Path.of(p),
        s"# LongMemEval — end-to-end QA\n\nmodel: `$modelName`  memories/turn: $memoryLimit  questions: $count\n\n$table\n")
      println(s"report written to $p")
    }
    host.shutdown.sync()
    System.exit(0)
  }

  private def runQuestion(host: MemoryArmsSigil,
                          judge: BenchJudge,
                          modelId: Id[Model],
                          entry: Json,
                          index: Int,
                          arm: String,
                          memoryLimit: Int,
                          reasoning: Boolean,
                          outputCap: Int,
                          turnTimeout: Int,
                          timestamps: Boolean,
                          settleMs: Long = 2000L): QaResult = {
    val question = entry("question").asString
    val questionType = entry.get("question_type").map(_.asString).getOrElse("unknown")
    val gold = entry.get("answer").map(_.asString).getOrElse("")
    val answerSessionIds = entry("answer_session_ids").asVector.map(_.asString).toSet
    val sessions = entry("haystack_sessions").asVector
    val sessionIds = entry("haystack_session_ids").asVector.map(_.asString)
    val sessionDates = entry.get("haystack_dates").map(_.asVector.map(_.asString)).getOrElse(Vector.empty)

    val space = ArmSpace(s"lme-$arm-q$index")
    host.setArmSpace(space)
    host.setRetriever(
      if (arm == "sigil") StandardMemoryRetriever(limit = memoryLimit)
      else NoOpMemoryRetriever
    )

    // The haystack, one memory per turn, tagged with its session so the
    // retrieval diagnostic can tell whether the ANSWER session's text is
    // what reached the prompt.
    // (sessionId, date, text). The date rides INTO the memory text —
    // LongMemEval's temporal-reasoning questions ("four weeks ago")
    // cannot be answered from evidence that dropped when it happened,
    // so a store that discards the session stamp makes that category
    // chance-level no matter how good retrieval is.
    val turns: List[(String, String, String)] = sessions.zip(sessionIds).zipWithIndex.toList
      .flatMap { case ((session, sessId), si) =>
        val date = sessionDates.lift(si).getOrElse("")
        session.asVector.toList.map(t => (sessId, date, t("content").asString))
      }.filter(_._3.trim.nonEmpty)

    val ingestStart = System.currentTimeMillis()
    if (arm == "sigil") {
      val memories = turns.map { case (sessId, date, text) =>
        val stamped = if (timestamps) LongMemEvalDates.prefix(date) + text else text
        ContextMemory(
          fact = stamped,
          label = stamped.take(48),
          summary = stamped,
          source = MemorySource.Corpus,
          spaceId = space,
          // The session's real time, not ingest time — the fusion
          // stage's recency term and any temporal filter both read it.
          created = lightdb.time.Timestamp(LongMemEvalDates.parse(date)),
          extraContext = Map(ContextKey.CorpusPassage -> sessId)
        )
      }
      host.persistMemories(memories).sync()
    }
    val ingestMs = System.currentTimeMillis() - ingestStart

    val userText = arm match {
      case "longcontext" =>
        val haystack = turns.map { case (sessId, date, text) =>
          s"[$sessId${if (date.nonEmpty) s" $date" else ""}] $text"
        }.mkString("\n")
        s"Conversation history:\n$haystack\n\nQuestion: $question"
      case _ => question
    }

    val agent = DefaultAgentParticipant(
      id = ArmsBenchAgent,
      modelId = modelId,
      toolNames = Nil,
      tools = ToolPolicy.ActiveOnly(Nil),
      instructions = Instructions(),
      generationSettings = GenerationSettings(
        maxOutputTokens = Some(outputCap),
        temperature = Some(0.0),
        reasoningMode = if (reasoning) ReasoningMode.On else ReasoningMode.Off
      )
    )
    val harness = new AgentBenchHarness(host, ArmsBenchUser)
    val turnStart = System.currentTimeMillis()
    val trace = harness.runConversation(
      conversationFactory = id => Conversation(
        topics = List(TopicEntry(
          id = Topic.id(s"lme-$arm-$index-${rapid.Unique()}"),
          label = "Recall from our past conversations",
          summary = "")),
        participants = List(agent),
        space = space,
        _id = id
      ),
      userMessages = List(userText),
      perTurnTimeout = turnTimeout.seconds
    ).sync()

    val turnMs = System.currentTimeMillis() - turnStart
    // The llama.cpp provider admits one stream at a time. The harness
    // returns when the turn's events settle, but the agent loop's tail
    // (and any post-turn background work) can still hold the slot —
    // a following call then queues behind it and the provider logs
    // "stream slots busy (max=1)" before starving. Give the tail room
    // to finish before the judge call.
    Thread.sleep(settleMs)
    val answer = trace.turns.headOption.map(_.replyText).getOrElse("")
    val tokens = trace.turns.flatMap(_.events).collect { case m: Message => m.usage.totalTokens.toLong }.sum
    val judgeStart = System.currentTimeMillis()
    val verdict = judge.judge(host, question, gold, answer).sync()
    val judgeMs = System.currentTimeMillis() - judgeStart
    // Retrieval diagnostic: did text from an ANSWER session reach the
    // prompt? Read off the same injection the model saw.
    val injectedFacts = host.lastInjected.toSet
    val goldRetrieved = turns.exists { case (sessId, date, text) =>
      val stamped = if (timestamps) LongMemEvalDates.prefix(date) + text else text
      answerSessionIds.contains(sessId) && injectedFacts.contains(stamped)
    }

    QaResult(index, questionType, question, gold, answer,
      correct = verdict.correct, judgeFailed = verdict.judgeFailed,
      goldRetrieved = goldRetrieved, tokens = tokens,
      ingestMs = ingestMs, turnMs = turnMs, judgeMs = judgeMs, memoryCount = turns.size)
  }

  /** One JSONL line per judged answer: enough for a second judge to
    * re-decide the same call without re-running retrieval or the model. */
  private def appendJudgeRecord(path: String, arm: String, r: QaResult): Unit = {
    val json = obj(
      "index" -> num(r.index),
      "arm" -> str(arm),
      "questionType" -> str(r.questionType),
      "question" -> str(r.question),
      "gold" -> str(r.gold),
      "answer" -> str(r.answer),
      "localCorrect" -> bool(r.correct),
      "judgeFailed" -> bool(r.judgeFailed)
    )
    val line = fabric.io.JsonFormatter.Compact(json) + "\n"
    java.nio.file.Files.writeString(
      java.nio.file.Path.of(path), line,
      java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
  }

  private def render(summaries: List[ArmSummary]): String = {
    val header = "| arm | QA accuracy | gold retrieved | judge failures | errors | mean tokens/question | mean ingest | mean turn |"
    val sep = "|---|--:|--:|--:|--:|--:|--:|--:|"
    val rows = summaries.map { s =>
      val retr = if (s.arm == "norag") "—" else f"${s.retrieval.getOrElse(0.0) * 100}%.1f%%"
      val meanIngest = if (s.results.isEmpty) 0L else s.results.map(_.ingestMs).sum / s.results.size / 1000
      val meanTurn = if (s.results.isEmpty) 0L else s.results.map(_.turnMs).sum / s.results.size / 1000
      f"| ${s.arm} | ${s.accuracy * 100}%.1f%% | $retr | ${s.judgeFailures} | ${s.errors} | ${s.meanTokens} | ${meanIngest}s | ${meanTurn}s |"
    }
    (header :: sep :: rows).mkString("\n")
  }
}
