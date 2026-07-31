package spec

import fabric.{obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import lightdb.id.Id
import sigil.event.Event
import sigil.tool.{ToolContext, ToolResult}
import sigil.workflow.{WorkflowStepKind, WorkflowStepSpec}
import sigil.workflow.tool.{CreateWorkflowInput, CreateWorkflowTool}

/**
 * Sigil #378 — a Job step's tool `arguments` are validated against the
 * referenced tool's input schema at create_workflow time, so a wrong field
 * name fails fast with a fixable message instead of crashing the whole run
 * when the Loop reaches that step.
 */
class WorkflowStepArgValidationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "argval")
  TestWorkflowSigil.cache.merge(List(syntheticModel(modelId))).sync()

  private def syntheticModel(id: Id[Model]): Model = {
    val now = lightdb.time.Timestamp()
    Model(
      canonicalSlug = id.value,
      huggingFaceId = "",
      name = id.value,
      description = s"Synthetic Model record for $id.",
      contextLength = 32768L,
      architecture = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
      pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
      topProvider = ModelTopProvider(contextLength = Some(32768L), maxCompletionTokens = Some(8192L), isModerated = false),
      perRequestLimits = None,
      supportedParameters = Set("temperature", "tools"),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = ""),
      created = now,
      _id = id
    )
  }

  private val tool = new CreateWorkflowTool

  private def ctx(): ToolContext = {
    val convId = Conversation.id(s"argval-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
      _id = convId
    )
    val tc = TurnContext(
      sigil = TestWorkflowSigil,
      chain = List(WorkflowTestUser),
      conversation = conv,
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      model = TestWorkflowSigil.cache.find(modelId).get
    )
    ToolContext(tc, Event.id(), tool.name)
  }

  private def jobStep(args: fabric.Json): WorkflowStepSpec =
    WorkflowStepSpec(id = "echo", kind = WorkflowStepKind.Job, tool = Some("echo_back"), arguments = Some(args))

  "create_workflow step-argument validation (sigil #378)" should {

    "reject a Job step whose tool arguments use an unknown field name" in {
      // echo_back's only field is `text`; `bogus` should be rejected at authoring.
      val input = CreateWorkflowInput(name = "bad-args", steps = List(jobStep(obj("bogus" -> str("x")))))
      tool.invoke(input, ctx()).map(_ => fail("expected a Failure")).handleError {
        case tfe: sigil.tool.ToolFailureException => rapid.Task {
            val message = tfe.failureMessage
            message should include("echo_back")
            message should include("unknown argument")
            message should include("bogus")
            message should include("text") // the valid field is surfaced
          }
        case other => rapid.Task(fail(s"expected a ToolFailureException, got $other"))
      }
    }

    "accept a Job step whose tool arguments are field-correct" in {
      val input = CreateWorkflowInput(name = "good-args", steps = List(jobStep(obj("text" -> str("hi")))))
      tool.invoke(input, ctx()).map(_ => succeed)
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
