package bench

import fabric.*
import fabric.io.JsonParser
import rapid.Task

import java.io.File
import scala.io.{Codec, Source}

/**
 * ConvoMem benchmark runner, built on sigil's
 * `VectorIndex` + `EmbeddingProvider`. Evaluates retrieval quality
 * on the Salesforce ConvoMem dataset across six categories and six
 * evidence levels.
 *
 * Usage:
 *   sbt "benchmark/runMain bench.ConvoMemBench <path-to-pre_mixed_testcases>
 *        [--limit N] [--k N] [--category X] [--max-questions N] [--batch N]"
 *
 * Env: same as [[LoCoMoBench]] (OPENAI_API_KEY, SIGIL_QDRANT_URL).
 */
object ConvoMemBench {

  private val Collection = "sigil-bench-convomem"

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val dataDir = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: ConvoMemBench <path-to-pre_mixed_testcases> [--limit N] [--k N] [--category X] [--max-questions N] [--batch N]")
      println("       retrieval flags: --hybrid [--hybrid-weight D] --temporal-boost [--temporal-halflife DAYS]")
      println("       rerank flags:    --rerank --rerank-model openai/gpt-4o-mini [--rerank-pool N]")
      sys.exit(1)
    }
    val limit = RetrievalFlags.flagInt(args, "--limit").getOrElse(1)
    // Default k=1 ("did the top-ranked conversation contain the evidence?").
    // Scoring also skips cases where conversations.size <= k — see the
    // hit-counting block below for why.
    val k = RetrievalFlags.flagInt(args, "--k").getOrElse(1)
    val categoryFilter = RetrievalFlags.flagString(args, "--category")
    val maxQuestions = RetrievalFlags.flagInt(args, "--max-questions").getOrElse(Int.MaxValue)
    val batchNum = RetrievalFlags.flagInt(args, "--batch")
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

    println("=== Sigil ConvoMem Benchmark ===")
    println(s"Data: $dataDir")
    println(s"Retrieval: ${RetrievalFlags.describe(retrieval)}")
    println(s"Batch limit per evidence level: $limit")
    println(s"Recall@$k")
    println()

    val categories = List(
      ("user_evidence", List(1, 2, 3, 4, 5, 6)),
      ("assistant_facts_evidence", List(1, 2, 3, 4, 5, 6)),
      ("changing_evidence", List(2, 3, 4, 5, 6)),
      ("abstention_evidence", List(1, 2, 3)),
      ("preference_evidence", List(1, 2)),
      ("implicit_connection_evidence", List(1, 2, 3))
    ).filter(c => categoryFilter.isEmpty || categoryFilter.contains(c._1))

    var totalCorrect = 0
    var totalRun = 0
    val categoryBreakdown = scala.collection.mutable.Map.empty[String, (Int, Int)]
    val startTime = System.currentTimeMillis()

    // Failure entries: (category, evidenceLevel, batchFile, question, expected, topK, snippets).
    val failures = scala.collection.mutable.ListBuffer.empty[(String, Int, String, String, Set[Int], List[Int], List[String])]

    for ((catName, evidenceLevels) <- categories) {
      println(s"\n--- $catName ---")
      for (evidenceLevel <- evidenceLevels) {
        val evidenceDir = new File(dataDir, s"$catName/${evidenceLevel}_evidence")
        if (!evidenceDir.exists()) println(s"  Skipping ${evidenceLevel}_evidence (not found)")
        else {
          val allBatchFiles = evidenceDir.listFiles().filter(_.getName.endsWith(".json")).sorted
          val batchFiles = batchNum match {
            case Some(n) => allBatchFiles.filter(_.getName == f"batched_$n%03d.json").take(1)
            case None    => allBatchFiles.take(limit)
          }
          for (batchFile <- batchFiles) {
            if (totalRun >= maxQuestions) println(s"  Reached max questions ($maxQuestions), stopping")
            else {
              val raw = Source.fromFile(batchFile)(using Codec.UTF8).mkString
              val testCases = JsonParser(raw).asVector
              val remaining = math.min(testCases.size, maxQuestions - totalRun)

              for (tc <- testCases.take(remaining)) {
                val evidenceItems = tc("evidenceItems").asVector
                val conversations = tc("conversations").asVector

                // Fresh collection per test case.
                harness.resetCollection().sync()

                val msgToConv = scala.collection.mutable.Map.empty[String, Int]
                val batch = scala.collection.mutable.ListBuffer.empty[(String, String, Map[String, String])]
                conversations.zipWithIndex.foreach { case (conv, convIdx) =>
                  val messages = conv("messages").asVector
                  messages.zipWithIndex.foreach { case (msg, msgIdx) =>
                    val text = msg("text").asString
                    val msgId = s"c$convIdx-m$msgIdx"
                    msgToConv(msgId) = convIdx
                    batch += ((msgId, text, Map(
                      "kind" -> "convomem-message",
                      "conversationId" -> s"conv-$convIdx",
                      "messageId" -> msgId
                    )))
                  }
                  val allText = messages.map(m => m("text").asString).mkString("\n")
                  val truncated = if (allText.length > 6000) allText.take(6000) else allText
                  if (truncated.length >= 50) {
                    val summaryId = s"c$convIdx-summary"
                    msgToConv(summaryId) = convIdx
                    batch += ((summaryId, truncated, Map(
                      "kind" -> "convomem-summary",
                      "conversationId" -> s"conv-$convIdx",
                      "messageId" -> summaryId
                    )))
                  }
                }
                harness.embedAndIndexBatch(batch.toList).sync()

                evidenceItems.foreach { item =>
                  val question = item("question").asString
                  val evidenceConvs = item("conversations").asVector
                  val evidenceIndices = evidenceConvs.flatMap { ec =>
                    val ecSig = ec("messages").asVector.map(m => m("text").asString).mkString(" ").take(200)
                    conversations.zipWithIndex.collectFirst {
                      case (c, idx) if c("messages").asVector.map(m => m("text").asString).mkString(" ").take(200) == ecSig => idx
                    }
                  }.toSet

                  // Skip trivially-correct cases: when the test fixture contains
                  // `conversations.size <= k`, every conversation lands in the
                  // top-K regardless of ranking, so a "hit" carries no retrieval
                  // signal. ConvoMem's 1-evidence and 2-evidence levels are 1-2
                  // conversations per case — at k=5 the entire benchmark
                  // collapses to 100%. Counting only cases with strictly more
                  // conversations than k surfaces the actual retrieval quality.
                  if (conversations.size <= k) {
                    // skip — not informative
                  } else {
                    val results = harness.searchByQueryEnhanced(question, retrieval, limit = 50).sync()
                    val rankedConvs = results.flatMap(r => msgToConv.get(r.id)).distinct
                    val topK = rankedConvs.take(k).toSet
                    val hit = evidenceIndices.exists(topK.contains)

                    if (hit) totalCorrect += 1
                    totalRun += 1

                    val (cc, ct) = categoryBreakdown.getOrElse(catName, (0, 0))
                    categoryBreakdown(catName) = (cc + (if (hit) 1 else 0), ct + 1)

                    if (!hit && reportPath.nonEmpty) {
                      val snippets = results.take(k).flatMap { r =>
                        msgToConv.get(r.id).flatMap { ci =>
                          conversations(ci)("messages").asVector.headOption.map(_("text").asString.take(140))
                        }
                      }
                      failures += ((catName, evidenceLevel, batchFile.getName, question, evidenceIndices, rankedConvs.take(k).toList, snippets))
                    }
                  }
                }
              }

              val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
              val pct = if (totalRun > 0) totalCorrect.toDouble / totalRun * 100 else 0.0
              println(f"  ${evidenceLevel}_evidence/${batchFile.getName}: $remaining cases, R@$k=$pct%.1f%% ($totalCorrect/$totalRun, ${elapsed}%.0fs)")
            }
          }
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
    categoryBreakdown.toList.sortBy(_._1).foreach { case (cat, (c, t)) =>
      val pct = if (t > 0) c.toDouble / t * 100 else 0.0
      println(f"  $cat%-35s $c/$t ($pct%.1f%%)")
    }

    reportPath.foreach { path =>
      val sb = new StringBuilder
      sb.append("# Sigil ConvoMem Benchmark Results\n\n")
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
      failures.toList.foreach { case (cat, ev, fname, q, expected, topK, snips) =>
        sb.append(s"### [$cat / ${ev}_evidence / $fname] `${q}`\n\n")
        sb.append(s"- expected conv indices: ${expected.toList.sorted.mkString(", ")}\n")
        sb.append(s"- top-${k} ranked: ${topK.mkString(", ")}\n")
        if (snips.nonEmpty) {
          sb.append("- top-K snippets:\n")
          snips.zipWithIndex.foreach { case (s, i) => sb.append(s"  ${i + 1}. ${s}\n") }
        }
        sb.append("\n")
      }
      java.nio.file.Files.writeString(java.nio.file.Paths.get(path), sb.toString)
      println(s"\nReport written: $path")
    }

    System.exit(0)
  }
}
