package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.cloudflare.CloudflareProvider
import sigil.provider.{
  GenerationSettings, MessageContent, ProviderCall, ProviderEvent, ProviderMessage, ReasoningMode, ToolChoice
}
import sigil.workflow.WorkflowStepKind
import sigil.workflow.tool.{CreateWorkflowInput, CreateWorkflowTool}

import scala.concurrent.duration.*

/**
 * Live proof that the flat `WorkflowStepSpec` authoring surface is fillable over
 * the real wire by a small model (#338/#372): asked to compose a known-shape
 * `find → act on each` workflow, Kimi-K2.6 calls `create_workflow` with steps
 * that decode into typed `WorkflowStepSpec`s carrying REAL content — a `Job`
 * naming an actual discovery tool. The prior `oneOf`-of-seven `WorkflowStepInput`
 * union could not be filled at all: the model collapsed to a single sub-workflow
 * step with placeholder values ("string", {key:"string"}). That this no longer
 * happens — a meaningful, decodable step with a real tool — is the redesign's win
 * and what this guards against regressing.
 *
 * Scope: a small model fills the schema with real content, but does NOT reliably
 * author the full discovery→loop WIRING (a Job's `output` equal to the Loop's
 * `over`, a non-empty `bodyStepIds`) — across escalating guidance and explicit
 * recoverable-error feedback, Kimi K2.6 gets individual steps right but not the
 * cross-step dataflow, and its step count even varies run to run. Sage's design
 * has a frontier model author workflows; the wiring, the framework's recoverable
 * validation of it (dangling `over`, empty loop body), and the engine execution
 * are proven deterministically in WorkflowStepSpecLoweringSpec and
 * WorkflowEndToEndSpec.
 *
 * **Self-skips** without `SIGIL_LIVE` + Cloudflare credentials.
 */
class CloudflareKimiWorkflowFirstSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  override protected val testTimeout: FiniteDuration = 4.minutes

  private val apiTokenOpt: Option[String]  = sys.env.get("CLOUDFLARE_AUTH_TOKEN").filter(_.nonEmpty)
  private val accountIdOpt: Option[String] = sys.env.get("CLOUDFLARE_ACCOUNT_ID").filter(_.nonEmpty)
  private val modelId: Id[Model] = Model.id("cloudflare", "@cf/moonshotai/kimi-k2.6")

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    CloudflareLiveSupport.runGated(this, testName, args)(super.run(testName, args))

  private def registerKimiModel(): Model = TestWorkflowSigil.cache.find(modelId).getOrElse {
    val m = Model(
      canonicalSlug = modelId.value, huggingFaceId = "", name = modelId.value,
      description = "Live Kimi fixture.", contextLength = 131072L,
      architecture = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
      pricing = ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
      topProvider = ModelTopProvider(Some(131072L), Some(16000L), isModerated = false),
      perRequestLimits = None,
      supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
      knowledgeCutoff = None, expirationDate = None, links = ModelLinks(""),
      created = lightdb.time.Timestamp(), _id = modelId
    )
    TestWorkflowSigil.cache.merge(List(m)).sync(); m
  }

  private val system =
    """You author a Sigil workflow as a FLAT list of steps. Each step has a unique `id`, a `kind`,
      |and the fields for that kind. Nesting is by reference: a Loop names its body steps by id,
      |and those steps are ordinary entries in the same flat list.
      |
      |Use these kinds:
      |- kind "Job":  runs ONE tool. Set `tool` (a tool name), `arguments` (a JSON STRING of its
      |  args), and `output` (a variable name capturing the result).
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
      |  { "id":"scan",  "kind":"Job",  "tool":"grep", "arguments":"{\"pattern\":\"ERROR\",\"path\":\"/logs\"}", "output":"errorFiles" }
      |  { "id":"per",   "kind":"Loop", "over":"errorFiles", "itemVariable":"file", "bodyStepIds":["note"] }
      |  { "id":"note",  "kind":"Job",  "tool":"echo_back", "arguments":"{\"text\":\"{{file}}\"}" }
      |Note: the Loop's `over` is EXACTLY the first Job's `output` variable, and `bodyStepIds` lists
      |the id of the per-item step. Wire those three together or the loop has nothing to iterate.
      |
      |Reply ONLY by calling the `create_workflow` tool.""".stripMargin

  private val task =
    "Author a workflow named `strip-bug-refs`: find every Scala file under `/home/u/project` " +
      "that contains a bug-number reference (grep pattern `bug #`), then act on each matched file " +
      "by echoing its path. Compose it workflow-first — discovery is the first stage; do not list " +
      "the files yourself."

  private val discoveryTools = Set("grep", "glob", "lsp_workspace_symbols")

  "Kimi-K2.6 authoring a workflow up front" should {
    "fill the flat step schema with a real discovery-tool Job, not the union's placeholder junk (#338/#372)" in {
      if (apiTokenOpt.isEmpty || accountIdOpt.isEmpty)
        cancel("CLOUDFLARE_AUTH_TOKEN / CLOUDFLARE_ACCOUNT_ID not set — skipping live Kimi workflow-first spec")

      val model = registerKimiModel()
      val provider = CloudflareProvider(apiTokenOpt.get, accountIdOpt.get, TestWorkflowSigil)

      def author(messages: Vector[ProviderMessage]): Task[CreateWorkflowInput] = {
        val pc = ProviderCall(
          model = model,
          system = system,
          messages = messages,
          tools = Vector(new CreateWorkflowTool),
          builtInTools = Set.empty,
          toolChoice = ToolChoice.Required,
          generationSettings = GenerationSettings(maxOutputTokens = Some(16000), temperature = Some(0.0), reasoningMode = ReasoningMode.Auto)
        )
        provider.call(pc).toList.handleError { t =>
          if (CloudflareLiveSupport.isServiceUnavailable(t))
            Task(cancel(s"Cloudflare Workers AI unavailable (throttle/timeout) — skipping live spec. (${Option(t.getMessage).getOrElse(t.toString)})"))
          else Task.error(t)
        }.map { events =>
          events.collectFirst { case ProviderEvent.ToolCallComplete(_, in: CreateWorkflowInput) => in }
            .getOrElse(throw new RuntimeException(s"Kimi did not call create_workflow. Events: $events"))
        }
      }

      def summarize(in: CreateWorkflowInput): String =
        in.steps.map(s => s"{id:${s.id}, kind:${s.kind}, tool:${s.tool.getOrElse("-")}, output:${s.output.getOrElse("-")}, over:${s.over.getOrElse("-")}, bodyStepIds:${s.bodyStepIds.mkString("[", ",", "]")}}").mkString("\n")

      val turn1 = Vector[ProviderMessage](ProviderMessage.User(Vector(MessageContent.Text(task))))
      author(turn1).map { in =>
        withClue(s"authored steps:\n${summarize(in)}\n") {
          // The schema is filled with REAL content: at least one Job names an
          // actual discovery tool — not the placeholder ("string") the unfillable
          // union forced. Every step decodes to a typed kind by construction.
          in.steps should not be empty
          in.steps.exists(s => s.kind == WorkflowStepKind.Job && s.tool.exists(discoveryTools.contains)) shouldBe true
        }
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
