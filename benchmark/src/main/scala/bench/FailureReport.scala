package bench

import fabric.*
import fabric.io.JsonParser

import java.io.File
import scala.io.{Codec, Source}

/**
 * Turns a `--judge-log` JSONL into a failure list grouped by cause, so
 * a run's misses can be worked one at a time instead of read as 276
 * undifferentiated wrong answers.
 *
 * The grouping is the point. A failure where the gold evidence never
 * reached the prompt and a failure where it did are different bugs
 * with disjoint fixes — retriever/ingest versus rendering/prompt/model
 * — and lumping them together is what makes "improve the score" feel
 * unbounded. Classes:
 *
 *   - `retrieval-miss` — the answer session's text was not injected.
 *     Fix upstream: ingest granularity, `--memories`, fusion weights.
 *   - `comprehension-miss` — it WAS injected and the answer was still
 *     wrong. Fix downstream: how memories render, prompt framing,
 *     model choice. These are the ones worth reading individually.
 *   - `temporal-no-timestamps` — a temporal question run without
 *     session dates in memory. Structurally unanswerable; a harness
 *     defect, not a result.
 *   - `judge-no-verdict` / `harness-error` — infrastructure, never a
 *     model result.
 *
 * Emits the `--indices` list per class so the fix loop is: read a
 * class, change one thing, re-run only that class.
 *
 * Usage:
 * {{{
 * sbt "benchmark/runMain bench.FailureReport <judge-log.jsonl>
 *      [--arm sigil] [--class comprehension-miss] [--show N] [--report PATH]"
 * }}}
 */
object FailureReport {

  final private case class Rec(index: Int,
                               arm: String,
                               questionType: String,
                               question: String,
                               gold: String,
                               answer: String,
                               correct: Boolean,
                               failure: String,
                               judgeReasoning: String,
                               goldRetrieved: Boolean,
                               goldCoverage: Double,
                               injected: List[String],
                               injectedSessions: List[String],
                               answerSessions: List[String])

