package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.participant.{DefaultAgentParticipant, WorkerParticipantId}
import sigil.provider.{AnalysisWork, Complexity}
import sigil.role.Role
import sigil.tool.ToolResult
import sigil.tool.model.DelegateTaskInput
import sigil.tool.util.{DelegateTaskOutput, DelegateTaskTool}

/**
 * Sigil #335 — a `delegate_task(complexity = High)` must pin that tier on
 * the spawned worker conversation, so the worker's per-turn routing
 * honors the delegated complexity instead of re-inferring it (which
 * biases Low) on every turn and routing to the cheapest model.
 */
class DelegateTaskComplexitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "delegate-complexity")
  TestWorkflowSigil.cache.merge(List(syntheticModel(modelId))).sync()

  private def syntheticModel(id: Id[Model]): Model = {
    import sigil.db.{ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
    val now = lightdb.time.Timestamp()
    Model(
      canonicalSlug       = id.value,
      huggingFaceId       = "",
      name                = id.value,
      description         = s"Synthetic Model record for $id.",
      contextLength       = 32768L,
      architecture        = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
      pricing             = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
      topProvider         = ModelTopProvider(contextLength = Some(32768L), maxCompletionTokens = Some(8192L), isModerated = false),
      perRequestLimits    = None,
      supportedParameters = Set("temperature", "max_tokens", "top_p", "tools", "tool_choice"),
      knowledgeCutoff     = None,
      expirationDate      = None,
      links               = ModelLinks(details = ""),
      created             = now,
      _id                 = id
    )
  }

  /** A conversation whose caller is an AgentParticipant — `delegate_task`
    * requires the caller to be one (it becomes the worker's supervisor). */
  private def supervisorContext(): Task[TurnContext] = {
    // The supervisor must be an AgentParticipant whose id type is
    // registered in TestWorkflowSigil — WorkerParticipantId qualifies.
    val supervisorId = WorkerParticipantId(s"supervisor-${rapid.Unique()}")
    val supervisor   = DefaultAgentParticipant(id = supervisorId, modelId = modelId)
    val conv = Conversation(
      topics       = List(TopicEntry(TestTopicId, "test", "test")),
      participants = List(supervisor),
      _id          = Conversation.id(s"delegate-complexity-${rapid.Unique()}")
    )
    TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv))).map { stored =>
      TurnContext(
        sigil        = TestWorkflowSigil,
        chain        = List(supervisorId),
        conversation = stored,
        turnInput    = TurnInput(ConversationView(conversationId = stored._id)),
        model        = TestWorkflowSigil.cache.find(modelId).get
      )
    }
  }

  private def input(complexity: Option[Complexity]): DelegateTaskInput =
    DelegateTaskInput(
      role       = Role(name = "bug-finder", description = "Find bugs.", workType = AnalysisWork),
      brief      = "Find all bug references in the repo.",
      modelId    = Some(modelId.value),
      complexity = complexity
    )

  private def workerConvComplexity(complexity: Option[Complexity]): Task[Option[Complexity]] =
    supervisorContext().flatMap { ctx =>
      DelegateTaskTool.executeResult(input(complexity), toolContext(ctx)).flatMap {
        case ToolResult.Success(out: DelegateTaskOutput) =>
          TestWorkflowSigil.withDB(_.conversations.transaction(_.get(Conversation.id(out.workerConvId))))
            .map(_.flatMap(_.pinnedComplexity))
        case other => Task.pure(fail(s"expected a successful spawn, got $other"))
      }
    }

  /** Wrap a TurnContext into a ToolContext for the typed tool call. */
  private def toolContext(turn: TurnContext): sigil.tool.ToolContext =
    sigil.tool.ToolContext(turn, sigil.event.Event.id(), DelegateTaskTool.name)

  "delegate_task" should {
    "pin the delegated complexity on the worker conversation" in {
      workerConvComplexity(Some(Complexity.High)).map(_ shouldBe Some(Complexity.High))
    }

    "leave the worker conversation unpinned when no complexity is delegated" in {
      workerConvComplexity(None).map(_ shouldBe None)
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
