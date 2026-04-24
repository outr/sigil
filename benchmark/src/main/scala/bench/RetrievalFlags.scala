package bench

import rapid.Task
import sigil.Sigil
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.vector.{LLMReranker, TemporalBoost}
import lightdb.id.Id

/**
 * Shared CLI flag handling for retrieval enhancements. Each
 * benchmark runner calls [[fromArgs]] at startup; the returned
 * `Retrieval` feeds `harness.searchByQueryEnhanced`.
 *
 * Flags:
 *   --hybrid                       enable keyword-hybrid scoring (α = 0.7)
 *   --hybrid-weight D              override α (0.0 = pure keyword, 1.0 = pure semantic)
 *   --temporal-boost               enable temporal proximity reranker
 *   --temporal-halflife DAYS       override halflife (default 7 days)
 *   --temporal-weight D            override weight share (default 0.3)
 *   --rerank                       enable LLM reranker (requires --rerank-model)
 *   --rerank-model MODEL           OpenAI model id (e.g. `openai/gpt-4o-mini`)
 *   --rerank-pool N                candidate pool size (default 20)
 */
object RetrievalFlags {

  def fromArgs(args: Array[String],
               harness: BenchmarkHarness,
               sigilForRerank: => Sigil,
               chain: List[ParticipantId]): Retrieval = {
    val hybrid = if (args.contains("--hybrid") || args.contains("--hybrid-weight")) {
      val w = flagDouble(args, "--hybrid-weight").getOrElse(0.7)
      Some(Retrieval.Hybrid(semanticWeight = w))
    } else None

    val temporal = if (args.contains("--temporal-boost") || args.contains("--temporal-halflife") || args.contains("--temporal-weight")) {
      val days = flagLong(args, "--temporal-halflife").getOrElse(7L)
      val w = flagDouble(args, "--temporal-weight").getOrElse(0.3)
      Some(Retrieval.Temporal(TemporalBoost(halfLifeMs = days * TemporalBoost.HalfLife.OneDay, temporalWeight = w)))
    } else None

    val rerank = if (args.contains("--rerank") || args.contains("--rerank-model") || args.contains("--rerank-pool")) {
      val modelStr = flagString(args, "--rerank-model").getOrElse {
        System.err.println("ERROR: --rerank requires --rerank-model <provider/model> (e.g. openai/gpt-4o-mini)")
        sys.exit(1)
      }
      val poolSize = flagInt(args, "--rerank-pool").getOrElse(20)
      val reranker = LLMReranker(modelId = Id[Model](modelStr), chain = chain, maxCandidates = poolSize)
      Some(Retrieval.Rerank(reranker, sigilForRerank, poolSize))
    } else None

    Retrieval(hybrid = hybrid, temporal = temporal, rerank = rerank)
  }

  /** Concise one-line summary of enabled retrieval modes for console
    * output — benchmark runners print this at startup so the
    * result banner is self-describing. */
  def describe(retrieval: Retrieval): String = {
    val parts = List(
      retrieval.hybrid.map(h => f"hybrid(α=${h.semanticWeight}%.2f)"),
      retrieval.temporal.map(t => s"temporal(halfLife=${t.boost.halfLifeMs / TemporalBoost.HalfLife.OneDay}d, w=${t.boost.temporalWeight})"),
      retrieval.rerank.map(r => s"rerank(pool=${r.poolSize})")
    ).flatten
    if (parts.isEmpty) "vanilla cosine" else parts.mkString(" + ")
  }

  def flagInt(args: Array[String], name: String): Option[Int] =
    args.indexOf(name) match {
      case -1 => None
      case i  => args.lift(i + 1).flatMap(_.toIntOption)
    }

  def flagLong(args: Array[String], name: String): Option[Long] =
    args.indexOf(name) match {
      case -1 => None
      case i  => args.lift(i + 1).flatMap(_.toLongOption)
    }

  def flagDouble(args: Array[String], name: String): Option[Double] =
    args.indexOf(name) match {
      case -1 => None
      case i  => args.lift(i + 1).flatMap(_.toDoubleOption)
    }

  def flagString(args: Array[String], name: String): Option[String] =
    args.indexOf(name) match {
      case -1 => None
      case i  => args.lift(i + 1)
    }
}
