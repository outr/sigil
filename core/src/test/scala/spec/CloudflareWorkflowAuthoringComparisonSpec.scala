package spec

import fabric.{Json, Obj}
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.cloudflare.CloudflareProvider
import sigil.provider.{GenerationSettings, MessageContent, ProviderCall, ProviderEvent, ProviderMessage, ReasoningMode, ToolChoice}
import sigil.workflow.{WorkflowStepKind, WorkflowStepSpec}
import sigil.workflow.tool.{CreateWorkflowInput, CreateWorkflowTool}

import scala.concurrent.duration.*
import sigil.tool.ToolRoster

/**
 * Head-to-head: which Cloudflare Workers AI model can author a known-shape
 * `find → act on each` workflow that Kimi-K2.6 couldn't? Feeds the SAME
 * `create_workflow` authoring prompt to each candidate and scores the three
 * things Kimi failed at:
 *   (1) non-degenerate  — >=2 steps incl. a discovery Job (not a single
 *       placeholder SubWorkflow);
 *   (2) args populated  — the discovery Job's `arguments` is a non-empty object;
 *   (3) wired+lowers    — a Loop whose `over` is a Job's `output` with non-empty
 *       `bodyStepIds`, AND the flat specs lower to the engine IR (compilable).
 *
 * Measurement, not a gate — always succeeds; the scorecard is emitted via
 * `info(...)` as each model completes (so a late hang doesn't lose earlier
 * results). Self-skips a model on a Cloudflare service hang. **Self-skips
 * entirely without `SIGIL_LIVE` + Cloudflare creds.**
 */
class CloudflareWorkflowAuthoringComparisonSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  override protected val testTimeout: FiniteDuration = 12.minutes

  private val apiTokenOpt = sys.env.get("CLOUDFLARE_AUTH_TOKEN").filter(_.nonEmpty)
  private val accountIdOpt = sys.env.get("CLOUDFLARE_ACCOUNT_ID").filter(_.nonEmpty)

  // Candidates ordered strongest-first; Kimi LAST as the baseline so its known
  // unbounded-reasoning hang can't starve the others.
  private val candidates: List[String] = List(
    "@cf/openai/gpt-oss-120b",
    "@cf/nvidia/nemotron-3-120b-a12b",
    "@cf/meta/llama-4-scout-17b-16e-instruct",
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    "@cf/moonshotai/kimi-k2.6"
  )

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    CloudflareLiveSupport.runGated(this, testName, args)(super.run(testName, args))

  private def registerModel(slug: String): Model = {
    val id = Model.id("cloudflare", slug)
    TestWorkflowSigil.cache.find(id).getOrElse {
      val m = Model(
        canonicalSlug = id.value,
        huggingFaceId = "",
        name = id.value,
        description = "Live comparison fixture.",
        contextLength = 131072L,
        architecture = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
        pricing = ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
        topProvider = ModelTopProvider(Some(131072L), Some(16000L), isModerated = false),
        perRequestLimits = None,
        supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
        knowledgeCutoff = None,
        expirationDate = None,
        links = ModelLinks(""),
        created = lightdb.time.Timestamp(),
        _id = id
      )
      TestWorkflowSigil.cache.merge(List(m)).sync(); m
    }
  }

  private val system =
    """You author a Sigil workflow as a FLAT list of steps. Each step has a unique `id`, a `kind`,
      |and the fields for that kind. Nesting is by reference: a Loop names its body steps by id,
      |and those steps are ordinary entries in the same flat list.
      |
      |Use these kinds:
      |- kind "Job":  runs ONE tool. Set `tool` (a tool name), `arguments` (a JSON OBJECT holding
      |  EVERY parameter of that tool — NOT a string), and `output` (a variable name capturing the
      |  result).
      |- kind "Loop": iterates. `over` names the variable to iterate — when it names a tool's text
      |  output (e.g. grep's newline paths) the engine splits it into items automatically, so you
      |  do NOT hand-build an array. `itemVariable` (default "item") binds each element; reference
      |  it as {{item}} in body steps. `bodyStepIds` lists the ids of steps to run per item.
      |
      |Tools available to a Job's `tool`:
      |  grep { pattern, path }   -> newline-separated matching file paths
      |  echo_back { text }       -> echoes text (stand-in for the per-item action)
      |
      |WORKFLOW-FIRST PRINCIPLE: when the shape is known (find X, then act on each X), build the
      |WHOLE workflow up front. Make discovery the FIRST stage — a Job that runs a discovery tool
      |and captures its output into a variable — then a Loop whose `over` is THAT variable. The
      |engine finds the particulars at run time; never enumerate the items yourself.
      |
      |Worked example (a DIFFERENT task — find error logs, act on each):
      |  { "id":"scan",  "kind":"Job",  "tool":"grep", "arguments":{"pattern":"ERROR","path":"/logs"}, "output":"errorFiles" }
      |  { "id":"per",   "kind":"Loop", "over":"errorFiles", "itemVariable":"file", "bodyStepIds":["note"] }
      |  { "id":"note",  "kind":"Job",  "tool":"echo_back", "arguments":{"text":"{{file}}"} }
      |Note: `arguments` is a JSON object (all the tool's params go inside it), the Loop's `over` is
      |EXACTLY the first Job's `output`, and `bodyStepIds` lists the per-item step's id.
      |
      |Reply ONLY by calling the `create_workflow` tool.""".stripMargin

  private val task =
    "Author a workflow named `strip-bug-refs`: find every Scala file under `/home/u/project` " +
      "that contains a bug-number reference (grep pattern `bug #`), then act on each matched file " +
      "by echoing its path. Compose it workflow-first — discovery is the first stage; do not list " +
      "the files yourself."

  private val discoveryTools = Set("grep", "glob", "lsp_workspace_symbols")

  private def argsPopulated(s: WorkflowStepSpec): Boolean = s.arguments.exists {
    case Obj(m) => m.nonEmpty
    case _ => false
  }

  private def score(in: CreateWorkflowInput): String = {
    val steps = in.steps
    val jobs = steps.filter(_.kind == WorkflowStepKind.Job)
    val discoveryJob = jobs.find(_.tool.exists(discoveryTools.contains))
    val nonDegenerate = steps.sizeIs >= 2 && discoveryJob.isDefined
    val argsOk = discoveryJob.exists(argsPopulated)
    val jobOutputs = jobs.flatMap(_.output).filter(_.nonEmpty).toSet
    val loopWired = steps.exists(s =>
      s.kind == WorkflowStepKind.Loop && s.over.exists(jobOutputs.contains) && s.bodyStepIds.nonEmpty)
    val lowers = WorkflowStepSpec.lower(steps).isRight
    val wired = loopWired && lowers
    def m(b: Boolean) = if (b) "PASS" else "fail"
    val kinds = steps.map(_.kind).mkString(",")
    f"steps=${steps.size}%-2d [$kinds] | non-degenerate:${m(nonDegenerate)} args:${m(argsOk)} wired+lowers:${m(wired)}"
  }

  private def author(provider: CloudflareProvider, model: Model): Task[String] = {
    val pc = ProviderCall(
      model = model,
      system = system,
      messages = Vector(ProviderMessage.User(Vector(MessageContent.Text(task)))),
      roster = ToolRoster(Vector(new CreateWorkflowTool)),
      builtInTools = Set.empty,
      toolChoice = ToolChoice.Required,
      // No fixed temperature so natural run-to-run variance shows in the
      // reliability batch (CF defaults to non-streaming for these models).
      generationSettings = GenerationSettings(maxOutputTokens = Some(16000), reasoningMode = ReasoningMode.Auto)
    )
    provider.call(pc).toList.map { events =>
      events.flatMap { case ProviderEvent.ToolCallComplete(_, wc) => wc.decodedInput; case _ => None }.collectFirst {
        case in: CreateWorkflowInput => in
      }
        .map(score)
        .getOrElse(s"no create_workflow call. events=${events.map(_.getClass.getSimpleName).distinct.mkString(",")}")
    }.handleError { t =>
      if (CloudflareLiveSupport.isServiceUnavailable(t))
        Task.pure(s"SKIPPED (CF unavailable: ${Option(t.getMessage).getOrElse(t.toString).take(80)})")
      else
        Task.pure(s"ERROR: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("").take(120)}")
    }
  }

  "Workflow-authoring RELIABILITY batch (Kimi vs gpt-oss, non-streaming)" should {
    "author the find→loop→act workflow N times each and report consistency" in {
      if (apiTokenOpt.isEmpty || accountIdOpt.isEmpty)
        cancel("CLOUDFLARE_AUTH_TOKEN / CLOUDFLARE_ACCOUNT_ID not set — skipping live comparison")

      val provider = CloudflareProvider(apiTokenOpt.get, accountIdOpt.get, TestWorkflowSigil, tokenIdleTimeout = 90.seconds)
      val n = 5
      val models = List("@cf/moonshotai/kimi-k2.6", "@cf/openai/gpt-oss-120b")
      def fullPass(s: String): Boolean =
        s.contains("non-degenerate:PASS") && s.contains("args:PASS") && s.contains("wired+lowers:PASS")

      info("=== Workflow-authoring reliability batch (non-streaming CF) ===")
      models.foldLeft(Task.pure(())) { (acc, slug) =>
        acc.flatMap { _ =>
          val model = registerModel(slug)
          (1 to n).toList.foldLeft(Task.pure(List.empty[String])) { (a, _) =>
            a.flatMap(rs => author(provider, model).map(r => rs :+ r))
          }.map { results =>
            val good = results.count(fullPass)
            info(f"${slug.stripPrefix("@cf/")}%-34s  fully-correct: $good/$n")
            results.zipWithIndex.foreach { case (r, i) => info(f"    run ${i + 1}: $r") }
            ()
          }
        }
      }.map(_ => succeed)
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
