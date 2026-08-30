package bench

import fabric.*
import fabric.io.JsonParser
import fabric.rw.*
import lightdb.id.Id
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.anthropic.AnthropicProvider

import java.io.File
import scala.io.{Codec, Source}

/**
 * Validates the local [[BenchJudge]] against a stronger reference
 * judge, so any score the local judge produced can be published with a
 * measured agreement rate rather than an assumption.
 *
 * The memory row is the only part of the benchmark corpus that needs
 * an LLM judge at all, and the benchmarks that define it specify
 * GPT-4-class judges (LongMemEval pins `gpt-4o-2024-08-06` at >97%
 * agreement with human experts). Sigil judges locally for cost. That
 * substitution is defensible in principle — judging is a comparison
 * against a supplied gold answer, not a recall task — but "defensible
 * in principle" is not a number, and this runner produces the number.
 *
 * Input is the JSONL `LongMemEvalQABench --judge-log` writes: one
 * `(question, gold, answer, localCorrect)` record per judged answer.
 * Re-judging a stored triple costs one cheap call and re-runs none of
 * the expensive part, so validating N verdicts costs roughly
 * N × (500 in + 50 out) on the reference model — about $0.004 each on
 * Opus 5.
 *
 * Reports the agreement rate plus the 2×2 breakdown, because the two
 * disagreement directions mean different things: the local judge
 * scoring answers CORRECT that the reference calls wrong inflates
 * Sigil's number, while the reverse deflates it. A high overall
 * agreement that is entirely one-directional is a biased judge, not a
 * good one.
 *
 * Usage:
 * {{{
 * sbt "benchmark/runMain bench.JudgeAgreementBench <judge-log.jsonl>
 *      [--sample N] [--judge-model anthropic/claude-opus-5] [--report PATH]"
 * }}}
 */
object JudgeAgreementBench {

  private final case class Record(index: Int,
                                  question: String,
                                  gold: String,
                                  answer: String,
                                  localCorrect: Boolean)

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val logPath = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: JudgeAgreementBench <judge-log.jsonl> [--sample N] [--judge-model ID] [--report PATH]")
      sys.exit(1)
    }
    val sample = RetrievalFlags.flagInt(args, "--sample").getOrElse(200)
    val judgeModelName = RetrievalFlags.flagString(args, "--judge-model").getOrElse("claude-opus-5")
    val reportPath = RetrievalFlags.flagString(args, "--report")

    val apiKey = sys.env.get("ANTHROPIC_API_KEY").filter(_.nonEmpty).getOrElse {
      System.err.println("ERROR: ANTHROPIC_API_KEY required for the reference judge")
      sys.exit(1)
    }

    val all = Source.fromFile(new File(logPath))(using Codec.UTF8).getLines()
      .map(_.trim).filter(_.nonEmpty)
      .map { line =>
        val j = JsonParser(line)
        Record(
          index = j("index").asInt,
          question = j("question").asString,
          gold = j("gold").asString,
          answer = j("answer").asString,
          localCorrect = j("localCorrect").asBoolean
        )
      }.toList
      // Skip records the local judge never actually decided — a judge
      // failure is a harness fault, not a verdict to agree with.
      .filter(_.answer.trim.nonEmpty)

    // Stratify so the sample carries both verdict classes in the
    // proportion the run produced. A sample drawn only from agreements
    // (the common case, if you take the head of the file) would report
    // a flattering rate that says nothing about the disputed calls.
    val (correct, incorrect) = all.partition(_.localCorrect)
    val take = math.min(sample, all.size)
    val correctShare = math.round(take.toDouble * correct.size / math.max(all.size, 1)).toInt
    val selected =
      (correct.take(correctShare) ++ incorrect.take(take - correctShare)).sortBy(_.index)

    profig.Profig("sigil.dbPath").store(s"db/bench/judge-agreement-${System.currentTimeMillis()}")
    val host = new MemoryArmsSigil
    val modelId = Model.id("anthropic", judgeModelName)
    host.setProvider(AnthropicProvider(apiKey = apiKey, sigilRef = host))
    host.cache.merge(List(anthropicModel(modelId, judgeModelName))).sync()
    host.instance.sync()
    val reference = BenchJudge(modelId)

    println("\n=== Judge agreement ===")
    println(s"log: $logPath   records: ${all.size}   sampled: ${selected.size}")
    println(s"reference judge: $judgeModelName\n")

    var agree = 0
    var localYesRefNo = 0
    var localNoRefYes = 0
    var refFailed = 0
    val disputes = scala.collection.mutable.ListBuffer.empty[String]

    selected.zipWithIndex.foreach { case (r, i) =>
      val verdict = reference.judge(host, r.question, r.gold, r.answer).sync()
      if (verdict.judgeFailed) refFailed += 1
      else if (verdict.correct == r.localCorrect) agree += 1
      else {
        if (r.localCorrect) localYesRefNo += 1 else localNoRefYes += 1
        disputes += f"- q${r.index} local=${r.localCorrect} ref=${verdict.correct} — ${verdict.reasoning.take(120)}%n" +
          f"    gold: ${r.gold.take(100)}%n    answer: ${r.answer.replaceAll("\\s+", " ").take(140)}"
      }
      if ((i + 1) % 20 == 0) println(s"  ${i + 1}/${selected.size}")
    }

    val decided = selected.size - refFailed
    val rate = if (decided == 0) 0.0 else agree.toDouble / decided
    val table =
      s"""| metric | value |
         ||---|--:|
         || records sampled | ${selected.size} |
         || reference judge | `$judgeModelName` |
         || **agreement** | **${f"${rate * 100}%.1f%%"}** (${agree}/${decided}) |
         || local correct / reference incorrect | $localYesRefNo |
         || local incorrect / reference correct | $localNoRefYes |
         || reference judge failures (excluded) | $refFailed |""".stripMargin

    println("\n=== Result ===")
    println(table)
    if (disputes.nonEmpty) {
      println("\nDisagreements:")
      disputes.foreach(println)
    }

    reportPath.foreach { p =>
      java.nio.file.Files.writeString(java.nio.file.Path.of(p),
        s"# Judge agreement\n\n$table\n\n## Disagreements\n\n${disputes.mkString("\n")}\n")
      println(s"\nreport written to $p")
    }
    host.shutdown.sync()
    System.exit(0)
  }

  /** Pricing is the published Anthropic rate so the run's cost is
    * recoverable from `Message.usage` after the fact. */
  private def anthropicModel(modelId: Id[Model], name: String): Model = Model(
    canonicalSlug = s"anthropic/$name",
    huggingFaceId = "",
    name = name,
    displayName = Some(name),
    description = "",
    contextLength = 200000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "Unknown",
      instructType = None
    ),
    pricing = ModelPricing(BigDecimal(5), BigDecimal(25), None, None),
    topProvider = ModelTopProvider(Some(64000L), None, false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    defaultParameters = ModelDefaultParameters(),
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(""),
    created = lightdb.time.Timestamp(),
    modified = lightdb.time.Timestamp(),
    _id = modelId
  )
}
