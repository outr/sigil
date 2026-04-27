package bench

import fabric.*
import fabric.io.JsonParser
import rapid.Task

import java.io.File
import scala.io.{Codec, Source}

/**
 * REALTALK benchmark runner — built on sigil's `VectorIndex` +
 * `EmbeddingProvider` primitives.
 *
 * REALTALK is a 21-day real-world dialogue dataset (arXiv 2502.13270 —
 * `danny911kr/REALTALK` on GitHub). Unlike LoCoMo / ConvoMem which are
 * LLM-generated, REALTALK contains actual chat-app exchanges between
 * pairs of real people over multiple sessions (typically 18-25 sessions
 * per pair, 400-1500 messages, 70-85 ground-truth questions).
 *
 * The dataset stores each conversation as a single JSON file with:
 *   - `session_<N>` keys — each a list of utterances `{clean_text,
 *     speaker, date_time, dia_id}`. The `dia_id` (e.g. `"D1:6"`) is the
 *     stable identifier referenced by ground-truth `evidence`.
 *   - `qa` — a list of `{question, answer, evidence: List[dia_id],
 *     category: Int}`. `evidence` lists the specific utterances that
 *     support the ground-truth answer.
 *
 * Retrieval evaluation: index every utterance under its `dia_id`, search
 * by `question`, and count a hit when the top-K results include any
 * dia_id from the QA's `evidence` list. This is genuine retrieval
 * signal (no trivial-pass artifact like ConvoMem's 1-conv cases) — each
 * conversation has hundreds of utterances and `evidence` typically
 * picks 1-9 specific ones.
 *
 * Usage:
 *   sbt "benchmark/runMain bench.RealTalkBench /path/to/realtalk-data
 *        [--limit N] [--k N] [--max-questions N] [--report PATH]"
 *
 * `<realtalk-data>` is the directory containing `Chat_*.json` files.
 * Download from https://github.com/danny911kr/REALTALK/tree/main/data
 * (`curl` the raw `download_url`s).
 *
 * Shares retrieval flags with the other benchmarks — see
 * [[RetrievalFlags]].
 */
object RealTalkBench {

