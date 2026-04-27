package bench

import fabric.*
import fabric.io.JsonParser
import rapid.Task

import java.io.File
import scala.io.{Codec, Source}

/**
 * MemBench benchmark runner, built on sigil's `VectorIndex` +
 * `EmbeddingProvider` primitives. Evaluates retrieval quality on
 * the MemBench dataset (ACL 2025 Findings — "Towards More
 * Comprehensive Evaluation on the Memory of LLM-based Agents";
 * arXiv 2506.21605).
 *
 * The dataset is a set of per-category JSON files. Each file
 * contains 500 "roles" (test cases); each role has:
 *   - a haystack: `message_list` is a list of sessions, each session
 *     is a list of turn objects with `sid`, `user_message`,
 *     `assistant_message`, `time`, `place`
 *   - a QA block: `question`, target step(s) in `target_step_id`
 *     (a list of `[session_idx, turn_idx]` pairs), multiple-choice
 *     `choices` + `ground_truth`, and a `time` the question was asked
 *
 * For retrieval, we embed every turn once, search by `question`,
 * and count a hit when the top-k results include any of the target
 * `[session_idx, turn_idx]` turns. MemBench reports R@5 overall at
 * 80.3% across 8,500 items (10 categories × 500 items ÷ 2 agent
 * types — FirstAgent is ~5,000).
 *
 * Usage:
 *   sbt "benchmark/runMain bench.MemBenchBench <path-to-MemData-dir>
 *        [--limit N] [--k N] [--category CAT] [--agent FirstAgent|ThirdAgent]"
 *
 * The `<path-to-MemData-dir>` is the `MemData` folder in the
 * MemBench repo (https://github.com/import-myself/Membench), which
 * contains `FirstAgent/` and `ThirdAgent/` subfolders with one
 * `<category>.json` per category.
 *
 * Shares retrieval flags with the other benchmarks — see
 * [[RetrievalFlags]].
 */
object MemBenchBench {

  private val Collection = "sigil-bench-membench"

