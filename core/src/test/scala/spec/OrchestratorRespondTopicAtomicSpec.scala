package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.Model
import sigil.event.{Event, Message, TopicChange, TopicChangeKind}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.EventState
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Regression coverage for the path-discrepancy bug — TopicChange used
 * to fire only from the orchestrator's STREAMING-respond branch.
 * Atomic-respond calls (every llama.cpp grammar-constrained reply,
 * OpenAI strict-mode function calls, Anthropic tool_use, Google
 * functionCall) went through `RespondTool.executeTyped` which only
 * emitted a Message — no topic resolution at all.
 *
 * After the fix, `RespondTool` calls `Sigil.resolveTopicShift` and
 * `Sigil.updateConversationKeywords` from inside `executeTyped` so
 * every provider path produces the same TopicChange event shape.
 *
 * This spec drives an atomic-respond provider (no ContentBlockDelta)
 * and asserts the TopicChange lands.
 */
class OrchestratorRespondTopicAtomicSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "atomic-respond-topic")

  /**
   * Emits a single atomic respond call (no streaming text content
   * before the tool args). Tests the framework's atomic-respond path
   * end-to-end without depending on a live model.
   */
  final private class AtomicRespondProvider(topicLabel: String, topicSummary: String, content: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId(s"call-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          callId,
          RespondInput(
            topicLabel = topicLabel,
            topicSummary = topicSummary,
            content = content,
            endsTurn = true
          )
        ),
        ProviderEvent.Done(StopReason.Complete)
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

  /**
   * Seed a conversation with a two-topic stack so the proposed-
   * label fast paths exercise without invoking the classifier
   * (the fake test provider can't answer a TopicClassifier call —
   * it always emits the configured respond). The `topics` list
   * order is oldest → newest: `currentTopic` = last, `previousTopics`
   * = everything else.
   */
  private def runWith(provider: Provider,
                      convPrefix: String,
                      activeTopic: TopicEntry,
                      priorTopics: List[TopicEntry] = Nil,
                      userInput: String): Task[(Id[Conversation], List[Event])] = {
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"$convPrefix-${rapid.Unique()}")
    val stack = priorTopics :+ activeTopic
    val agent = makeAgent()
    val conv = Conversation(topics = stack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = activeTopic.id,
        content = Vector(ResponseContent.Text(userInput)),
        state = EventState.Complete
      ))
      _ <- Task.sleep(3.seconds)
      evs <- TestSigil.withDB(_.events.transaction(_.list))
    } yield (convId, evs.filter(_.conversationId == convId))
  }

  private def topicEntry(prefix: String, label: String, summary: String): TopicEntry =
    TopicEntry(id = Topic.id(s"$prefix-${rapid.Unique()}"), label = label, summary = summary)

  "Atomic-respond TopicChange emission" should {

    "fire a TopicChange when the respond's topicLabel matches a prior topic (fast-path Switch back)" in {
      // Stack: prior = "TypeScript Generics", active = "Roman Empire History".
      // Agent's respond proposes "TypeScript Generics" — fast-path
      // prior-match triggers without invoking the classifier.
      val prior = topicEntry("ts-prior", "TypeScript Generics", "Brief explanation of TypeScript generics")
      val active = topicEntry("roman-active", "Roman Empire History", "Discussion of late-Republic politics")
      val provider = new AtomicRespondProvider(
        topicLabel = "TypeScript Generics",
        topicSummary = "Brief explanation of TypeScript generics",
        content = "Yeah, TypeScript generics let you parameterise types — `identity<T>(x: T): T` works on any T."
      )
      runWith(provider, "topic-prior", activeTopic = active, priorTopics = List(prior), userInput = "Back to generics — refresh me?").map {
        case (_, evs) =>
          val tcs = evs.collect { case tc: TopicChange => tc }
          withClue(s"events: ${evs.map(e => s"${e.getClass.getSimpleName}").mkString(", ")}: ") {
            tcs should not be empty
          }
          tcs.head.newLabel shouldBe "TypeScript Generics"
          tcs.head.kind shouldBe a[TopicChangeKind.Switch]
      }
    }

    "NOT fire a TopicChange when the respond's topicLabel matches the active topic (fast-path NoChange)" in {
      val active = topicEntry("roman-active", "Roman Empire History", "Discussion of late-Republic politics")
      val provider = new AtomicRespondProvider(
        topicLabel = "Roman Empire History",
        topicSummary = "Discussion of late-Republic politics",
        content = "The Gracchi brothers' land reforms in the 130s BC marked a turning point."
      )
      runWith(provider, "topic-nochange", activeTopic = active, userInput = "Tell me about the Gracchi.").map {
        case (_, evs) =>
          evs.collect { case tc: TopicChange => tc } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
