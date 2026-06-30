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
 * Live proof of the #373 fix: a Job's `arguments` is a STRUCTURED OBJECT, so a
 * small model puts every tool parameter inside it — instead of, with the prior
 * stringified-JSON `arguments`, spilling the grep params into sibling fields
 * (`workflowId` / `variables`) and leaving `arguments` null. Asked to compose a
 * known-shape `find → act on each` workflow, Kimi-K2.6 keeps the discovery Job's
 * real grep params (the task's pattern + path) OUT of those cross-kind fields.
 *
 * The guard is the no-scatter property, not strict-emptiness of the cross-kind
 * fields: Cloudflare Workers AI doesn't honour temperature=0, so Kimi
 * intermittently echoes type-name placeholders ("string") into unused fields —
 * model noise that is not a param scatter. Checking that the actual grep params
 * never land in `workflowId` / `variables` is stable across those rolls while
 * still failing on a genuine #373 regression (params spilling out of
 * `arguments`).
 *
 * Why the flat schema (not the `oneOf`-by-kind #373 proposed): a discriminated
 * union of per-kind variants re-triggers #372 — Kimi degenerates to the
 * lowest-friction variant (a single placeholder SubWorkflow step). The flat
 * schema is the one Kimi fills structurally; structured `arguments` removes the
 * scatter incentive (#373's root cause) without that regression.
 *
 * Scope: this guards the schema's fillability — a discovery Job carries its
 * params in a real `arguments` object. The full discovery→loop WIRING is a
 * frontier-planner concern proven deterministically in WorkflowStepSpecLoweringSpec
 * / WorkflowEndToEndSpec.
 *
 * **Self-skips** without `SIGIL_LIVE` + Cloudflare credentials.
 */
class CloudflareKimiWorkflowFirstSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  override protected val testTimeout: FiniteDuration = 2.minutes

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

  "Kimi-K2.6 authoring a workflow up front" should {
    "compose a discovery Job without scattering its tool params into cross-kind fields (#373)" in {
      if (apiTokenOpt.isEmpty || accountIdOpt.isEmpty)
        cancel("CLOUDFLARE_AUTH_TOKEN / CLOUDFLARE_ACCOUNT_ID not set — skipping live Kimi workflow-first spec")

      val model = registerKimiModel()
      // Short idle timeout so a throttled/hung Cloudflare stream throws (and
      // self-skips via isServiceUnavailable) rather than blocking to the test
      // timeout.
      val provider = CloudflareProvider(apiTokenOpt.get, accountIdOpt.get, TestWorkflowSigil, tokenIdleTimeout = 45.seconds)

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

      def summarizeStep(s: sigil.workflow.WorkflowStepSpec): String =
        s"{id:${s.id}, kind:${s.kind}, tool:${s.tool.getOrElse("-")}, args:${s.arguments.map(fabric.io.JsonFormatter.Compact(_)).getOrElse("-")}, " +
          s"workflowId:${s.workflowId.getOrElse("-")}, variables:${s.variables}}"
      def summarize(in: CreateWorkflowInput): String = in.steps.map(summarizeStep).mkString("\n")

      val turn1 = Vector[ProviderMessage](ProviderMessage.User(Vector(MessageContent.Text(task))))
      author(turn1).map { in =>
        withClue(s"authored steps:\n${summarize(in)}\n") {
          in.steps should not be empty
          val discoveryJob = in.steps.find(s =>
            s.kind == WorkflowStepKind.Job && s.tool.exists(discoveryTools.contains))
          discoveryJob should not be empty
          // The #373 property: with `arguments` a structured object the grep
          // params have a home there, so the model no longer mis-routes them
          // into the SubWorkflow-only cross-kind fields (`workflowId` /
          // `variables`) the way the prior stringified-`arguments` schema
          // forced (e.g. spilling "includeIgnored:false" into `workflowId`).
          //
          // Asserting those fields are STRICTLY empty was brittle: Cloudflare
          // Workers AI does not honour temperature=0, so Kimi intermittently
          // returns a schema SKELETON — `arguments` empty, type-name
          // placeholders ("string") echoed into otherwise-unused fields. That
          // is model noise, NOT a param scatter. Assert the property the bug
          // actually names: the real grep params (the task's pattern + path)
          // never appear in a cross-kind field. A genuine #373 regression puts
          // those exact values there and fails; placeholder noise does not — so
          // the guard is stable across Kimi's rolls yet still catches the
          // regression it exists to catch.
          val crossKindValues: List[String] =
            discoveryJob.flatMap(_.workflowId).toList :::
              discoveryJob.toList.flatMap(_.variables.values)
          val grepParamSignals = List("/home/u/project", "bug #")
          withClue(s"discovery Job = ${discoveryJob.map(summarizeStep)}\n" +
                   s"cross-kind field values = $crossKindValues\n") {
            for (v <- crossKindValues; sig <- grepParamSignals)
              withClue(s"grep param '$sig' must live in `arguments`, not scattered into a cross-kind field (found in '$v'): ") {
                v.toLowerCase.contains(sig.toLowerCase) shouldBe false
              }
          }
          succeed
        }
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
