package bench

import fabric.*
import fabric.io.JsonParser
import rapid.Task

import java.io.File
import scala.io.{Codec, Source}

/**
 * LongMemEval benchmark runner, built on sigil's
 * `VectorIndex` + `EmbeddingProvider`. Evaluates retrieval quality
 * on the LongMemEval dataset (500 real user questions).
 *
 * Usage:
 *   sbt "benchmark/runMain bench.LongMemEvalBench <path-to-longmemeval_s_cleaned.json>
 *        [--limit N] [--k N] [--indices 1,2,3] [--report <path>]"
 *
 * Dataset: https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned
 *
 * Env: OPENAI_API_KEY (required), SIGIL_QDRANT_URL (default localhost:6333),
 * SIGIL_EMBEDDING_MODEL, SIGIL_EMBEDDING_DIMENSIONS.
 *
 * Flags:
 *   --limit N      Only run first N questions
 *   --k N          Recall@N (default 5)
 *   --indices ...  Comma-separated 1-based indices to run
 *   --report PATH  Markdown report output (default benchmark-results.md)
 */
object LongMemEvalBench {

  private val Collection = "sigil-bench-longmemeval"

  /** LongMemEval stamps: "2023/05/30 (Tue) 23:40" → epoch millis. */
  private def parseDate(dateStr: String): Long = {
    try {
      val clean = dateStr.replaceAll("\\([A-Za-z]+\\)\\s*", "").trim
      val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
      val dt = java.time.LocalDateTime.parse(clean, fmt)
      dt.atZone(java.time.ZoneId.of("UTC")).toInstant.toEpochMilli
    } catch {
      case _: Exception => System.currentTimeMillis()
    }
  }

