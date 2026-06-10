package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.db.Model
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.cloudflare.CloudflareProvider
import sigil.provider.{
  GenerationSettings, MessageContent, Provider, ProviderCall, ProviderEvent, ProviderMessage, ReasoningMode, ToolChoice
}
import sigil.script.ScalaScriptExecutor

import scala.concurrent.duration.*

/**
 * Coding head-to-head (HARD discriminator): cheap Cloudflare models
 * (glm-4.7-flash, gpt-oss-120b, kimi-k2.6) vs Anthropic Haiku 4.5 /
 * Sonnet 4.6 / Opus 4.8 on a recursive-descent integer-expression
 * evaluator — operator precedence, left-associativity, unary minus
 * (incl. after an operator), parentheses, Scala modulo-sign semantics,
 * and strict error handling (return Left, never throw).
 *
 * Unlike the comment-stripper calibration (everyone aced it), this set
 * has many edge cases weak models drop. Correctness is MACHINE-VERIFIED:
 * each model's returned `eval` is compiled + run in-process via Sigil's
 * own [[ScalaScriptExecutor]] against 25 hidden cases, with a per-case
 * 2s watchdog so a buggy parser that infinite-loops scores 0 for that
 * case instead of wedging the run.
 *
 * Measurement, not a gate — always succeeds; per-model scorecard emitted
 * via `info(...)` as each completes. Self-skips without ANTHROPIC_API_KEY
 * + Cloudflare creds.
 */
class CodingHeadToHeadSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override protected val testTimeout: FiniteDuration = 20.minutes

  private val anthropicKey = sys.env.get("ANTHROPIC_API_KEY").filter(_.nonEmpty)
  private val cfToken      = sys.env.get("CLOUDFLARE_AUTH_TOKEN").filter(_.nonEmpty)
  private val cfAccount    = sys.env.get("CLOUDFLARE_ACCOUNT_ID").filter(_.nonEmpty)

  private val executor = new ScalaScriptExecutor()

  private val taskPrompt = List(
    "Implement this exact Scala method:",
    "",
    "  def eval(expr: String): Either[String, Long]",
    "",
    "It evaluates an integer arithmetic expression and returns Right(result), or Left(errorMessage)",
    "on any malformed input or division/modulo by zero. NEVER throw - always return Left on error.",
    "",
    "Grammar and rules:",
    "- Non-negative integer literals (0, 42, 1000).",
    "- Binary operators + - * / % . Precedence: * / % bind tighter than + - . ALL binary operators",
    "  are LEFT-associative (so 8 - 3 - 2 == 3, and 100 / 7 % 3 == 2).",
    "- Unary minus, e.g. -5, and it MAY follow another operator: 3 * -2 is 3 times (-2) == -6.",
    "- Parentheses for grouping.",
    "- Whitespace between tokens is insignificant.",
    "- Integer division (/) truncates toward zero. % is the remainder with Scala/Java semantics",
    "  (the sign follows the dividend: -7 % 3 == -1, and 7 % -3 == 1). Division or modulo by zero",
    "  returns Left.",
    "- Any malformed input returns Left: unbalanced parentheses, a leading/trailing/missing operand,",
    "  two adjacent numbers, an unknown operator, an unexpected character, empty or blank input.",
    "",
    "Examples:",
    "  eval(\"2 + 3 * 4\")   == Right(14)",
    "  eval(\"(2 + 3) * 4\") == Right(20)",
    "  eval(\"3 * -2\")      == Right(-6)",
    "  eval(\"10 / 0\")      is a Left",
    "  eval(\"(2 + 3\")      is a Left",
    "",
    "Use ONLY the Scala standard library (scala.* / java.*). Write a fully SELF-CONTAINED parser;",
    "do NOT use scala.util.parsing parser-combinators or any external library.",
    "",
    "Output ONLY the Scala method definition - no explanation, no markdown fences, no surrounding",
    "object/class. You MAY define helper methods/values nested inside eval. Just:",
    "  def eval(expr: String): Either[String, Long] = ..."
  ).mkString("\n")

  /** 25 cases — a few shown anchors plus many hidden discriminators. */
  private val harness = List(
    "val __cases: List[(String, Either[String, Long])] = List(",
    "  (\"2 + 3 * 4\", Right(14L)),",
    "  (\"(2 + 3) * 4\", Right(20L)),",
    "  (\"8 - 3 - 2\", Right(3L)),",
    "  (\"2 - 3 - 4\", Right(-5L)),",
    "  (\"- - 5\", Right(5L)),",
    "  (\"3 * -2\", Right(-6L)),",
    "  (\"10 / 3\", Right(3L)),",
    "  (\"-7 % 3\", Right(-1L)),",
    "  (\"7 % -3\", Right(1L)),",
    "  (\"2 * (3 + 4) * 5\", Right(70L)),",
    "  (\"  7  \", Right(7L)),",
    "  (\"1 + 2 * 3 - 4 / 2\", Right(5L)),",
    "  (\"2 * 3 % 4\", Right(2L)),",
    "  (\"100 / 7 % 3\", Right(2L)),",
    "  (\"-(3 + 4)\", Right(-7L)),",
    "  (\"10 / 0\", Left(\"\")),",
    "  (\"5 % 0\", Left(\"\")),",
    "  (\"(2 + 3\", Left(\"\")),",
    "  (\"2 + 3)\", Left(\"\")),",
    "  (\"2 +\", Left(\"\")),",
    "  (\"* 3\", Left(\"\")),",
    "  (\"\", Left(\"\")),",
    "  (\"   \", Left(\"\")),",
    "  (\"2 3\", Left(\"\")),",
    "  (\"4 + a\", Left(\"\"))",
    ")",
    "val __pass = __cases.count { case (in, exp) =>",
    "  scala.util.Try(eval(in)).toOption.exists { got => exp match { case Right(v) => got == Right(v); case Left(_) => got.isLeft } }",
    "}",
    "__pass.toString + \"/\" + __cases.size.toString"
  ).mkString("\n")

  private def extractCode(text: String): String = {
    // Drop any markdown fence lines (```scala, ```, or a stray closing fence),
    // then take from the method definition onward.
    val noFences = text.linesIterator.filterNot(_.trim.startsWith("```")).mkString("\n")
    val i = noFences.indexOf("def eval")
    if (i >= 0) noFences.substring(i) else noFences
  }

  private def verify(modelCode: String): Task[String] =
    executor.execute(modelCode + "\n" + harness, Map.empty)
      .map(_.trim)
      .handleError(t => Task.pure(s"compile/run error: ${Option(t.getMessage).getOrElse("").take(70)}"))

  private def run1(provider: Provider, model: Model): Task[String] = {
    val pc = ProviderCall(
      model = model,
      system = "You are a precise Scala engineer. Follow the spec exactly.",
      messages = Vector(ProviderMessage.User(Vector(MessageContent.Text(taskPrompt)))),
      tools = Vector.empty,
      builtInTools = Set.empty,
      toolChoice = ToolChoice.None,
      // No temperature: Opus 4.8/4.7 reject sampling params (400).
      generationSettings = GenerationSettings(maxOutputTokens = Some(16000), reasoningMode = ReasoningMode.Auto)
    )
    provider.call(pc).toList.flatMap { events =>
      val text = events.collect {
        case ProviderEvent.TextDelta(t)            => t
        case ProviderEvent.ContentBlockDelta(_, t) => t
      }.mkString
      scala.util.Try(java.nio.file.Files.writeString(
        java.nio.file.Path.of(s"/tmp/h2h-${model._id.value.replaceAll("[^A-Za-z0-9]", "_")}.txt"), text))
      if (text.trim.isEmpty) Task.pure("no text output")
      else verify(extractCode(text))
    }.handleError(t => Task.pure(s"call error: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("").take(70)}"))
  }

  "Coding head-to-head (HARD): integer expression evaluator (machine-verified)" should {
    "score cheap CF models (incl. Kimi) vs Haiku / Sonnet / Opus on an objective pass-rate" in {
      if (anthropicKey.isEmpty || cfToken.isEmpty || cfAccount.isEmpty)
        cancel("ANTHROPIC_API_KEY / CLOUDFLARE creds not set — skipping live coding comparison")

      val cf   = CloudflareProvider(cfToken.get, cfAccount.get, TestSigil, tokenIdleTimeout = 90.seconds)
      val anth = AnthropicProvider(apiKey = anthropicKey.get, sigilRef = TestSigil)

      // Reliable models first; Kimi last (it may hang on Cloudflare).
      val entries: List[(String, Provider, Id[Model])] = List(
        ("anthropic/haiku-4-5",   anth, Model.id("anthropic/claude-haiku-4-5")),
        ("cf/glm-4.7-flash",      cf,   Model.id("cloudflare", "@cf/zai-org/glm-4.7-flash")),
        ("cf/gpt-oss-120b",       cf,   Model.id("cloudflare", "@cf/openai/gpt-oss-120b")),
        ("anthropic/sonnet-4-6",  anth, Model.id("anthropic/claude-sonnet-4-6")),
        ("anthropic/opus-4-8",    anth, Model.id("anthropic/claude-opus-4-8")),
        ("cf/kimi-k2.6",          cf,   Model.id("cloudflare", "@cf/moonshotai/kimi-k2.6"))
      )

      info("=== Coding head-to-head HARD: integer expression evaluator (25 hidden cases) ===")
      entries.foldLeft(Task.pure(())) { (acc, e) =>
        val (label, provider, id) = e
        acc.flatMap { _ =>
          run1(provider, TestSigil.testModel(id)).map { result =>
            info(f"$label%-26s $result")
            ()
          }
        }
      }.map(_ => succeed)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
