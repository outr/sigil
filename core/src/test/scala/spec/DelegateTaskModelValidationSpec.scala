package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.{Event, ToolOutcome}
import sigil.provider.AnalysisWork
import sigil.role.Role
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.model.DelegateTaskInput
import sigil.tool.util.DelegateTaskTool

/**
 * Coverage for the `delegate_task` `modelId` validation boundary. When
 * the parent agent emits a `modelId` that isn't in the host's
 * [[sigil.cache.ModelRegistry]], the tool must refuse at the boundary
 * with an actionable `ToolResult.Failure` — before spawning the worker
 * sub-conversation — so the framework can't route a worker to an unknown
 * model. Refusal payload composes with [[sigil.tool.RefusalPayload]]
 * (schema + worked example + sampling of valid ids). The successful
 * spawn path is covered end-to-end by the LlamaCpp delegation bridge
 * spec.
 */
class DelegateTaskModelValidationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestWorkflowSigil.initFor(getClass.getSimpleName)

  // A known-good model id seeded into the registry once for the
  // "registered id is accepted" case. Disjoint from `knownTestModels`
  // so the assertion isn't accidentally satisfied by a fixture id.
  private val registeredId: Id[Model] = Model.id("test", "delegate-task-validation")
  TestWorkflowSigil.cache.merge(List(syntheticModel(registeredId))).sync()

  // The hallucinated id from the bug doc — never registered.
  private val unregisteredIdString: String = "anthropic/claude-sonnet-4-6"

  private def turnContext(): Task[TurnContext] = {
    val convId = Conversation.id(s"delegate-modelid-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      _id    = convId
    )
    TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv))).map { stored =>
      TurnContext(
        sigil        = TestWorkflowSigil,
        chain        = List(WorkflowTestUser),
        conversation = stored,
        turnInput    = TurnInput(ConversationView(conversationId = stored._id)),
        model        = TestWorkflowSigil.cache.find(registeredId).get
      )
    }
  }

  private def baseInput(modelId: Option[String]): DelegateTaskInput =
    DelegateTaskInput(
      role = Role(
        name = "researcher",
        description = "Research and synthesize.",
        workType = AnalysisWork
      ),
      brief = "Find recent papers on RAG.",
      modelId = modelId
    )

  private def failureReason(signals: List[Signal]): Option[String] =
    signals.collectFirst { case d: ToolDelta => d.outcome }.flatten.collect {
      case ToolOutcome.Failure(reason, _) => reason
    }

  private def successOutcome(signals: List[Signal]): Boolean =
    signals.collectFirst { case d: ToolDelta => d.outcome }.flatten.contains(ToolOutcome.Success)

  "DelegateTaskTool" when {
    "modelId is set to an id not in the ModelRegistry" should {
      "refuse at the tool boundary with a ToolResult.Failure" in {
        for {
          ctx     <- turnContext()
          signals <- DelegateTaskTool.execute(baseInput(Some(unregisteredIdString)), ctx, Event.id()).toList
        } yield {
          val reason = failureReason(signals).getOrElse(
            fail(s"expected a Failure outcome on a settling ToolDelta; got signals=$signals"))
          reason should include(unregisteredIdString)
          // Names `modelId` as the offending field + suggests the
          // recommended default (omit and let routing pick).
          reason should include("modelId")
          reason.toLowerCase should (include("routing") or include("routed") or include("route") or include("providerstrategy"))
          reason should include("optional")
          successOutcome(signals) shouldBe false
        }
      }

      "include the full delegate_task input_schema and a worked example in the refusal" in {
        for {
          ctx     <- turnContext()
          signals <- DelegateTaskTool.execute(baseInput(Some(unregisteredIdString)), ctx, Event.id()).toList
        } yield {
          val reason = failureReason(signals).getOrElse(
            fail("expected a Failure outcome on a settling ToolDelta"))
          // Schema present — JSON-Schema style "properties" + each
          // first-level field name surfaces inline.
          reason should include("\"properties\"")
          reason should include("\"role\"")
          reason should include("\"brief\"")
          // Worked example present (RefusalPayload renders an Example block).
          reason.toLowerCase should include("example")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }

  private def syntheticModel(id: Id[Model]): Model = {
    import sigil.db.{ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
    val now = lightdb.time.Timestamp()
    Model(
      canonicalSlug       = id.value,
      huggingFaceId       = "",
      name                = id.value,
      description         = s"Synthetic Model record for $id.",
      contextLength       = 32768L,
      architecture        = ModelArchitecture(
        modality         = "text->text",
        inputModalities  = List("text"),
        outputModalities = List("text"),
        tokenizer        = "GPT",
        instructType     = None
      ),
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
}