  private val Collection = "sigil-bench-realtalk"

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val dataDir = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: RealTalkBench <path-to-realtalk-data> [--limit N] [--k N] [--max-questions N] [--report PATH]")
      println("       retrieval flags: --hybrid [--hybrid-weight D] --temporal-boost [--temporal-halflife DAYS]")
      println("       rerank flags:    --rerank --rerank-model openai/gpt-4o-mini [--rerank-pool N]")
      sys.exit(1)
    }
    val limit = RetrievalFlags.flagInt(args, "--limit").getOrElse(Int.MaxValue)
    val k = RetrievalFlags.flagInt(args, "--k").getOrElse(5)
    val maxQuestions = RetrievalFlags.flagInt(args, "--max-questions").getOrElse(Int.MaxValue)
    val reportPath = RetrievalFlags.flagString(args, "--report")

    val harness = BenchmarkHarness.fromEnv(Collection)
    BenchmarkHarness.ensureQdrantReachable(BenchmarkHarness.qdrantUrlFromEnv)
    lazy val benchSigil = BenchmarkSigil.withOpenAI(
      embeddingProvider = harness.embeddingProvider,
      vectorIndex = harness.vectorIndex,
      openaiApiKey = BenchmarkSigil.openaiApiKeyFromEnv,
      openaiBaseUrl = BenchmarkSigil.openaiBaseUrlFromEnv
    )
    val retrieval = RetrievalFlags.fromArgs(args, harness, benchSigil, chain = Nil)

    println("=== Sigil REALTALK Benchmark ===")
    println(s"Data: $dataDir")
    println(s"Retrieval: ${RetrievalFlags.describe(retrieval)}")
    println(s"Recall@$k")
    println()

    val files = new File(dataDir).listFiles()
      .filter(_.getName.endsWith(".json"))
      .filter(_.getName.startsWith("Chat_"))
      .sorted
      .take(limit)

    var totalCorrect = 0
    var totalRun = 0
    val categoryBreakdown = scala.collection.mutable.Map.empty[Int, (Int, Int)]
    val failures = scala.collection.mutable.ListBuffer.empty[(String, String, String, Int, Set[String], List[String])]
    val startTime = System.currentTimeMillis()

    for (file <- files if totalRun < maxQuestions) {
      val raw = Source.fromFile(file)(using Codec.UTF8).mkString
      val data = JsonParser(raw)

      // Collect every utterance keyed by its stable dia_id
      val sessionKeys = data.asObj.value.keys
        .filter(k => k.startsWith("session_") && !k.contains("date_time") && !k.startsWith("events_"))
        .toList.sorted

      val batch = scala.collection.mutable.ListBuffer.empty[(String, String, Map[String, String])]
      sessionKeys.foreach { sk =>
        val utterances = data(sk).asVector
        utterances.foreach { u =>
          val diaId = u.get("dia_id").map(_.asString).getOrElse("")
          val text = u.get("clean_text").map(_.asString).getOrElse("")
          val speaker = u.get("speaker").map(_.asString).getOrElse("")
          if (diaId.nonEmpty && text.nonEmpty) {
            // Embed `<speaker>: <text>` so the retriever has speaker
            // context (the QA "What are Kate's hobbies?" needs to find
            // utterances where Kate herself spoke about hiking, not
            // Elise asking).
            batch += ((diaId, s"$speaker: $text", Map(
              "kind" -> "realtalk-utterance",
              "chatId" -> file.getName,
              "diaId" -> diaId,
              "session" -> sk
            )))
          }
        }
      }

      // Fresh collection per chat — different chats have unrelated
      // participants and evidence ids overlap (D1:6 means session 1
      // turn 6 in EVERY chat), so cross-chat indexing would let
      // the wrong chat's D1:6 satisfy a different chat's question.
      harness.resetCollection().sync()
      harness.embedAndIndexBatch(batch.toList).sync()

      val qa = data("qa").asVector
      var chatCorrect = 0
      var chatRun = 0

      qa.foreach { item =>
        if (totalRun < maxQuestions) {
          val question = item("question").asString
          val answer = item("answer").asString
          val evidence: Set[String] = item("evidence").asVector.map(_.asString).toSet
          val category = item.get("category").map(_.asInt).getOrElse(0)

          val results = harness.searchByQueryEnhanced(question, retrieval, limit = math.max(k, 50)).sync()
          val rankedDiaIds = results.map(_.id)
          val topK = rankedDiaIds.take(k).toSet
          val hit = evidence.exists(topK.contains)

          if (hit) { totalCorrect += 1; chatCorrect += 1 }
          totalRun += 1; chatRun += 1
          val (cc, ct) = categoryBreakdown.getOrElse(category, (0, 0))
          categoryBreakdown(category) = (cc + (if (hit) 1 else 0), ct + 1)

          if (!hit && reportPath.nonEmpty) {
            failures += ((file.getName, question, answer, category, evidence, rankedDiaIds.take(k).toList))
          }
        }
      }

      val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
      val pct = if (chatRun > 0) chatCorrect.toDouble / chatRun * 100 else 0.0
      println(f"  ${file.getName}: $chatCorrect/$chatRun (${pct}%.1f%%) — running total $totalCorrect/$totalRun (${elapsed}%.0fs)")
    }

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    val overall = if (totalRun > 0) totalCorrect.toDouble / totalRun * 100 else 0.0
    println()
    println("=== Results ===")
    println(f"Recall@$k: $totalCorrect/$totalRun ($overall%.1f%%)")
    println(f"Time: ${elapsed}%.0fs")
    println()
    println("By category:")
    categoryBreakdown.toList.sortBy(_._1).foreach { case (cat, (c, t)) =>
      val pct = if (t > 0) c.toDouble / t * 100 else 0.0
      println(f"  category $cat%-2d $c/$t ($pct%.1f%%)")
    }

    reportPath.foreach { path =>
      val sb = new StringBuilder
      sb.append("# Sigil REALTALK Benchmark Results\n\n")
      sb.append(s"**Date:** ${java.time.Instant.now()}\n")
      sb.append(s"**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)\n")
      sb.append(s"**Retrieval:** ${RetrievalFlags.describe(retrieval)}\n")
      sb.append(f"**Score:** $totalCorrect/$totalRun ($overall%.1f%% R@$k)\n\n")
      sb.append("## Per-category accuracy\n\n")
      sb.append("| Category | Correct | Total | Accuracy |\n|---|---|---|---|\n")
      categoryBreakdown.toList.sortBy(_._1).foreach { case (cat, (c, t)) =>
        val pct = if (t > 0) c.toDouble / t * 100 else 0.0
        sb.append(f"| $cat | $c | $t | $pct%.1f%% |\n")
      }
      sb.append(s"\n## Failures (${failures.size})\n\n")
      failures.toList.foreach { case (chat, q, ans, cat, expected, topK) =>
        sb.append(s"### [$chat / category $cat] `$q`\n\n")
        sb.append(s"- expected answer: ${ans}\n")
        sb.append(s"- evidence dia_ids: ${expected.toList.sorted.mkString(", ")}\n")
        sb.append(s"- top-$k retrieved: ${topK.mkString(", ")}\n\n")
      }
      java.nio.file.Files.writeString(java.nio.file.Paths.get(path), sb.toString)
      println(s"\nReport written: $path")
    }

    System.exit(0)
  }
}
