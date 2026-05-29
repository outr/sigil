package spec

import fabric.*
import fabric.io.JsonParser
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageDisposition, MessageRole, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.core.{ChangeModeTool, RecordConsentTool, UnknownTool}
import sigil.tool.model.{ChangeModeInput, RecordConsentInput}
import sigil.tool.{
  JsonInput, TextToolOutput, Tool, ToolContext, ToolExample, ToolInput,
  ToolName, ToolResult
}
import spice.http.HttpRequest

/**
 * Pins the refusal-payload shape down across the framework's refusal-producing
 * paths (`change_mode` no-op, `record_consent` unknown-tool, `UnknownTool`
 * fallback, the orchestrator's validator-error path). Every refusal must
 * carry the rule, the full input schema, and a worked example so the agent
 * can retry correctly on the next iteration without consulting the offered
 * roster again.
 */
class RefusalPayloadShapeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "refusal-payload-shape")
  TestSigil.testModel(modelId)

  // ---- schema fixtures for the multi-mismatch + closest-name cases ----

  /** Test tool whose input has two fields a model can fumble at once:
    * `role` (object) and `complexity` (string). Mirrors the field
    * evidence in the bug doc — a polymorphic-object field + a separate
    * scalar field on the same call. */
  case class FanoutRole(name: String, workType: String) extends ToolInput derives RW
  case class FanoutInput(role: FanoutRole, complexity: String) extends ToolInput derives RW

  case object FanoutTool extends Tool {
    type Input  = FanoutInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[FanoutInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName("fanout_workers")
    val description = "Fan out a per-item action across a container of items."
    override val examples: List[ToolExample] = List(
      ToolExample(
        "kick off a code-review fan-out",
        FanoutInput(role = FanoutRole(name = "reviewer", workType = "AnalysisWork"),
                    complexity = "Medium")
      )
    )
    override def executeOutput(input: FanoutInput, ctx: ToolContext): Task[TextToolOutput] =
      Task.pure(TextToolOutput(s"ran ${input.role.name}"))
  }

  // ---- helpers ----

  private def turnContextFor(currentMode: sigil.provider.Mode,
                             offered: Vector[Tool] = Vector.empty): Task[TurnContext] = {
    val convId = Conversation.id(s"refusal-payload-${rapid.Unique()}")
    val topic  = TopicEntry(
      id      = sigil.conversation.Topic.id(s"topic-$convId"),
      label   = "test",
      summary = "test"
    )
    val conv = Conversation(_id = convId, topics = List(topic), currentMode = currentMode)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map { stored =>
      TurnContext(
        sigil        = TestSigil,
        chain        = List(TestUser, TestAgent),
        conversation = stored,
        turnInput    = TurnInput(conversationId = stored._id),
        model        = TestSigil.testModel(modelId),
        offeredTools = offered
      )
    }
  }

  private def failureBody(signals: List[Signal]): String =
    signals.collectFirst {
      case d: ToolDelta => d.outcome
    }.flatten.collect {
      case f: ToolOutcome.Failure => f.reason
    }.getOrElse(fail("expected a Failure outcome on the settling ToolDelta"))

  "ChangeModeTool same-mode refusal" should {
    "include the full input_schema for change_mode + a worked example" in {
      for {
        ctx     <- turnContextFor(TestCodingMode)
        signals <- ChangeModeTool.execute(ChangeModeInput(mode = "coding"), ctx, Event.id()).toList
      } yield {
        val body = failureBody(signals)
        body should include("already in")
        // Schema present — at minimum the JSON-Schema-style "properties"
        // key with the `mode` field name visible inline.
        body should include("\"properties\"")
        body should include("\"mode\"")
        // Worked example present — the example label section + a
        // syntactically suggestive `mode` field on the example payload.
        body.toLowerCase should include("example")
      }
    }
  }

  "ChangeModeTool unknown-mode refusal" should {
    "include the schema, an example, and the registered modes" in {
      for {
        ctx     <- turnContextFor(sigil.provider.ConversationMode)
        signals <- ChangeModeTool.execute(ChangeModeInput(mode = "ferocity"), ctx, Event.id()).toList
      } yield {
        val body = failureBody(signals)
        body should include("ferocity")
        body should include("\"properties\"")
        body should include("\"mode\"")
        body.toLowerCase should include("example")
        // Available modes still surface — that's today's hint and the
        // agent depends on it.
        body should include("coding")
      }
    }
  }

  "RecordConsentTool unknown-tool refusal" should {
    "include record_consent's schema, an example, and the closest match by name from the registry" in {
      for {
        ctx     <- turnContextFor(sigil.provider.ConversationMode)
        // `send_slack_messag` is one char off from `send_slack_message`
        // which TestSigil registers as a real tool in the static roster.
        signals <- RecordConsentTool.execute(
                    RecordConsentInput(toolName = "send_slack_messag", approved = true),
                    ctx, Event.id()
                  ).toList
      } yield {
        val body = failureBody(signals)
        body should include("send_slack_messag")
        // Schema for record_consent itself.
        body should include("\"properties\"")
        body should include("\"toolName\"")
        body.toLowerCase should include("example")
        // Closest-name suggestion.
        body should include("send_slack_message")
      }
    }
  }

  "UnknownTool dispatch fallback" should {
    "include the closest-name match from the offered roster plus that tool's schema and example" in {
      // Offered roster contains FanoutTool; the model hallucinates a
      // close-but-wrong name (`fanout_worker` — singular).
      for {
        ctx     <- turnContextFor(sigil.provider.ConversationMode, offered = Vector(FanoutTool, ChangeModeTool))
        signals <- UnknownTool.execute(
                    JsonInput(obj("brief" -> str("do something"))),
                    ctx, Event.id(),
                    invokedName = ToolName("fanout_worker")
                  ).toList
      } yield {
        val body = failureBody(signals)
        body should include("fanout_worker")
        // Closest match surfaced.
        body should include("fanout_workers")
        // Closest match's schema rendered inline so the agent can retry.
        body should include("\"role\"")
        body should include("\"complexity\"")
        body.toLowerCase should include("example")
      }
    }
  }

  "Orchestrator validator-error path" should {
    "surface ALL schema mismatches in one refusal payload, not just the first" in {
      // Stub a provider that emits a synthetic validator-style
      // ProviderEvent.Error naming two distinct field violations on a
      // single call. The orchestrator's `_provider_error` path renders
      // them into the settling ToolDelta's Failure outcome.
      val provider = new MultiViolationProvider
      val convId = Conversation.id("refusal-multi")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = ConversationRequest(
        conversationId     = convId,
        model              = TestSigil.testModel(modelId),
        instructions       = Instructions(),
        turnInput          = TurnInput(conversationId = convId),
        currentMode        = ConversationMode,
        currentTopic       = TestTopicEntry,
        previousTopics     = Nil,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
        chain              = List(TestUser, TestAgent),
        tools              = Vector(FanoutTool)
      )
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        // Find the settling Failure outcome the agent reads.
        val failureReason = signals.collect {
          case d: ToolDelta => d.outcome
        }.flatten.collect {
          case ToolOutcome.Failure(reason, _) => reason
        }.find(_.contains("Provider error"))
          .getOrElse(fail("expected a Provider error Failure on a settling ToolDelta"))
        // Both mismatches surface in ONE refusal payload, not one-at-a-time.
        failureReason should include("role.workType")
        failureReason should include("complexity")
        // The literal sent value should appear alongside the expected
        // shape so the agent can diff its emit against the target.
        failureReason should include("\"AnalysisWork\"")
      }
    }
  }

  /** Provider that emits a single validator-style error citing two distinct
    * field mismatches on one tool call. */
  private final class MultiViolationProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("multi-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, FanoutTool.schema.name.value),
        ProviderEvent.Error(
          "Args for tool fanout_workers violated schema constraints: " +
            "role.workType: expected object {type: <WorkType>}; you sent string \"AnalysisWork\"; " +
            "complexity: expected one of [Low, Medium, High]; you sent string \"medium\""
        )
      ))
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
