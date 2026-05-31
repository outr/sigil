package sigil.tool.output

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.Model
import sigil.provider.{GenerationSettings, ReasoningMode, SummarizationWork}
import sigil.tool.consult.{ConsultTool, SummarizationInput, SummarizationTool}
import sigil.tool.{TextToolOutput, Tool, ToolContext, ToolExample, ToolName, ToolResult}

/**
 * `summarize_output` — the on-demand, LLM-derived summary rung of the
 * bulk-output ladder (sigil #336). The agent hands it a *reference* (a
 * prior grep / glob / bulk result's `callId`) and gets back a short
 * overview, WITHOUT the full result ever entering its context: this tool
 * reads the materialized container itself and map-reduces it on a cheap
 * summarization-tier model.
 *
 * It's the deliberate-cost step between the free templated stats on the
 * first page and acting on the set by reference (`dispatch_workers` /
 * `filter_container`) — used when the stats aren't enough to decide the
 * next move, in place of paging the whole result into context.
 */
case object SummarizeOutputTool extends Tool {
  type Input  = SummarizeOutputInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[SummarizeOutputInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("summarize_output")
  val description =
    """Produce a concise, LLM-derived summary of a prior bulk tool result (grep / glob / …) BY REFERENCE,
      |without pulling the full result into your context. Pass `reference` = that result's `callId`; the full
      |set is read and map-reduced on a cheap model and you get back a short overview. Use this when the
      |first-page stats aren't enough to decide your next step — instead of paging through the whole result.
      |`focus` steers it (e.g. "which files have the most matches", "categorize these"). To ACT on the set,
      |hand the same reference to `dispatch_workers` / `filter_container`; to inspect specific entries, use
      |`query_tool_output`.""".stripMargin
  override val keywords = Set("summarize", "summary", "overview", "digest", "reference", "bulk", "results")
  override val examples = List(
    ToolExample(
      "Summarize a grep result by reference",
      SummarizeOutputInput(reference = "<grep callId>", focus = Some("which files have the most matches"))
    )
  )

  /** Char budget per summarization chunk (~3k tokens at 4 chars/token).
    * Rows are rendered and split into chunks of at most this size, so even
    * a large container never lands in a single prompt. */
  private val ChunkChars = 12000

  private val consultSettings =
    GenerationSettings(maxOutputTokens = Some(600), temperature = Some(0.0), reasoningMode = ReasoningMode.Off)

  override def executeResult(input: SummarizeOutputInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    ToolOutputReference.resolve(ctx.sigil, ctx.conversation.id, input.reference, input.conversationId).flatMap {
      case Left(reason) =>
        Task.pure(ToolResult.failure(
          message = s"summarize_output: $reason",
          hint    = Some("`reference` is a prior grep / glob (or other bulk tool) result's `callId`.")
        ))
      case Right(resolved) if resolved.rows.isEmpty =>
        Task.pure(ToolResult.success(TextToolOutput("(the referenced result is empty — nothing to summarize)")))
      case Right(resolved) =>
        val rendered = resolved.rows.map(r => JsonFormatter.Compact(r.payload))
        val chunks   = chunk(rendered, ChunkChars)
        ctx.sigil.routedModelFor(SummarizationWork, ctx.chain, fallback = ctx.modelId)
          .handleError(_ => Task.pure(ctx.modelId))
          .flatMap { model =>
            summarizeChunks(ctx.sigil, model, ctx, chunks, input.focus, resolved.rows.size)
              .map(s => ToolResult.success(TextToolOutput(s)))
          }
    }

  /** Group rendered rows into chunks bounded by `budget` chars. */
  private def chunk(lines: List[String], budget: Int): List[String] = {
    val out = scala.collection.mutable.ListBuffer.empty[String]
    val cur = new StringBuilder
    lines.foreach { line =>
      if (cur.nonEmpty && cur.length + line.length + 1 > budget) {
        out += cur.toString; cur.clear()
      }
      if (cur.nonEmpty) cur.append('\n')
      cur.append(line)
    }
    if (cur.nonEmpty) out += cur.toString
    out.toList
  }

  private def systemPrompt(focus: Option[String]): String =
    "You are summarizing a set of tool-output records (search matches, file listings, etc.). Produce a " +
      "faithful, concise overview an agent can use to decide its next step: how many records, what kinds, " +
      "notable groupings (files / paths), and anything actionable. Summarize ONLY what's present; do not " +
      "invent." + focus.map(f => s" Focus on: $f").getOrElse("")

  private def summarizeOne(host: Sigil,
                           model: Id[Model],
                           ctx: ToolContext,
                           text: String,
                           focus: Option[String]): Task[String] =
    ConsultTool.invoke[SummarizationInput](
      sigil              = host,
      modelId            = model,
      chain              = ctx.chain,
      systemPrompt       = systemPrompt(focus),
      userPrompt         = text,
      tool               = SummarizationTool,
      generationSettings = consultSettings
    ).map(_.map(_.summary).getOrElse("(summary unavailable for a slice of the result)"))

  /** Map each chunk to a partial summary, then reduce to one. The full
    * set never lands in a single prompt nor in the agent's context. */
  private def summarizeChunks(host: Sigil,
                              model: Id[Model],
                              ctx: ToolContext,
                              chunks: List[String],
                              focus: Option[String],
                              total: Int): Task[String] =
    chunks match {
      case Nil           => Task.pure(s"$total records.")
      case single :: Nil => summarizeOne(host, model, ctx, single, focus).map(s => s"$total records. $s")
      case many =>
        Task.sequence(many.map(c => summarizeOne(host, model, ctx, c, focus))).flatMap { partials =>
          summarizeOne(
            host, model, ctx,
            "Partial summaries of disjoint slices of one result set:\n\n" + partials.mkString("\n---\n"),
            Some("combine these partial summaries into one faithful overview" + focus.map(f => s"; $f").getOrElse(""))
          ).map(s => s"$total records across ${many.size} slices. $s")
        }
    }
}
