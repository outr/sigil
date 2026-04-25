package bench

import fabric.*
import fabric.io.JsonParser
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.Model
import sigil.provider.{OneShotRequest, ProviderEvent}

import java.io.File
import scala.io.{Codec, Source}

/**
 * BFCL v4 runner — Berkeley Function Calling Leaderboard.
 *
 * Starts with the `simple_python` category (~400 single-function
 * single-call tests). Each test case pairs a question with one tool
 * spec; the model is expected to emit exactly one tool call
 * matching the ground-truth function name + param values.
 *
 * Scoring is AST-equivalent: model's args are a JSON object,
 * ground-truth lists each param's allowed values; a call matches
 * if every model arg is in the allowed set for that param.
 *
 * Usage:
 *   sbt "benchmark/runMain bench.BFCLBench <bfcl-data-dir> --model openai/gpt-4o-mini
 *        [--category simple_python] [--limit N]"
 *
 * `<bfcl-data-dir>` holds the BFCL_v4_*.json test files + a
 * `possible_answer/` sub-folder with ground truth. Download from
 * https://github.com/ShishirPatil/gorilla/tree/main/berkeley-function-call-leaderboard/bfcl_eval/data
 */
object BFCLBench {

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val dataDir = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: BFCLBench <bfcl-data-dir> --model MODEL [--category CAT] [--limit N]")
      sys.exit(1)
    }
    val modelStr = RetrievalFlags.flagString(args, "--model").getOrElse("openai/gpt-4o-mini")
    val category = RetrievalFlags.flagString(args, "--category").getOrElse("simple_python")
    val limit = RetrievalFlags.flagInt(args, "--limit").getOrElse(Int.MaxValue)
    // Failures always logged at end-of-run unless explicitly silenced.
    val dumpFailures = !args.contains("--no-dump-failures")
    val reportPath = RetrievalFlags.flagString(args, "--report")

    val testFile = new File(dataDir, s"BFCL_v4_$category.json")
    val answerFile = new File(new File(dataDir, "possible_answer"), s"BFCL_v4_$category.json")
    if (!testFile.exists() || !answerFile.exists()) {
      System.err.println(s"ERROR: missing ${testFile.getPath} or ${answerFile.getPath}")
      sys.exit(1)
    }

    val tests = loadJsonl(testFile).take(limit)
    val answersById: Map[String, Json] =
      loadJsonl(answerFile).map(a => a("id").asString -> a("ground_truth")).toMap

    println("=== Sigil BFCL Benchmark ===")
    println(s"Data: ${dataDir}")
    println(s"Category: $category")
    println(s"Model: $modelStr")
    println(s"Cases: ${tests.size}")
    println()

    // BenchmarkSigil — reuse the OpenAI-backed sigil used for the
    // rerank path in the memory benchmarks, but with no vector index
    // (tool-use benchmarks never search memory).
    val benchSigil = BenchmarkSigil.multiProvider(
      embeddingProvider = _root_.sigil.embedding.NoOpEmbeddingProvider,
      vectorIndex = _root_.sigil.vector.NoOpVectorIndex
    )
    val modelId = Id[Model](modelStr)

    var correct = 0
    var total = 0
    var parseFail = 0
    val failures = scala.collection.mutable.ListBuffer.empty[(String, String, Option[(String, Json)], Json)]
    val startTime = System.currentTimeMillis()

    tests.zipWithIndex.foreach { case (test, idx) =>
      val testId = test("id").asString
      val questions = test("question").asVector
      // `question` is [[{role, content}]]; simple category has one
      // turn with one user message. Concatenate any system/user
      // messages into the prompt.
      val firstTurn = questions.headOption.map(_.asVector).getOrElse(Vector.empty)
      // BFCL's official OpenAI FC handler passes the test entry's
      // messages straight through with no injected system prompt. Do
      // the same: empty string when the dataset doesn't supply one.
      val systemPrompt = firstTurn.collectFirst {
        case m if m("role").asString == "system" => m("content").asString
      }.getOrElse("")
      val userPrompt = firstTurn.collectFirst {
        case m if m("role").asString == "user" => m("content").asString
      }.getOrElse("")

      val functionSpec = test("function").asVector.head
      val originalName = functionSpec("name").asString
      val requiredParams: Set[String] = functionSpec("parameters").asObj.value
        .get("required").map(_.asVector.map(_.asString).toSet).getOrElse(Set.empty)
      // OpenAI rejects dots in tool names (pattern ^[a-zA-Z0-9_-]+$);
      // BFCL uses `math.factorial`, `math.hypot`, etc. Map dots to
      // underscores on the wire and un-map at scoring time.
      val wireName = originalName.replace('.', '_')
      val tool = DynamicTool(
        toolName = wireName,
        toolDescription = functionSpec("description").asString,
        paramsDefinition = BFCLSchemaParser.parse(functionSpec("parameters"))
      )

      val request = OneShotRequest(
        modelId = modelId,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        tools = Vector(tool),
        chain = Nil
      )

      val (result, observedCall) = runCaseVerbose(benchSigil, request, originalName, answersById.get(testId))
      total += 1
      result match {
        case Right(true)  => correct += 1
        case Right(false) =>
          if (dumpFailures) failures += ((testId, "wrong-args", observedCall, answersById(testId)))
        case Left(e) =>
          parseFail += 1
          if (dumpFailures) failures += ((testId, s"parse: ${e.getMessage.take(120)}", observedCall, answersById.getOrElse(testId, obj())))
      }

      result match {
        case Left(e) => println(s"  $testId: ${e.getMessage}")
        case _       => ()
      }
      if (idx % 25 == 0 || idx == tests.size - 1) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val pct = if (total > 0) correct.toDouble / total * 100 else 0.0
        println(f"[$idx/${tests.size}] $testId — ${if (result == Right(true)) "✓" else "✗"} | " +
          f"running: $correct/$total (${pct}%.1f%%) parseFail=$parseFail | ${elapsed}%.0fs")
      }
    }

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    println()
    println("=== Results ===")
    val accPct = if (total > 0) correct.toDouble / total * 100 else 0.0
    println(f"Accuracy: $correct/$total ($accPct%.1f%%)")
    println(f"Parse failures (no tool call emitted): $parseFail")
    println(f"Time: ${elapsed}%.0fs")

    if (dumpFailures && failures.nonEmpty) {
      println()
      println(s"=== Failures (${failures.size}) ===")
      failures.foreach { case (id, kind, obs, gt) =>
        println(s"--- $id ($kind) ---")
        obs match {
          case Some((n, args)) => println(s"  observed: $n(${fabric.io.JsonFormatter.Compact(args)})")
          case None            => println(s"  observed: <no tool call>")
        }
        println(s"  ground_truth: ${fabric.io.JsonFormatter.Compact(gt)}")
      }
    }

    reportPath.foreach { path =>
      val sb = new StringBuilder
      sb.append("# Sigil BFCL Benchmark Results\n\n")
      sb.append(s"**Date:** ${java.time.Instant.now()}\n")
      sb.append(s"**Pipeline:** Sigil (Provider abstraction + BFCLScorer port of `ast_checker.py`)\n")
      sb.append(s"**Model:** $modelStr\n")
      sb.append(s"**Category:** $category\n")
      sb.append(f"**Score:** $correct/$total ($accPct%.1f%% accuracy, $parseFail no-tool-call)\n\n")
      sb.append(s"## Failures (${failures.size})\n\n")
      failures.toList.foreach { case (id, kind, obs, gt) =>
        sb.append(s"### `$id` — $kind\n\n")
        obs match {
          case Some((n, a)) => sb.append(s"- observed: `$n(${fabric.io.JsonFormatter.Compact(a)})`\n")
          case None         => sb.append("- observed: _no tool call_\n")
        }
        sb.append(s"- ground_truth: `${fabric.io.JsonFormatter.Compact(gt)}`\n\n")
      }
      java.nio.file.Files.writeString(java.nio.file.Paths.get(path), sb.toString)
      println(s"\nReport written: $path")
    }

    System.exit(0)
  }

  private def runCaseVerbose(sigil: Sigil,
                             request: OneShotRequest,
                             expectedToolName: String,
                             groundTruthOpt: Option[Json]): (Either[Throwable, Boolean], Option[(String, Json)]) = {
    try {
      val events = sigil.providerFor(request.modelId, Nil).flatMap(_.apply(request).toList).sync()
      val toolCall = events.collectFirst {
        case ProviderEvent.ToolCallComplete(_, input: DynamicToolInput) => input
      }
      // Which tool name did sigil route to — helpful when we see
      // funny arg mismatches.
      val observedName = events.collectFirst {
        case ProviderEvent.ToolCallStart(_, name) => name
      }
      val observed = (observedName, toolCall) match {
        case (Some(n), Some(input)) => Some(n -> input.args)
        case _                      => None
      }
      val result = groundTruthOpt match {
        case None => Right(false)
        case Some(gt) => toolCall match {
          case None =>
            val summary = events.collect {
              case ProviderEvent.ToolCallStart(_, name) => s"ToolCallStart($name)"
              case ProviderEvent.ToolCallComplete(_, input) => s"ToolCallComplete(${input.getClass.getSimpleName})"
              case ProviderEvent.ContentBlockDelta(_, t) if t.nonEmpty => s"ContentBlockDelta(${t.take(80)})"
              case ProviderEvent.TextDelta(t) if t.nonEmpty => s"TextDelta(${t.take(80)})"
              case ProviderEvent.Error(msg) => s"Error($msg)"
              case ProviderEvent.Done(sr) => s"Done($sr)"
            }.mkString(" | ")
            Left(new RuntimeException(s"no tool call; events: $summary"))
          case Some(input) => Right(BFCLScorer.scoreSimple(expectedToolName -> input.args, gt))
        }
      }
      (result, observed)
    } catch {
      case e: Throwable => (Left(e), None)
    }
  }


  private def loadJsonl(file: File): List[Json] = {
    val src = Source.fromFile(file)(Codec.UTF8)
    try src.getLines().filter(_.trim.nonEmpty).map(JsonParser(_)).toList
    finally src.close()
  }
}
