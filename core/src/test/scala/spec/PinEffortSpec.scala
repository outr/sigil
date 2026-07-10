package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, Effort, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent,
  ProviderType, ReasoningMode, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.CoreTools
import sigil.tool.model.{RespondInput, ResponseContent}
import sigil.tool.provider.{PinEffortInput, PinEffortTool, UnpinEffortInput, UnpinEffortTool}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Coverage for conversation-level reasoning-effort pinning (sigil #412).
 *
 * The contract mirrors `pinnedModelId` / `pinnedComplexity`: a consumer
 * exposes a per-conversation effort picker (Low / Medium / High / Max),
 * the choice persists on the Conversation record, and the framework
 * overlays it onto the MAIN agent turn's resolved GenerationSettings
 * (forcing reasoning on so the effort engages) without touching the
 * deployment-global ProviderStrategy candidate settings.
 *
 * Locked invariants:
 *   1. `Conversation.pinnedEffort` defaults None + persists.
 *   2. `pin_effort` parses level names and writes the field; unknown
 *      levels are rejected without mutating state.
 *   3. `unpin_effort` clears it.
 *   4. The pin flows into the agent turn's wire GenerationSettings
 *      (effort set + reasoningMode On); an unpinned conversation ships
 *      the candidate's own settings.
 */
class PinEffortSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "pin-effort-model")
  TestSigil.testModel(modelId)

  private def freshConv(label: String, pinned: Option[Effort] = None): Task[Conversation] = {
    val convId = Conversation.id(s"$label-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, _id = convId, pinnedEffort = pinned)
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv))).map(_ => conv)
  }

  private def ctx(conv: Conversation): TurnContext =
    TurnContext(
      sigil        = TestSigil,
      chain        = List(TestUser, TestAgent),
      conversation = conv,
      turnInput    = TurnInput(conversationId = conv._id, frames = Vector.empty),
      model        = TestSigil.defaultTestModel
    )

  /** Records the `GenerationSettings` of every `ProviderCall` it serves,
    * then answers with a clean single-call `respond(endsTurn = true)`
    * whose topic matches the active one (fast-path — no classifier
    * consult). The first recorded settings are the agent turn's. */
  private final class CapturingProvider extends Provider {
    val settings = new ConcurrentLinkedQueue[GenerationSettings]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      settings.add(input.generationSettings)
      val cid = CallId(s"respond-${rapid.Unique()}")
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ToolCallStart(cid, "respond"),
        ProviderEvent.ToolCallComplete(cid, RespondInput(
          topicLabel   = TestTopicEntry.label,
          topicSummary = TestTopicEntry.summary,
          content      = "Done.",
          endsTurn     = true
        )),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def agentWith(base: GenerationSettings): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = base
    )

  /** Run one user turn against `provider` and return the first
    * ProviderCall's GenerationSettings (the agent turn). */
  private def firstTurnSettings(provider: CapturingProvider,
                                pinned: Option[Effort],
                                base: GenerationSettings): Task[GenerationSettings] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"pin-effort-turn-${rapid.Unique()}")
    val agent  = agentWith(base)
    val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId, pinnedEffort = pinned)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("hello")),
             state          = EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId)
    } yield provider.settings.asScala.headOption.getOrElse(
      throw new IllegalStateException("provider was never called"))
  }

  "Conversation.pinnedEffort" should {

    "default to None on fresh conversations" in {
      freshConv("default").map(_.pinnedEffort shouldBe None)
    }

    "persist and round-trip when set" in {
      freshConv("persist", pinned = Some(Effort.High)).flatMap { conv =>
        TestSigil.withDB(_.conversations.transaction(_.get(conv._id))).map { reloaded =>
          reloaded.flatMap(_.pinnedEffort) shouldBe Some(Effort.High)
        }
      }
    }
  }

  "PinEffortTool" should {

    "parse level names and write pinnedEffort" in {
      for {
        conv     <- freshConv("pin-tool")
        _        <- PinEffortTool.execute(PinEffortInput("high"), ctx(conv), Event.id()).toList
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedEffort) shouldBe Some(Effort.High)
    }

    "accept multiple normalisations of the same level" in {
      val normalisations = List("max", "MAX", "Maximum", "highest", "full")
      Task.sequence(normalisations.map { raw =>
        for {
          conv     <- freshConv(s"normalise-${raw.replaceAll("\\W", "")}")
          _        <- PinEffortTool.execute(PinEffortInput(raw), ctx(conv), Event.id()).toList
          reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
        } yield reloaded.flatMap(_.pinnedEffort)
      }).map { results =>
        results.foreach(_ shouldBe Some(Effort.Max))
        succeed
      }
    }

    "reject an unrecognised level without mutating state" in {
      for {
        conv     <- freshConv("reject", pinned = Some(Effort.Low))
        _        <- PinEffortTool.execute(PinEffortInput("turbo"), ctx(conv), Event.id()).toList
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedEffort) shouldBe Some(Effort.Low) // unchanged
    }
  }

  "UnpinEffortTool" should {

    "clear pinnedEffort when present" in {
      for {
        conv     <- freshConv("unpin", pinned = Some(Effort.High))
        _        <- UnpinEffortTool.execute(UnpinEffortInput(), ctx(conv), Event.id()).toList
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedEffort) shouldBe None
    }

    "no-op when nothing pinned" in {
      for {
        conv     <- freshConv("unpin-noop")
        _        <- UnpinEffortTool.execute(UnpinEffortInput(), ctx(conv), Event.id()).toList
        reloaded <- TestSigil.withDB(_.conversations.transaction(_.get(conv._id)))
      } yield reloaded.flatMap(_.pinnedEffort) shouldBe None
    }
  }

  "The effort overlay in the agent turn" should {

    "ship the pinned effort with reasoning forced on" in {
      val provider = new CapturingProvider
      firstTurnSettings(provider, pinned = Some(Effort.High),
        base = GenerationSettings(maxOutputTokens = Some(50))).map { s =>
        s.effort shouldBe Some(Effort.High)
        s.reasoningMode shouldBe ReasoningMode.On
      }
    }

    "leave the candidate's own settings untouched when unpinned" in {
      val provider = new CapturingProvider
      firstTurnSettings(provider, pinned = None,
        base = GenerationSettings(maxOutputTokens = Some(50))).map { s =>
        s.effort shouldBe None
        s.reasoningMode shouldBe ReasoningMode.Auto
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