  private val Categories: List[String] = List(
    "simple",
    "aggregative",
    "comparative",
    "knowledge_update",
    "highlevel",
    "highlevel_rec",
    "lowlevel_rec",
    "conditional",
    "noisy",
    "post_processing"
  )

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val dataDir = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: MemBenchBench <path-to-MemData-dir> [--limit N] [--k N] [--category CAT] [--agent FirstAgent|ThirdAgent]")
      println("       retrieval flags: --hybrid [--hybrid-weight D] --temporal-boost [--temporal-halflife DAYS]")
      println("       rerank flags:    --rerank --rerank-model openai/gpt-4o-mini [--rerank-pool N]")
      println()
      println("Download MemBench from https://github.com/import-myself/Membench (see README for the external MemData download).")
      sys.exit(1)
    }
    val limit = RetrievalFlags.flagInt(args, "--limit").getOrElse(Int.MaxValue)
    val k = RetrievalFlags.flagInt(args, "--k").getOrElse(5)
    val categoryFilter = RetrievalFlags.flagString(args, "--category")
    val agent = RetrievalFlags.flagString(args, "--agent").getOrElse("FirstAgent")
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

    println("=== Sigil MemBench Benchmark ===")
    println(s"Data: $dataDir ($agent)")
    println(s"Retrieval: ${RetrievalFlags.describe(retrieval)}")
    println(s"Recall@$k")
    println()

    val cats = Categories.filter(c => categoryFilter.isEmpty || categoryFilter.contains(c))

    var totalCorrect = 0
    var totalRun = 0
    val categoryBreakdown = scala.collection.mutable.Map.empty[String, (Int, Int)]
    val startTime = System.currentTimeMillis()

    // Failures: (category, tid, question, expected sids, top-K sids).
    val failures = scala.collection.mutable.ListBuffer.empty[(String, Int, String, Set[Int], List[Int])]

    for (cat <- cats) {
      val file = new File(new File(dataDir, agent), s"$cat.json")
      if (!file.exists()) println(s"Skipping $cat — missing file: ${file.getPath}")
      else {
        println(s"\n--- $cat (${file.getName}) ---")
        val raw = Source.fromFile(file)(using Codec.UTF8).mkString
        val data = JsonParser(raw)
        // The "rec" / recommendation categories (highlevel*, lowlevel_rec)
        // use a different schema (`{movie: [...]}` with `mid`/`user`
        // turn fields) and a different task (ranking) — out of scope
        // for this retrieval-accuracy runner.
        val rolesOpt = data.asObj.value.get("roles").map(_.asVector)
        if (rolesOpt.isEmpty) {
          println(s"  Skipping $cat — schema lacks 'roles' (recommendation-category variant)")
        } else {
        val roles = rolesOpt.get.take(limit)
        println(s"  Loaded ${roles.size} roles")

        var catCorrect = 0
        var catRun = 0

        roles.zipWithIndex.foreach { case (role, roleIdx) =>
          val tid = role.get("tid").map(_.asInt).getOrElse(roleIdx)
          val messageList = role("message_list").asVector
          val qa = role("QA")
          val question = qa("question").asString
          // target_step_id is a list of [global_sid, session_idx] pairs
          // where global_sid is the turn's `sid` field (unique across
          // all sessions for this role). We index each turn by its
          // sid and match against target sids.
          val targets: Set[Int] = qa("target_step_id").asVector.flatMap { pair =>
            val p = pair.asVector
            if (p.nonEmpty) Some(p(0).asInt) else None
          }.toSet
          if (targets.nonEmpty) {
            harness.resetCollection().sync()

            val batch = scala.collection.mutable.ListBuffer.empty[(String, String, Map[String, String])]
            messageList.zipWithIndex.foreach { case (session, sessionIdx) =>
              session.asVector.zipWithIndex.foreach { case (turn, turnIdx) =>
                val sid = turn.get("sid").map(_.asInt).getOrElse(-1)
                val user = turn.get("user_message").map(_.asString).getOrElse("")
                val assist = turn.get("assistant_message").map(_.asString).getOrElse("")
                val place = turn.get("place").map(_.asString).getOrElse("")
                val time = turn.get("time").map(_.asString).getOrElse("")
                val text = s"$place ($time) USER: $user ASSISTANT: $assist".trim
                val id = s"r$tid-sid$sid"
                batch += ((id, text, Map(
                  "kind" -> "membench-turn",
                  "roleId" -> tid.toString,
                  "sid" -> sid.toString,
                  "sessionIdx" -> sessionIdx.toString,
                  "turnIdx" -> turnIdx.toString
                )))
              }
            }
            harness.embedAndIndexBatch(batch.toList).sync()

            val results = harness.searchByQueryEnhanced(question, retrieval, limit = math.max(k, 50)).sync()
            val rankedSids: List[Int] = results.flatMap(_.payload.get("sid").flatMap(_.toIntOption))
            val topK = rankedSids.take(k).toSet
            val hit = targets.exists(topK.contains)
            if (hit) { totalCorrect += 1; catCorrect += 1 }
            totalRun += 1
            catRun += 1

            if (!hit && reportPath.nonEmpty) {
              failures += ((cat, tid, question, targets, rankedSids.take(k)))
            }

            if (roleIdx % 50 == 0 || roleIdx == roles.size - 1) {
              val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
              val pct = if (totalRun > 0) totalCorrect.toDouble / totalRun * 100 else 0.0
              println(f"  [$cat $roleIdx/${roles.size}] R@$k=$pct%.1f%% ($totalCorrect/$totalRun, ${elapsed}%.0fs)")
            }
          }
        }

        categoryBreakdown(cat) = (catCorrect, catRun)
        val catPct = if (catRun > 0) catCorrect.toDouble / catRun * 100 else 0.0
        println(f"  $cat%-18s $catCorrect/$catRun ($catPct%.1f%%)")
        }  // end of rolesOpt.isDefined branch
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
      println(f"  $cat%-18s $c/$t ($pct%.1f%%)")
    }

    reportPath.foreach { path =>
      val sb = new StringBuilder
      sb.append("# Sigil MemBench Benchmark Results\n\n")
      sb.append(s"**Date:** ${java.time.Instant.now()}\n")
      sb.append(s"**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)\n")
      sb.append(s"**Agent:** $agent\n")
      sb.append(s"**Retrieval:** ${RetrievalFlags.describe(retrieval)}\n")
      sb.append(f"**Score:** $totalCorrect/$totalRun ($overall%.1f%% R@$k)\n\n")
      sb.append("## Per-category accuracy\n\n")
      sb.append("| Category | Correct | Total | Accuracy |\n|---|---|---|---|\n")
      categoryBreakdown.toList.sortBy(_._1).foreach { case (cat, (c, t)) =>
        val pct = if (t > 0) c.toDouble / t * 100 else 0.0
        sb.append(f"| $cat | $c | $t | $pct%.1f%% |\n")
      }
      sb.append(s"\n## Failures (${failures.size})\n\n")
      failures.toList.foreach { case (cat, tid, q, expected, topK) =>
        sb.append(s"### [$cat tid=$tid] `${q}`\n\n")
        sb.append(s"- expected sids: ${expected.toList.sorted.mkString(", ")}\n")
        sb.append(s"- top-${k} sids: ${topK.mkString(", ")}\n\n")
      }
      java.nio.file.Files.writeString(java.nio.file.Paths.get(path), sb.toString)
      println(s"\nReport written: $path")
    }

    System.exit(0)
  }
}
