package bench

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.db.Model
import sigil.tool.consult.ConsultTool

/**
 * Shared LLM judge for the benchmarks whose ground truth is a natural-
 * language answer rather than an executable end state — the memory-QA
 * row (LongMemEval, LoCoMo, MemoryAgentBench). Everything else in the
 * corpus verifies programmatically (BFCL's AST checker, AgentDojo's
 * predicates, τ²-bench's final database state) and needs no judge.
 *
 * The judgment runs through [[ConsultTool.invoke]] against
 * [[JudgeVerdictTool]], so the verdict arrives as a typed value rather
 * than prose a mid-size local model might format three different ways.
 *
 * **Judging is not answering.** The judge is handed the gold answer and
 * asked only whether the candidate conveys it — a comparison, not a
 * recall task — which is why a local model can plausibly stand in for
 * the GPT-4-class judges the benchmarks specify (LongMemEval pins
 * `gpt-4o-2024-08-06` at >97% human agreement). That "plausibly" is a
 * claim to be measured, not assumed — the planned validation replays a
 * sample of verdicts through a stronger judge and publishes the
 * agreement rate beside any score this judge produced (see
 * `design/benchmark-corpus.md`, decision 3). Until that lands, treat
 * local-judge scores as provisional.
 *
 * A consult that fails or returns nothing scores the answer INCORRECT
 * and flags `judgeFailed`, so a flaky judge shows up as a visible
 * deduction rather than silently inflating or deflating a run.
 */
case class BenchJudge(modelId: Id[Model]) {

  def judge(sigil: Sigil, question: String, goldAnswer: String, response: String): Task[JudgeResult] = {
    val candidate = response.trim
    if (candidate.isEmpty)
      Task.pure(JudgeResult(correct = false, reasoning = "empty response", judgeFailed = false))
    else
      ConsultTool.invoke[JudgeVerdictInput](
        sigil = sigil,
        modelId = modelId,
        chain = Nil,
        systemPrompt = BenchJudge.SystemPrompt,
        userPrompt =
          s"""Question:
             |$question
             |
             |Gold answer:
             |$goldAnswer
             |
             |Candidate response:
             |$candidate""".stripMargin,
        tool = JudgeVerdictTool,
        generationSettings = JudgeVerdictTool.consultSettings
      ).map {
        case Some(v) => JudgeResult(correct = v.correct, reasoning = v.reasoning.trim, judgeFailed = false)
        case None    => JudgeResult(correct = false, reasoning = "judge returned no verdict", judgeFailed = true)
      }.handleError { e =>
        Task.pure(JudgeResult(correct = false, reasoning = s"judge error: ${e.getMessage}", judgeFailed = true))
      }
  }
}

object BenchJudge {
  val SystemPrompt: String =
    """You grade a candidate response against a gold answer via the `judge_verdict` tool.
      |
      |Mark it correct when the response conveys the gold answer's substance. Different
      |wording, extra detail, and a conversational frame do not make it wrong; a number or
      |name that matches the gold answer is correct even if the surrounding sentence differs.
      |
      |Mark it incorrect when the response contradicts the gold answer, omits the specific
      |fact the question asked for, answers a different question, or declines to answer
      |("I don't know", "no information available"). When the gold answer is a specific value,
      |the response must contain that value or an unambiguous paraphrase of it.
      |
      |Judge only the substance. Do not reward confidence or penalize hedging that still
      |carries the right answer.""".stripMargin
}

/** One judgment. `judgeFailed` distinguishes "the judge said no" from
  * "the judge did not answer" — the second is a harness problem and is
  * reported separately so it can't masquerade as a model result. */
case class JudgeResult(correct: Boolean, reasoning: String, judgeFailed: Boolean)
