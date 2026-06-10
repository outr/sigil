package spec

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.db.Model
import sigil.provider.anthropic.AnthropicProvider
import sigil.provider.cloudflare.CloudflareProvider
import sigil.provider.{
  CallId, GenerationSettings, MessageContent, Provider, ProviderCall, ProviderEvent, ProviderMessage,
  ReasoningMode, ToolCallMessage, ToolChoice
}
import sigil.script.ScalaScriptExecutor
import sigil.tool.{TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * AGENTIC coding eval (real coding, not a one-shot puzzle): each model
 * drives a multi-iteration tool loop — list_files / read_file / grep /
 * edit_file / run_tests — over a fresh 2-file Scala workspace with a
 * planted cross-file bug. The symptom is in `Url.make`; the bug is in
 * `Slug.slugify` (it leaves leading/trailing/collapsed hyphens), so the
 * model must NAVIGATE from symptom to cause, edit the right file, and use
 * run_tests to verify. Objective score = final run_tests pass-rate; plus
 * iteration count.
 *
 * This is the faithful proxy for Sage's hard tier. Measurement, not a gate
 * — always succeeds; per-model scorecard via `info(...)`. Self-skips without
 * ANTHROPIC_API_KEY + Cloudflare creds.
 */
class CodingAgenticEvalSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override protected val testTimeout: FiniteDuration = 30.minutes

  private val anthropicKey = sys.env.get("ANTHROPIC_API_KEY").filter(_.nonEmpty)
  private val cfToken      = sys.env.get("CLOUDFLARE_AUTH_TOKEN").filter(_.nonEmpty)
  private val cfAccount    = sys.env.get("CLOUDFLARE_ACCOUNT_ID").filter(_.nonEmpty)

  private val executor = new ScalaScriptExecutor()

  // ---- the planted-bug workspace -------------------------------------

  private val slugBuggy =
    """object Slug {
      |  // Turn a human title into a URL slug: lowercase, and replace every
      |  // run of non-alphanumeric characters with a single hyphen.
      |  def slugify(s: String): String =
      |    s.toLowerCase.replaceAll("[^a-z0-9]+", "-")
      |}
      |""".stripMargin

  private val urlSrc =
    """object Url {
      |  def make(title: String): String = "https://x.com/" + Slug.slugify(title)
      |}
      |""".stripMargin

  /** Hidden cases — the buggy slugify fails the ones with leading/trailing
    * separators; a correct fix trims hyphens at both ends and collapses runs. */
  private val cases: List[(String, String)] = List(
    "Hello, World!"   -> "https://x.com/hello-world",
    "  Spaced  Out  " -> "https://x.com/spaced-out",
    "C++ & Scala"     -> "https://x.com/c-scala",
    "---weird---"     -> "https://x.com/weird",
    "Already-A-Slug"  -> "https://x.com/already-a-slug"
  )

  private def newWorkspace(): Path = {
    val dir = Files.createTempDirectory("agentic-eval-")
    Files.writeString(dir.resolve("Slug.scala"), slugBuggy)
    Files.writeString(dir.resolve("Url.scala"), urlSrc)
    dir
  }

  /** Compile the current workspace + assert the hidden cases via the REPL. */
  private def runTests(ws: Path): String = {
    val sources = List("Slug.scala", "Url.scala").map(f => Files.readString(ws.resolve(f))).mkString("\n")
    val asserts = cases.map { case (in, exp) =>
      s"""if (Url.make(${quote(in)}) != ${quote(exp)}) __fails += (${quote(in)} + " -> got " + Url.make(${quote(in)}))"""
    }.mkString("\n")
    val program =
      s"""$sources
         |val __fails = scala.collection.mutable.ListBuffer[String]()
         |$asserts
         |if (__fails.isEmpty) "PASS ${cases.size}/${cases.size}"
         |else "FAIL " + (${cases.size} - __fails.size) + "/${cases.size}: " + __fails.mkString("; ")
         |""".stripMargin
    scala.util.Try(executor.execute(program, Map.empty).sync()).fold(
      t => s"COMPILE/RUN ERROR: ${Option(t.getMessage).getOrElse("").take(200)}",
      identity
    )
  }

  private def quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  // ---- agent tools over the workspace --------------------------------

  case class ListInput() extends ToolInput derives RW
  case class ReadInput(path: String) extends ToolInput derives RW
  case class GrepInput(pattern: String) extends ToolInput derives RW
  case class EditInput(path: String, find: String, replace: String) extends ToolInput derives RW
  case class RunInput() extends ToolInput derives RW

  /** Minimal Tool over a captured workspace; the loop calls `run` directly. */
  private abstract class WsTool extends Tool {
    type Output = TextToolOutput
    val outputRW = summon[RW[TextToolOutput]]
    def run(input: ToolInput): String
    override def executeResult(input: Input, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(run(input))))
    def argsJson(in: ToolInput): String = JsonFormatter.Compact(inputRW.asInstanceOf[RW[ToolInput]].read(in))
  }

  private def tools(ws: Path): List[WsTool] = {
    def readAll: List[String] = Files.list(ws).iterator().asScala.map(_.getFileName.toString).filter(_.endsWith(".scala")).toList.sorted
    List(
      new WsTool {
        type Input = ListInput; val inputRW = summon[RW[ListInput]]
        val name = ToolName("list_files"); val description = "List the .scala files in the project."
        def run(in: ToolInput) = readAll.mkString("\n")
      },
      new WsTool {
        type Input = ReadInput; val inputRW = summon[RW[ReadInput]]
        val name = ToolName("read_file"); val description = "Read a file's full contents (with line numbers). Args: path."
        def run(in: ToolInput) = readFile(ws, in.asInstanceOf[ReadInput].path)
      },
      new WsTool {
        type Input = GrepInput; val inputRW = summon[RW[GrepInput]]
        val name = ToolName("grep"); val description = "Search all files for a substring. Args: pattern. Returns file:line matches."
        def run(in: ToolInput) = grep(ws, in.asInstanceOf[GrepInput].pattern)
      },
      new WsTool {
        type Input = EditInput; val inputRW = summon[RW[EditInput]]
        val name = ToolName("edit_file")
        val description = "Replace the FIRST exact occurrence of `find` with `replace` in `path`. Args: path, find, replace."
        def run(in: ToolInput) = { val e = in.asInstanceOf[EditInput]; edit(ws, e.path, e.find, e.replace) }
      },
      new WsTool {
        type Input = RunInput; val inputRW = summon[RW[RunInput]]
        val name = ToolName("run_tests"); val description = "Compile the project and run the hidden test cases. Call this to verify your fix."
        def run(in: ToolInput) = runTests(ws)
      }
    )
  }

  private def readFile(ws: Path, path: String): String = {
    val p = ws.resolve(path.stripPrefix("./"))
    if (!Files.exists(p)) s"(no such file: $path)"
    else Files.readString(p).linesIterator.zipWithIndex.map { case (l, i) => f"${i + 1}%4d  $l" }.mkString("\n")
  }
  private def grep(ws: Path, pattern: String): String = {
    val hits = readAllFiles(ws).flatMap { case (name, content) =>
      content.linesIterator.zipWithIndex.collect { case (l, i) if l.contains(pattern) => f"$name:${i + 1}: ${l.trim}" }
    }
    if (hits.isEmpty) "(no matches)" else hits.mkString("\n")
  }
  private def edit(ws: Path, path: String, find: String, replace: String): String = {
    val p = ws.resolve(path.stripPrefix("./"))
    if (!Files.exists(p)) s"FAILED: no such file: $path"
    else {
      val content = Files.readString(p)
      val idx = content.indexOf(find)
      if (idx < 0) s"FAILED: `find` text not found in $path. Read the file again and match exactly."
      else { Files.writeString(p, content.substring(0, idx) + replace + content.substring(idx + find.length)); s"OK: edited $path" }
    }
  }
  private def readAllFiles(ws: Path): List[(String, String)] =
    Files.list(ws).iterator().asScala.filter(_.getFileName.toString.endsWith(".scala")).toList
      .map(p => p.getFileName.toString -> Files.readString(p)).sortBy(_._1)

  // ---- the manual agentic loop ---------------------------------------

  private val system =
    "You are an autonomous coding agent fixing a bug in a small Scala project. Use the tools to " +
      "explore (list_files, read_file, grep), make changes (edit_file), and verify (run_tests). " +
      "Keep going until run_tests reports PASS, then stop. Make minimal, correct edits."

  private val task =
    "The function Url.make is producing wrong slugs. For example Url.make(\"Hello, World!\") should " +
      "return \"https://x.com/hello-world\" but it doesn't. There are other failing cases too. Find the " +
      "root cause, fix it, and run_tests until everything passes."

  private def agenticRun(provider: Provider, model: Model, ws: Path): Task[String] = {
    val toolList = tools(ws)
    val byName = toolList.map(t => t.name.value -> t).toMap
    val maxIters = 18

    def loop(messages: Vector[ProviderMessage], iter: Int): Task[String] = {
      if (iter >= maxIters) Task.pure(s"(hit ${maxIters}-iteration cap)")
      else {
        val pc = ProviderCall(
          model = model, system = system, messages = messages, tools = toolList.toVector,
          builtInTools = Set.empty, toolChoice = ToolChoice.Auto,
          generationSettings = GenerationSettings(maxOutputTokens = Some(16000), reasoningMode = ReasoningMode.Off)
        )
        provider.call(pc).toList.flatMap { events =>
          val text = events.collect { case ProviderEvent.TextDelta(t) => t; case ProviderEvent.ContentBlockDelta(_, t) => t }.mkString
          val names = events.collect { case ProviderEvent.ToolCallStart(CallId(id), n) => id -> n }.toMap
          val calls = events.collect { case ProviderEvent.ToolCallComplete(CallId(id), in) => (id, names.getOrElse(id, ""), in) }
          scala.util.Try(java.nio.file.Files.writeString(
            java.nio.file.Path.of(s"/tmp/ag-${model._id.value.replaceAll("[^A-Za-z0-9]", "_")}-i$iter.txt"),
            s"events=${events.map(_.getClass.getSimpleName).distinct.mkString(",")}\ncalls=[${calls.map(_._2).mkString(",")}]\n---TEXT(${text.length})---\n${text.take(1500)}"))
          if (calls.isEmpty) Task.pure(s"(agent stopped after $iter iterations without passing)")
          else {
            val toolCallMsgs = calls.map { case (id, n, in) => ToolCallMessage(id, n, byName.get(n).map(_.argsJson(in)).getOrElse("{}")) }
            val results = calls.map { case (id, n, in) =>
              val out = byName.get(n).map(_.run(in)).getOrElse(s"(unknown tool: $n)")
              ProviderMessage.ToolResult(id, out)
            }
            val next = messages ++ Vector(ProviderMessage.Assistant(text, toolCallMsgs)) ++ results
            loop(next, iter + 1)
          }
        }.handleError(t => Task.pure(s"call error @iter$iter: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("").take(60)}"))
      }
    }

    loop(Vector(ProviderMessage.User(Vector(MessageContent.Text(task)))), 0).map { stop =>
      s"final=${runTests(ws)}  | $stop"
    }
  }

  "Agentic coding RELIABILITY batch (Kimi vs gpt-oss, non-streaming CF)" should {
    "run each cheap CF model N times and report pass-rate + iteration counts" in {
      if (anthropicKey.isEmpty || cfToken.isEmpty || cfAccount.isEmpty)
        cancel("ANTHROPIC_API_KEY / CLOUDFLARE creds not set — skipping live agentic eval")

      val cf   = CloudflareProvider(cfToken.get, cfAccount.get, TestSigil, tokenIdleTimeout = 120.seconds)
      val anth = AnthropicProvider(apiKey = anthropicKey.get, sigilRef = TestSigil)

      val n = 5
      // (label, provider, id, runs)
      val plan: List[(String, Provider, Id[Model], Int)] = List(
        ("cf/kimi-k2.6",         cf,   Model.id("cloudflare", "@cf/moonshotai/kimi-k2.6"), n),
        ("cf/gpt-oss-120b",      cf,   Model.id("cloudflare", "@cf/openai/gpt-oss-120b"), n),
        ("anthropic/sonnet-4-6", anth, Model.id("anthropic/claude-sonnet-4-6"), 2)
      )
      def itersOf(s: String): String = """after (\d+) iter""".r.findFirstMatchIn(s).map(_.group(1)).getOrElse("?")
      def passed(s: String): Boolean = s.contains("final=PASS")

      info("=== Agentic reliability batch: cross-file fix (objective compile+test) ===")
      plan.foldLeft(Task.pure(())) { (acc, p) =>
        val (label, provider, id, runs) = p
        acc.flatMap { _ =>
          (1 to runs).toList.foldLeft(Task.pure(List.empty[String])) { (a, _) =>
            a.flatMap(rs => agenticRun(provider, TestSigil.testModel(id), newWorkspace()).map(r => rs :+ r))
          }.map { results =>
            val passes = results.count(passed)
            info(f"$label%-22s  ${passes}/${runs} PASS   iters=[${results.map(itersOf).mkString(",")}]")
            results.zipWithIndex.foreach { case (r, i) => info(f"    run ${i + 1}: ${r.take(120)}") }
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
