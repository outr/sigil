package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke, TopicChange, TopicChangeKind}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.consult.TopicClassifierInput
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import scala.concurrent.duration.*
import sigil.tool.consult.TopicClassifierTool

/**
 * A topic switch lands at the END of its turn — the classifier only
 * decides once the respond proposes a label — so without the
 * founding-turn sweep every event of the turn that caused the change
 * (the triggering user message included) would stay stamped with the
 * previous topic, permanently. The sweep re-homes the founding turn
 * onto the topic it founded via [[sigil.signal.TopicDelta]]s at claim
 * release.
 *
 * Verifies:
 *   1. Classifier `New` → after the turn settles, every event of the
 *      turn (user message, tool invokes, agent reply) carries the new
 *      topicId/topicIndex — read back from the persisted rows, so
 *      replay shows the same stamps.
 *   2. Events from BEFORE the founding turn keep the previous topic.
 *   3. Classifier `Refine` (rename, same topic id) → stamps untouched.
 *   4. Classifier `NoChange` → stamps untouched.
 */
class TopicFoundingTurnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "founding-turn")
  TestSigil.testModel(modelId)

  private object NoExtraction extends sigil.conversation.compression.extract.MemoryExtractor {
    override def extract(sigil: _root_.sigil.Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[_root_.sigil.participant.ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[_root_.sigil.conversation.ContextMemory]] =
      Task.pure(Nil)
  }

  /**
   * Serves both call shapes: the primary agent turn emits a respond
   * proposing `proposedLabel`; the classifier consult returns the
   * scripted kind.
   */
  private class DualShapeProvider(proposedLabel: String, classifierKind: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (input.tools.exists(_.schema.name.value == "classify_topic_shift")) {
        val callId = CallId(s"classify-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, "classify_topic_shift"),
          ProviderEvent.toolCall(callId, new TopicClassifierTool(Nil))(TopicClassifierInput(kind = classifierKind)),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      } else {
        val callId = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
          ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
            topicLabel = proposedLabel,
            topicSummary = s"$proposedLabel summary",
            content = "On it.",
            endsTurn = true
          )),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  private def waitUntil(timeout: FiniteDuration)(cond: Task[Boolean]): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = cond.flatMap { ok =>
      if (ok || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(50.millis).flatMap(_ => loop)
    }
    loop
  }

  /**
   * Seed a conversation with an established topic and one persisted
   * prior-turn exchange (direct row inserts — no trigger fired), then
   * publish the founding user message and let the agent turn run.
   */
  private def runFoundingTurn(proposedLabel: String,
                              classifierKind: String,
                              suffix: String): Task[(Id[Conversation], Topic, Message, Message)] = {
    val convId = Conversation.id(s"founding-$suffix-${rapid.Unique()}")
    val seed = Topic(
      conversationId = convId,
      label = "sigil project setup",
      summary = "Setting up the sigil project.",
      createdBy = TestUser)
    val conv = Conversation(
      topics = List(TopicEntry(seed._id, seed.label, seed.summary)),
      participants = List(makeAgent()),
      _id = convId
    )
    val priorUserMsg = Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = seed._id,
      content = Vector(ResponseContent.Text("please set up the project")),
      state = EventState.Complete,
      timestamp = Timestamp(lightdb.util.Nowish() - 60000L)
    )
    val foundingMsg = Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = seed._id,
      content = Vector(ResponseContent.Text("Find and remove all references to bugs in the code.")),
      state = EventState.Complete
    )
    TestSigil.setProvider(Task.pure(new DualShapeProvider(proposedLabel, classifierKind)))
    TestSigil.setMemoryExtractor(NoExtraction)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.withDB(_.topics.transaction(_.upsert(seed)))
      _ <- TestSigil.withDB(_.eventsTransaction(convId)(_.upsert(priorUserMsg)))
      _ <- TestSigil.publish(foundingMsg)
      // The turn is fully settled once the agent's reply Message exists;
      // the sweep runs at claim release, so poll past both.
      _ <- waitUntil(20.seconds) {
        TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map { events =>
          events.exists {
            case m: Message => m.participantId == TestAgent && m.role == MessageRole.Standard
            case _ => false
          }
        }
      }
      _ <- Task.sleep(500.millis)
    } yield (convId, seed, priorUserMsg, foundingMsg)
  }

  private def eventsOf(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.eventsTransaction(convId)(_.list)).map(_.filter(_.conversationId == convId))

  "the founding-turn topic sweep" should {

    "re-home every event of the turn that caused a New topic — user message, tool invokes, reply" in
      runFoundingTurn("bug removal sweep", classifierKind = "New", suffix = "new").flatMap {
        case (convId, seed, priorUserMsg, foundingMsg) =>
          for {
            newTopic <- TestSigil.withDB(_.topics.transaction(_.list))
              .map(_.find(t => t.conversationId == convId && t.label == "bug removal sweep")
                .getOrElse(fail("new Topic record not persisted")))
            // The sweep runs at claim release and folds one delta per
            // event — poll until the WHOLE turn has been re-homed, not
            // just the first row the sweep happened to reach.
            _ <- waitUntil(20.seconds) {
              eventsOf(convId).map { events =>
                val turn = events.filter(_.timestamp.value >= foundingMsg.timestamp.value)
                turn.exists(_._id == foundingMsg._id) && turn.forall(_.topicId == newTopic._id)
              }
            }
            events <- eventsOf(convId)
            conv <- TestSigil.withDB(_.conversations.transaction(_.get(convId))).map(_.get)
          } yield {
            val newIdx = conv.topics.indexWhere(_.id == newTopic._id)
            newIdx shouldBe 1

            val founding = events.find(_._id == foundingMsg._id).get
            withClue("founding user message: ") {
              founding.topicId shouldBe newTopic._id
              founding.topicIndex shouldBe newIdx
            }
            val invokes = events.collect { case ti: ToolInvoke if !ti.toolName.value.startsWith("_") => ti }
            invokes should not be empty
            withClue(s"tool invokes (${invokes.map(_.toolName.value)}): ") {
              invokes.foreach { ti =>
                ti.topicId shouldBe newTopic._id
                ti.topicIndex shouldBe newIdx
              }
            }
            val reply = events.collect {
              case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard => m
            }
            reply should not be empty
            val dump = events.sortBy(_.timestamp.value).map(e =>
              s"${e.timestamp.value} ${e.getClass.getSimpleName} topic=${e.topicId.value.take(8)} idx=${e.topicIndex} state=${e.state}").mkString(
              "\n")
            withClue(s"agent reply (seed=${seed._id.value.take(8)} new=${newTopic._id.value.take(8)}):\n$dump\n") {
              reply.foreach { m =>
                m.topicId shouldBe newTopic._id
                m.topicIndex shouldBe newIdx
              }
            }
            // Nothing of the founding turn still points at the old topic
            // except pre-turn history (asserted below).
            val stale = events.filter(e =>
              e.timestamp.value >= foundingMsg.timestamp.value && e.topicId == seed._id)
            withClue(s"post-boundary events still on the old topic (${stale.map(_.getClass.getSimpleName)}): ") {
              stale shouldBe empty
            }
            // Pre-turn history keeps its topic — it legitimately belongs
            // to the previous subject.
            val prior = events.find(_._id == priorUserMsg._id).get
            prior.topicId shouldBe seed._id
            prior.topicIndex shouldBe 0
          }
      }

    "leave stamps untouched when the classifier says Refine (same topic, sharper label)" in
      runFoundingTurn("sigil workspace setup", classifierKind = "Refine", suffix = "refine").flatMap {
        case (convId, seed, _, foundingMsg) =>
          for {
            // Rename lands in the same turn — wait for the renamed row.
            _ <- waitUntil(20.seconds) {
              TestSigil.withDB(_.topics.transaction(_.get(seed._id))).map(_.exists(_.label == "sigil workspace setup"))
            }
            events <- eventsOf(convId)
          } yield {
            events.collect { case tc: TopicChange => tc.kind } should matchPattern {
              case List(TopicChangeKind.Rename(_)) =>
            }
            val founding = events.find(_._id == foundingMsg._id).get
            founding.topicId shouldBe seed._id
            founding.topicIndex shouldBe 0
          }
      }

    "leave stamps untouched when the classifier says NoChange" in
      runFoundingTurn("unrelated label", classifierKind = "NoChange", suffix = "nochange").flatMap {
        case (convId, seed, _, foundingMsg) =>
          eventsOf(convId).map { events =>
            events.collect { case tc: TopicChange => tc } shouldBe empty
            val founding = events.find(_._id == foundingMsg._id).get
            founding.topicId shouldBe seed._id
            founding.topicIndex shouldBe 0
          }
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