  /** `topResults` entries: `(sessionId, score, contentSnippet)`.
    * Content is the turn's actual text, truncated for report
    * readability. Session-level dedupe happens at scoring time
    * (see `rankedSessionIdsDistinct`), but the per-turn list is
    * kept un-deduped in the report so a reader can see *which*
    * turn within the session scored best. */
  private case class QuestionResult(questionIdx: Int,
                                    question: String,
                                    questionType: String,
                                    answer: String,
                                    answerSessionIds: Set[String],
                                    topResults: List[(String, Double, String)],
                                    answerRank: Option[Int],
                                    hit: Boolean,
                                    ndcg: Double,
                                    turnCount: Int)

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val dataPath = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: LongMemEvalBench <path-to-longmemeval_s_cleaned.json> [--limit N] [--k N] [--indices I,J,K] [--report <path>]")
      println("       retrieval flags: --hybrid [--hybrid-weight D] --temporal-boost [--temporal-halflife DAYS]")
      println("       rerank flags:    --rerank --rerank-model openai/gpt-4o-mini [--rerank-pool N]")
      println("\nDownload dataset: https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned")
      sys.exit(1)
    }
    val limit = RetrievalFlags.flagInt(args, "--limit").getOrElse(Int.MaxValue)
    val k = RetrievalFlags.flagInt(args, "--k").getOrElse(5)
    val indices: Option[Set[Int]] = args.indexOf("--indices") match {
      case -1 => None
      case i  => args.lift(i + 1).map(_.split(",").flatMap(_.trim.toIntOption).map(_ - 1).toSet)
    }
    val reportPath = RetrievalFlags.flagString(args, "--report").getOrElse("benchmark-results.md")

    val harness = BenchmarkHarness.fromEnv(Collection)
    BenchmarkHarness.ensureQdrantReachable(BenchmarkHarness.qdrantUrlFromEnv)
    lazy val benchSigil = BenchmarkSigil.withOpenAI(
      embeddingProvider = harness.embeddingProvider,
      vectorIndex = harness.vectorIndex,
      openaiApiKey = BenchmarkSigil.openaiApiKeyFromEnv,
      openaiBaseUrl = BenchmarkSigil.openaiBaseUrlFromEnv
    )
    val retrieval = RetrievalFlags.fromArgs(args, harness, benchSigil, chain = Nil)

    println("=== Sigil LongMemEval Benchmark ===")
    println(s"Data: $dataPath")
    println(s"Retrieval: ${RetrievalFlags.describe(retrieval)}")
    println(s"Recall@$k")
    println()

    println("Loading dataset...")
    val raw = Source.fromFile(new File(dataPath))(using Codec.UTF8).mkString
    val entries = JsonParser(raw).asVector
    val questionIndices = indices match {
      case Some(idxs) => idxs.filter(_ < entries.size).toList.sorted
      case None       => (0 until math.min(entries.size, limit)).toList
    }
    val total = questionIndices.size
    println(s"Loaded ${entries.size} questions, running $total${indices.map(_ => " (specific indices)").getOrElse("")}")
    println()

    var correct = 0
    var totalRun = 0
    var ndcgSum = 0.0
    val typeBreakdown = scala.collection.mutable.Map.empty[String, (Int, Int)]
    val allResults = scala.collection.mutable.ListBuffer.empty[QuestionResult]
    val startTime = System.currentTimeMillis()

    for (qi <- 0 until total) {
      val i = questionIndices(qi)
      val entry = entries(i)
      val question = entry("question").asString
      val questionType = entry.get("question_type").map(_.asString).getOrElse("unknown")
      val answer = entry.get("answer").map(_.asString).getOrElse("")
      val answerSessionIds = entry("answer_session_ids").asVector.map(_.asString).toSet

      val sessions = entry("haystack_sessions").asVector
      val sessionIds = entry("haystack_session_ids").asVector.map(_.asString)
      val sessionDates = entry("haystack_dates").asVector.map(_.asString)

      if (sessions.nonEmpty) {
        harness.resetCollection().sync()

        val messageToSession = scala.collection.mutable.Map.empty[String, String]
        val batch = scala.collection.mutable.ListBuffer.empty[(String, String, Map[String, String])]
        sessions.zip(sessionIds).zip(sessionDates).foreach { case ((session, sessId), dateStr) =>
          val epochMs = parseDate(dateStr)

          session.asVector.zipWithIndex.foreach { case (turn, turnIdx) =>
            val messageId = s"msg-$i-$sessId-turn-$turnIdx"
            messageToSession(messageId) = sessId
            batch += ((messageId, turn("content").asString, Map(
              "kind" -> "longmemeval-message",
              "conversationId" -> s"bench-$i-$sessId",
              "messageId" -> messageId,
              "timestamp" -> epochMs.toString
            )))
          }

          val userText = session.asVector
            .filter(t => t("role").asString == "user")
            .map(t => t("content").asString)
            .mkString("\n")
          val truncatedText = if (userText.length > 6000) userText.take(6000) else userText
          if (truncatedText.length >= 50) {
            val summaryId = s"msg-$i-$sessId-summary"
            messageToSession(summaryId) = sessId
            batch += ((summaryId, truncatedText, Map(
              "kind" -> "longmemeval-summary",
              "conversationId" -> s"bench-$i-$sessId",
              "messageId" -> summaryId,
              "timestamp" -> epochMs.toString
            )))
          }
        }
        harness.embedAndIndexBatch(batch.toList).sync()

        val refTime = parseDate(entry.get("question_date").map(_.asString).getOrElse(""))
        val results = harness.searchByQueryEnhanced(
          question,
          retrieval,
          limit = 50,
          referenceTimeMs = Some(refTime)
        ).sync()
        // Dedupe by session id before taking top-K so a single session
        // whose turns all score high doesn't occupy every slot (standard
        // Recall@k is "did the answer appear in any of k *distinct*
        // sessions"). Keep per-turn content in a lookup table so the
        // failure report can show which turn drove the session's rank.
        val rankedSessionIds = results.flatMap(r => messageToSession.get(r.id)).distinct
        val topTurnForSession = scala.collection.mutable.LinkedHashMap.empty[String, (Double, String)]
        results.foreach { r =>
          messageToSession.get(r.id).foreach { sid =>
            if (!topTurnForSession.contains(sid)) {
              val content = r.payload.getOrElse(sigil.vector.HybridSearch.TextKey, r.id)
              topTurnForSession(sid) = (r.score, content)
            }
          }
        }

        if (indices.isDefined || totalRun < 3) {
          println(s"  Q: $question")
          println(s"  Answer sessions: $answerSessionIds")
          println(s"  Embedded ${messageToSession.size} items, search returned ${results.size} results")
          rankedSessionIds.take(5).foreach { sessId =>
            val isAnswer = answerSessionIds.contains(sessId)
            val (score, _) = topTurnForSession.getOrElse(sessId, (0.0, ""))
            println(f"    ${if (isAnswer) "✓" else " "} $sessId (score=$score%.4f)")
          }
        }

        val topK = rankedSessionIds.take(k).toSet
        val hit = answerSessionIds.exists(topK.contains)
        if (hit) correct += 1

        val answerRank = rankedSessionIds.indexWhere(answerSessionIds.contains) match {
          case -1 => None
          case idx => Some(idx + 1)
        }

        val relevances = rankedSessionIds.take(k).map(id => if (answerSessionIds.contains(id)) 1.0 else 0.0)
        val dcgScore = relevances.zipWithIndex.map { case (rel, idx) => rel / (math.log(idx + 2) / math.log(2)) }.sum
        val idealRels = relevances.sorted.reverse
        val idcg = idealRels.zipWithIndex.map { case (rel, idx) => rel / (math.log(idx + 2) / math.log(2)) }.sum
        val ndcgScore = if (idcg > 0) dcgScore / idcg else 0.0
        ndcgSum += ndcgScore

        val topResultsWithContent = rankedSessionIds.take(10).map { sessId =>
          val (score, content) = topTurnForSession.getOrElse(sessId, (0.0, ""))
          val snippet = content.replace("\n", " ").take(180)
          (sessId, score, snippet)
        }

        allResults += QuestionResult(
          i, question, questionType, answer, answerSessionIds,
          topResultsWithContent,
          answerRank, hit, ndcgScore, messageToSession.size
        )
        totalRun += 1

        val (tc, tt) = typeBreakdown.getOrElse(questionType, (0, 0))
        typeBreakdown(questionType) = (tc + (if (hit) 1 else 0), tt + 1)

        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        val rate = if (elapsedSec > 1) totalRun / elapsedSec else 0.0
        val eta = if (rate > 0) ((total - totalRun) / rate).toInt else 0
        val pct = correct.toDouble / totalRun * 100
        val etaStr = if (eta > 60) s"${eta / 60}m${eta % 60}s" else s"${eta}s"
        if (indices.isDefined || totalRun % 5 == 0 || totalRun == total || totalRun <= 5 || !hit) {
          println(f"[Q${i+1}%d $totalRun%d/$total] R@$k=$pct%.1f%% NDCG@$k=${ndcgSum / totalRun * 100}%.1f%% ($rate%.2f q/s, ETA $etaStr) ${if (hit) "✓" else "✗"} $questionType")
        }
      }
    }

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    println()
    println("=== Results ===")
    val overallPct = correct.toDouble / math.max(totalRun, 1) * 100
    println(f"Recall@$k: $correct/$totalRun ($overallPct%.1f%%)")
    println(f"NDCG@$k:   ${ndcgSum / math.max(totalRun, 1) * 100}%.1f%%")
    println(f"Time:      ${elapsed}%.1fs (${totalRun / math.max(elapsed, 0.001)}%.1f q/s)")
    println()
    println("By question type:")
    typeBreakdown.toList.sortBy(_._1).foreach { case (qtype, (c, t)) =>
      val pct = if (t > 0) c.toDouble / t * 100 else 0.0
      println(f"  $qtype%-30s $c/$t ($pct%.1f%%)")
    }

    val failures = allResults.filter(!_.hit)
    val report = new StringBuilder
    report.append("# Sigil LongMemEval Benchmark Results\n\n")
    report.append(s"**Date:** ${java.time.LocalDateTime.now().toString.take(16)}\n")
    report.append(s"**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)\n")
    report.append(f"**Score:** $correct/$totalRun ($overallPct%.1f%% R@$k)\n")
    report.append(f"**NDCG@$k:** ${ndcgSum / math.max(totalRun, 1) * 100}%.1f%%\n")
    report.append(f"**Time:** ${elapsed}%.0fs (${totalRun / math.max(elapsed, 0.001)}%.1f q/s)\n\n")

    report.append("## Results by Question Type\n\n")
    report.append("| Type | Correct | Total | Accuracy |\n")
    report.append("|------|---------|-------|----------|\n")
    typeBreakdown.toList.sortBy(_._1).foreach { case (qtype, (c, t)) =>
      report.append(f"| $qtype | $c | $t | ${c.toDouble / t * 100}%.1f%% |\n")
    }

    report.append(s"\n## Failures (${failures.size})\n\n")
    if (failures.nonEmpty) {
      report.append("| # | Type | Question | Expected | Rank |\n")
      report.append("|---|------|----------|----------|------|\n")
      failures.foreach { f =>
        val rankStr = f.answerRank.map(r => s"#$r").getOrElse("not found")
        val qShort = if (f.question.length > 60) f.question.take(57) + "..." else f.question
        val aShort = if (f.answer.length > 40) f.answer.take(37) + "..." else f.answer
        report.append(s"| Q${f.questionIdx + 1} | ${f.questionType} | $qShort | $aShort | $rankStr |\n")
      }
    } else {
      report.append("No failures — perfect score!\n")
    }

    report.append("\n## All Questions\n\n")
    allResults.foreach { r =>
      val status = if (r.hit) "PASS" else "FAIL"
      val icon = if (r.hit) "✅" else "❌"
      report.append(s"### Q${r.questionIdx + 1} — $icon $status\n\n")
      report.append(s"- **Type:** ${r.questionType}\n")
      report.append(s"- **Question:** ${r.question}\n")
      report.append(s"- **Expected answer:** ${r.answer}\n")
      report.append(s"- **Answer session(s):** ${r.answerSessionIds.mkString(", ")}\n")
      r.answerRank match {
        case Some(rank) if r.hit => report.append(s"- **Answer found at rank:** #$rank ✓\n")
        case Some(rank)          => report.append(s"- **Answer found at rank:** #$rank (outside top $k)\n")
        case None if !r.hit      => report.append(s"- **Answer not found in top 50 results**\n")
        case _                   =>
      }
      report.append(s"- **Turns embedded:** ${r.turnCount}\n")
      report.append(s"- **Top 5 distinct sessions:**\n")
      r.topResults.take(5).zipWithIndex.foreach { case ((sessId, score, content), idx) =>
        val isAnswer = r.answerSessionIds.contains(sessId)
        val mark = if (isAnswer) "✓" else " "
        report.append(f"  ${idx + 1}. $mark `$sessId` (score=$score%.4f)\n")
        if (content.nonEmpty) report.append(s"     > ${content}\n")
      }
      report.append("\n")
    }

    val writer = new java.io.PrintWriter(reportPath)
    writer.write(report.toString())
    writer.close()
    println(s"Full report written to $reportPath (${allResults.size} questions, ${failures.size} failures)")

    System.exit(0)
  }
}