  def main(args: Array[String]): Unit = BenchmarkMain.guard {
    val logPath = args.find(!_.startsWith("--")).getOrElse {
      println("Usage: FailureReport <judge-log.jsonl> [--arm sigil] [--class NAME] [--show N] [--report PATH]")
      sys.exit(1)
    }
    val armFilter = RetrievalFlags.flagString(args, "--arm")
    val classFilter = RetrievalFlags.flagString(args, "--class")
    val show = RetrievalFlags.flagInt(args, "--show").getOrElse(10)
    val reportPath = RetrievalFlags.flagString(args, "--report")

    def strList(j: Json, k: String): List[String] =
      j.get(k).map(_.asVector.map(_.asString).toList).getOrElse(Nil)

    val raw = Source.fromFile(new File(logPath))(using Codec.UTF8).getLines()
      .map(_.trim).filter(_.nonEmpty).map { line =>
        val j = JsonParser(line)
        Rec(
          index = j("index").asInt,
          arm = j.get("arm").map(_.asString).getOrElse("?"),
          questionType = j.get("questionType").map(_.asString).getOrElse("?"),
          question = j.get("question").map(_.asString).getOrElse(""),
          gold = j.get("gold").map(_.asString).getOrElse(""),
          answer = j.get("answer").map(_.asString).getOrElse(""),
          correct = j.get("localCorrect").exists(_.asBoolean),
          // Records written before diagnostics existed carry no
          // `failure`; derive what we can rather than dropping them.
          failure = j.get("failure").map(_.asString).filter(_.nonEmpty).getOrElse {
            if (j.get("localCorrect").exists(_.asBoolean)) "" else "unclassified-legacy"
          },
          judgeReasoning = j.get("judgeReasoning").map(_.asString).getOrElse(""),
          goldRetrieved = j.get("goldRetrieved").exists(_.asBoolean),
          goldCoverage = j.get("goldCoverage").map(_.asDouble).getOrElse(if (j.get("goldRetrieved").exists(_.asBoolean)) 1.0 else 0.0),
          injected = strList(j, "injected"),
          injectedSessions = strList(j, "injectedSessions"),
          answerSessions = strList(j, "answerSessions")
        )
      }.toList

    // A record logged twice is a run that overlapped another; keeping
    // both would double-count. Drop duplicated indices entirely rather
    // than guess which run owns them — an unbiased subset beats a
    // contaminated whole.
    val byArm = raw.filter(r => armFilter.forall(_ == r.arm))
    val counts = byArm.groupBy(r => (r.arm, r.index)).view.mapValues(_.size).toMap
    val dupes = counts.count(_._2 > 1)
    val recs = byArm.filter(r => counts((r.arm, r.index)) == 1)

    val failures = recs.filterNot(_.correct)
    val grouped = failures.groupBy(_.failure)

    val sb = new StringBuilder
    // Console gets the summary; the file gets the summary AND every
    // failure in full. A dossier you can read months later beats a
    // terminal scrollback, and the per-question detail is the whole
    // point of keeping it.
    def out(s: String): Unit = { println(s); sb.append(s).append('\n') }
    def file(s: String): Unit = sb.append(s).append('\n')

    out(s"# Failure report — $logPath")
    out("")
    out(s"records: ${recs.size} scored" +
      (if (dupes > 0) s"  (excluded $dupes duplicated index(es) from an overlapping run)" else "") +
      s"   correct: ${recs.count(_.correct)}   failed: ${failures.size}")
    out("")
    out("| failure class | n | share of failures | fix lives in |")
    out("|---|--:|--:|---|")
    grouped.toList.sortBy(-_._2.size).foreach { case (cls, rs) =>
      val where = cls match {
        case "retrieval-miss" => "ranking — fusion weights, query composition"
        case "retrieval-partial" => "injection budget — --memories, ingest granularity"
        case "comprehension-miss" => "memory rendering, prompt framing, model"
        case "temporal-no-timestamps" => "harness — run with timestamps"
        case "judge-no-verdict" => "judge (infrastructure, not a result)"
        case "harness-error" => "harness (infrastructure, not a result)"
        case _ => "unclassified — re-run to classify"
      }
      out(f"| $cls | ${rs.size} | ${rs.size * 100.0 / math.max(failures.size, 1)}%.1f%% | $where |")
    }

    out("")
    out("## By category × class")
    out("")
    val cats = failures.map(_.questionType).distinct.sorted
    val classes = grouped.keys.toList.sorted
    out("| category | " + classes.mkString(" | ") + " |")
    out("|---" * (classes.size + 1) + "|")
    cats.foreach { c =>
      val row = classes.map(cl => failures.count(r => r.questionType == c && r.failure == cl).toString)
      out(s"| $c | ${row.mkString(" | ")} |")
    }

    out("")
    out("## Re-run lists")
    out("")
    grouped.toList.sortBy(-_._2.size).foreach { case (cls, rs) =>
      out(s"**$cls** (${rs.size}) — `--indices ${rs.map(_.index).sorted.mkString(",")}`")
      out("")
    }

    classFilter.foreach { cls =>
      out(s"## $cls — first $show in detail")
      out("")
      grouped.getOrElse(cls, Nil).sortBy(_.index).take(show).foreach { r =>
        out(s"### q${r.index} — ${r.questionType}")
        out(s"- **question**: ${r.question}")
        out(s"- **gold**: ${r.gold}")
        out(s"- **answered**: ${r.answer.replaceAll("\\s+", " ").take(400)}")
        out(s"- **judge said**: ${r.judgeReasoning}")
        out(s"- **gold retrieved**: ${r.goldRetrieved}  " +
          s"(answer sessions ${r.answerSessions.mkString(",")} / injected from ${r.injectedSessions.mkString(",")})")
        if (r.injected.nonEmpty) {
          out(s"- **evidence the model read**:")
          r.injected.foreach(t => out(s"    - ${t.replaceAll("\\s+", " ").take(220)}"))
        }
        out("")
      }
    }

    // Full dossier: every failure, grouped by class then category, with
    // the question, the gold answer, what was actually answered, why the
    // judge rejected it, and the evidence the model was holding.
    if (reportPath.isDefined) {
      file("")
      file("---")
      file("")
      file("# Every failure, in detail")
      file("")
      grouped.toList.sortBy(-_._2.size).foreach { case (cls, rs) =>
        file(s"## $cls (${rs.size})")
        file("")
        rs.groupBy(_.questionType).toList.sortBy(-_._2.size).foreach { case (cat, catRs) =>
          file(s"### $cat (${catRs.size})")
          file("")
          catRs.sortBy(_.index).foreach { r =>
            file(s"#### q${r.index}")
            file("")
            file(s"**Question**: ${r.question}")
            file("")
            file(s"**Gold answer**: ${r.gold}")
            file("")
            file(s"**Model answered**:")
            file("")
            file("> " +
              (if (r.answer.trim.isEmpty) "_(empty)_"
               else r.answer.trim.replaceAll("\r?\n", "\n> ")))
            file("")
            if (r.judgeReasoning.nonEmpty) { file(s"**Judge**: ${r.judgeReasoning}"); file("") }
            file(f"**Gold coverage**: ${r.goldCoverage * 100}%.0f%% of answer sessions" +
              (if (r.answerSessions.nonEmpty || r.injectedSessions.nonEmpty)
                 s" — answer session(s) `${r.answerSessions.mkString(", ")}`, injected from `${r.injectedSessions.mkString(", ")}`"
               else ""))
            file("")
            if (r.injected.nonEmpty) {
              file("<details><summary>Evidence the model read</summary>")
              file("")
              r.injected.zipWithIndex.foreach { case (t, i) =>
                file(s"${i + 1}. ${t.trim.replaceAll("\r?\n", " ")}")
              }
              file("")
              file("</details>")
              file("")
            }
          }
        }
      }
    }

    reportPath.foreach { p =>
      java.nio.file.Files.writeString(java.nio.file.Path.of(p), sb.toString)
      println(s"\nfull dossier (${failures.size} failures) written to $p")
    }
    System.exit(0)
  }
}
