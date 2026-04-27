package bench

import fabric.*
import fabric.io.JsonParser
import rapid.Task

import java.io.File
import scala.io.{Codec, Source}

/**
 * LoCoMo benchmark runner, built on sigil's
 * [[sigil.vector.VectorIndex]] + [[sigil.embedding.EmbeddingProvider]]
 * primitives.
 *
 * Evaluates retrieval quality on the LoCoMo dataset (reformatted via
 * ConvoMem). Three categories:
 *   1 — basic facts
 *   2 — temporal reasoning
 *   5 — abstention
 *
 * Usage:
 *   sbt "benchmark/runMain bench.LoCoMoBench <path-to-locomo-dir>
 *        [--limit N] [--k N] [--category N]"
 *
 * `<locomo-dir>` contains subdirectories `category_1_basic_facts/`,
 * `category_2_temporal/`, `category_5_abstention/`, each with
 * per-file JSON fixtures.
 *
 * Env:
 *   SIGIL_QDRANT_URL         default `http://localhost:6333`
 *   OPENAI_API_KEY           required
 *   SIGIL_EMBEDDING_MODEL    default `text-embedding-3-small`
 *   SIGIL_EMBEDDING_DIMENSIONS  default 1536
 */
object LoCoMoBench {

  private val Collection = "sigil-bench-locomo"

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val dataDir = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: LoCoMoBench <path-to-locomo-dir> [--limit N] [--k N] [--category N]")
      println("       retrieval flags: --hybrid [--hybrid-weight D] --temporal-boost [--temporal-halflife DAYS] [--temporal-weight D]")
      println("       rerank flags:    --rerank --rerank-model openai/gpt-4o-mini [--rerank-pool N]")
      sys.exit(1)
    }
    val limit = RetrievalFlags.flagInt(args, "--limit").getOrElse(Int.MaxValue)
    val k = RetrievalFlags.flagInt(args, "--k").getOrElse(10)
    val categoryFilter = RetrievalFlags.flagInt(args, "--category")
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

    println("=== Sigil LoCoMo Benchmark ===")
    println(s"Data: $dataDir")
    println(s"Retrieval: ${RetrievalFlags.describe(retrieval)}")
    println(s"Recall@$k")
    println()

    val categories = List(
      (1, "category_1_basic_facts"),
      (2, "category_2_temporal"),
      (5, "category_5_abstention")
    ).filter(c => categoryFilter.isEmpty || categoryFilter.contains(c._1))

    var totalCorrect = 0
    var totalRun = 0
    val typeBreakdown = scala.collection.mutable.Map.empty[Int, (Int, Int)]
    val startTime = System.currentTimeMillis()

    // Capture failure details for the optional report file. Each
    // entry: (categoryNum, fileName, question, evidenceConvIndices, topK indices, top hit content).
    val failures = scala.collection.mutable.ListBuffer.empty[(Int, String, String, Set[Int], List[Int], List[String])]

    for ((catNum, catDir) <- categories) {
      val dir = new File(dataDir, catDir)
      if (!dir.exists()) println(s"Skipping $catDir (not found)")
      else {
        val files = dir.listFiles().filter(_.getName.endsWith(".json")).sorted.take(limit)
        println(s"\n--- Category $catNum: $catDir (${files.length} files) ---")

        for (file <- files) {
          val raw = Source.fromFile(file)(using Codec.UTF8).mkString
          val data = JsonParser(raw)
          val conversations = data("conversations").asVector
          val evidenceItems = data("evidenceItems").asVector

          // Fresh collection per file — fixtures define their own
          // haystack, so we wipe between files to avoid cross-
          // contamination.
          harness.resetCollection().sync()

          // Embed all conversations (haystack) — one point per message,
          // plus one per session summary. Batched so a 100-turn
          // haystack is embedded in ~2 API calls instead of 100.
          val convToIdx = scala.collection.mutable.Map.empty[String, Int]
          val batch = scala.collection.mutable.ListBuffer.empty[(String, String, Map[String, String])]
          conversations.zipWithIndex.foreach { case (conv, convIdx) =>
            val messages = conv("messages").asVector
            messages.zipWithIndex.foreach { case (msg, msgIdx) =>
              val text = msg("text").asString
              val messageId = s"conv-$convIdx-msg-$msgIdx"
              convToIdx(messageId) = convIdx
              batch += ((messageId, text, Map(
                "kind" -> "locomo-message",
                "conversationId" -> s"locomo-conv-$convIdx",
                "messageId" -> messageId
              )))
            }
            val allText = messages.map(m => m("text").asString).mkString("\n")
            val truncated = if (allText.length > 6000) allText.take(6000) else allText
            if (truncated.length >= 50) {
              val summaryId = s"conv-$convIdx-summary"
              convToIdx(summaryId) = convIdx
              batch += ((summaryId, truncated, Map(
                "kind" -> "locomo-summary",
                "conversationId" -> s"locomo-conv-$convIdx",
                "messageId" -> summaryId
              )))
            }
          }
          harness.embedAndIndexBatch(batch.toList).sync()

          // Evaluate each question
          evidenceItems.foreach { item =>
            val question = item("question").asString
            val evidenceConvs = item("conversations").asVector
            val evidenceConvIndices = evidenceConvs.flatMap { ec =>
              val ecMsgs = ec("messages").asVector.map(m => m("text").asString).mkString(" ").take(200)
              conversations.zipWithIndex.collectFirst {
                case (c, idx) if c("messages").asVector.map(m => m("text").asString).mkString(" ").take(200) == ecMsgs => idx
              }
            }.toSet

            val results = harness.searchByQueryEnhanced(question, retrieval, limit = 50).sync()
            val rankedConvIndices = results.flatMap(r => convToIdx.get(r.id)).distinct

            val topK = rankedConvIndices.take(k).toSet
            val hit = evidenceConvIndices.exists(topK.contains)
            if (hit) totalCorrect += 1
            totalRun += 1

            val (tc, tt) = typeBreakdown.getOrElse(catNum, (0, 0))
            typeBreakdown(catNum) = (tc + (if (hit) 1 else 0), tt + 1)

            if (!hit && reportPath.nonEmpty) {
              val topHitContent = results.take(k).flatMap { r =>
                convToIdx.get(r.id).flatMap { ci =>
                  val msgs = conversations(ci)("messages").asVector
                  msgs.headOption.map(m => m("text").asString.take(140))
                }
              }
              failures += ((catNum, file.getName, question, evidenceConvIndices, rankedConvIndices.take(k).toList, topHitContent))
            }
          }

          val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
          val pct = if (totalRun > 0) totalCorrect.toDouble / totalRun * 100 else 0.0
          println(f"  ${file.getName}: ${evidenceItems.size} questions, R@$k=$pct%.1f%% ($totalCorrect/$totalRun, ${elapsed}%.0fs)")
        }
      }
    }

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    println()
    println("=== Results ===")
    val overall = if (totalRun > 0) totalCorrect.toDouble / totalRun * 100 else 0.0
    println(f"Recall@$k: $totalCorrect/$totalRun ($overall%.1f%%)")
    println(f"Time: ${elapsed}%.0fs")
    println()
    println("By category:")
    val catLabel: Int => String = {
      case 1 => "basic_facts"
      case 2 => "temporal"
      case 5 => "abstention"
      case n => s"category_$n"
    }
    typeBreakdown.toList.sortBy(_._1).foreach { case (cat, (c, t)) =>
      val pct = if (t > 0) c.toDouble / t * 100 else 0.0
      println(f"  ${catLabel(cat)}%-20s $c/$t ($pct%.1f%%)")
    }

    reportPath.foreach { path =>
      val sb = new StringBuilder
      sb.append("# Sigil LoCoMo Benchmark Results\n\n")
      sb.append(s"**Date:** ${java.time.Instant.now()}\n")
      sb.append(s"**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)\n")
      sb.append(s"**Retrieval:** ${RetrievalFlags.describe(retrieval)}\n")
      sb.append(f"**Score:** $totalCorrect/$totalRun ($overall%.1f%% R@$k)\n\n")
      sb.append("## Per-category accuracy\n\n")
      sb.append("| Category | Correct | Total | Accuracy |\n|---|---|---|---|\n")
      typeBreakdown.toList.sortBy(_._1).foreach { case (cat, (c, t)) =>
        val pct = if (t > 0) c.toDouble / t * 100 else 0.0
        sb.append(f"| ${catLabel(cat)} | $c | $t | $pct%.1f%% |\n")
      }
      sb.append(s"\n## Failures (${failures.size})\n\n")
      failures.toList.foreach { case (catNum, fname, q, expected, topK, topHits) =>
        sb.append(s"### [${catLabel(catNum)}] ${fname} — `${q}`\n\n")
        sb.append(s"- expected conv indices: ${expected.toList.sorted.mkString(", ")}\n")
        sb.append(s"- top-${k} ranked conv indices: ${topK.mkString(", ")}\n")
        if (topHits.nonEmpty) {
          sb.append("- top-K snippets:\n")
          topHits.zipWithIndex.foreach { case (s, i) => sb.append(s"  ${i + 1}. ${s}\n") }
        }
        sb.append("\n")
      }
      java.nio.file.Files.writeString(java.nio.file.Paths.get(path), sb.toString)
      println(s"\nReport written: $path")
    }

    System.exit(0)
  }
}
