package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ReplySuggestionsConfig}
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall, ProviderEvent, ProviderMessage, ProviderType, MessageContent, StopReason
}
import sigil.signal.{EventState, Signal, SuggestedReplies}
import sigil.tool.consult.{SuggestReplyInput, SuggestReplyTool}
import sigil.tool.core.{CoreTools, RespondOptionsTool, RespondTool}
import sigil.tool.model.{RespondInput, RespondOptionsInput, ResponseContent, SelectOption}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Framework-generated reply suggestions: after a turn settles with a
 * user-visible reply, a background consult predicts the user's likely
 * next message(s) and the framework publishes a transient
 * [[SuggestedReplies]] notice.
 *
 * Covers the gate (off by default, worker / staging conversations,
 * options already offered), the delivered payload for the
 * single-suggestion and multi-suggestion shapes, and the failure
 * posture (consult blows up → warn, no notice, turn unaffected).
 */
class ReplySuggestionsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "reply-suggestions-model")
  TestSigil.testModel(modelId)

  /**
   * The per-turn extractor issues its own consult after every turn;
   * silence it so the suggestion consult is the only one this suite's
   * provider has to recognise.
   */
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
   * Answers an agent turn with the scripted reply, answers the
   * suggestion consult with `suggestions`, and answers every other
   * framework consult with a bare `Done` (no opinion). Records each
   * suggestion consult's rendered user prompt.
   */
  final private class SuggestingProvider(reply: ProviderEvent*)(suggestions: List[String],
                                                                failConsult: Boolean = false)
    extends Provider {
    val suggestCalls = new AtomicInteger(0)
    val suggestPrompts = new ConcurrentLinkedQueue[String]()
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (input.roster.contains(SuggestReplyTool.name.value)) {
        suggestCalls.incrementAndGet()
        suggestPrompts.add(promptOf(input))
        if (failConsult) Stream.force(Task.error(new RuntimeException("consult exploded")))
        else {
          val cid = CallId(s"suggest-${rapid.Unique()}")
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ToolCallStart(cid, SuggestReplyTool.name.value),
            ProviderEvent.toolCall(cid, SuggestReplyTool)(SuggestReplyInput(suggestions)),
            ProviderEvent.Done(StopReason.Complete)
          ))
        }
      } else if (input.roster.size == 1) Stream.emits(List[ProviderEvent](ProviderEvent.Done(StopReason.Complete)))
      else Stream.emits(reply.toList)

    private def promptOf(input: ProviderCall): String = input.messages.collect {
      case ProviderMessage.User(blocks) => blocks.collect { case MessageContent.Text(t) => t }.mkString("\n")
    }.mkString("\n")
  }

  private def respondEvents(content: String): List[ProviderEvent] = {
    val cid = CallId(s"respond-${rapid.Unique()}")
    List[ProviderEvent](
      ProviderEvent.ToolCallStart(cid, RespondTool.name.value),
      ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
        topicLabel = TestTopicEntry.label,
        topicSummary = TestTopicEntry.summary,
        content = content,
        endsTurn = true
      )),
      ProviderEvent.Done(StopReason.Complete)
    )
  }

  private def respondOptionsEvents: List[ProviderEvent] = {
    val cid = CallId(s"options-${rapid.Unique()}")
    List[ProviderEvent](
      ProviderEvent.ToolCallStart(cid, RespondOptionsTool.name.value),
      ProviderEvent.toolCall(cid, RespondOptionsTool)(RespondOptionsInput(
        prompt = "Which environment?",
        options = List(SelectOption("Staging", "staging"), SelectOption("Production", "production")),
        allowMultiple = false
      )),
      ProviderEvent.Done(StopReason.Complete)
    )
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))
    )

  private def subscribe(): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signals
      .takeWhile(_ => running)
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  private def awaitSuggestions(recorded: ConcurrentLinkedQueue[Signal],
                               convId: Id[Conversation],
                               timeout: FiniteDuration = 10.seconds): Task[List[SuggestedReplies]] = {
    def snapshot: List[SuggestedReplies] = recorded.iterator().asScala.collect {
      case s: SuggestedReplies if s.conversationId == convId => s
    }.toList
    def loop(remainingMs: Long): Task[List[SuggestedReplies]] =
      if (snapshot.nonEmpty || remainingMs <= 0) Task.pure(snapshot)
      else Task.sleep(25.millis).flatMap(_ => loop(remainingMs - 25))
    loop(timeout.toMillis)
  }

  /**
   * Drive one complete turn: seed the conversation, publish the user
   * message, wait for the agent's claim to settle, then give the
   * post-settle background fiber a bounded window to land.
   */
  private def runTurn(conv: Conversation,
                      text: String = "How do I roll back the last deploy?"): Task[Unit] =
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = conv._id,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text(text)),
        state = EventState.Complete
      ))
      _ <- TestSigil.awaitSettled(conv._id, timeout = 30.seconds)
    } yield ()

  private def prepare(): Unit = {
    TestSigil.reset()
    TestSigil.setMemoryExtractor(NoExtraction)
  }

  private def newConv(prefix: String,
                      parent: Option[Id[Conversation]] = None,
                      staging: Option[Id[Conversation]] = None): Conversation =
    Conversation(
      topics = TestTopicStack,
      participants = List(makeAgent()),
      _id = Conversation.id(s"$prefix-${rapid.Unique()}"),
      parentConversationId = parent,
      stagingFor = staging
    )

  private val config = ReplySuggestionsConfig(fallbackModelId = modelId)

  "reply suggestions" should {
    "dispatch no consult and publish no notice when unconfigured" in {
      prepare()
      val provider = new SuggestingProvider(respondEvents("Run `deploy rollback --last`.")*)(List("How do I verify it worked?"))
      TestSigil.setProvider(Task.pure(provider))
      val (recorded, stop) = subscribe()
      val conv = newConv("suggest-off")
      runTurn(conv)
        .flatMap(_ => awaitSuggestions(recorded, conv._id, timeout = 2.seconds))
        .map { notices =>
          stop()
          provider.suggestCalls.get() should be(0)
          notices should be(empty)
        }
    }

    "deliver a single suggestion anchored on the settled reply" in {
      prepare()
      TestSigil.setReplySuggestions(config)
      val provider = new SuggestingProvider(respondEvents("Run `deploy rollback --last`.")*)(
        List("  \"How do I verify the rollback worked?\"  "))
      TestSigil.setProvider(Task.pure(provider))
      val (recorded, stop) = subscribe()
      val conv = newConv("suggest-single")
      for {
        _ <- runTurn(conv)
        notices <- awaitSuggestions(recorded, conv._id)
        events <- TestSigil.withDB(_.conversationEvents(conv._id))
      } yield {
        stop()
        provider.suggestCalls.get() should be(1)
        notices should have size 1
        val notice = notices.head
        notice.suggestions should be(List("How do I verify the rollback worked?"))
        val agentReplies = events.collect {
          case m: Message if m.participantId == TestAgent && m.state == EventState.Complete => m
        }
        agentReplies.map(_._id) should contain(notice.forMessageId)
      }
    }

    "ask for distinct intents and carry up to `count` suggestions" in {
      prepare()
      TestSigil.setReplySuggestions(config.copy(count = 3))
      val provider = new SuggestingProvider(respondEvents("Run `deploy rollback --last`.")*)(
        List("Did that actually revert the migration?", "Show me the deploy log instead", "What changed in the last release?"))
      TestSigil.setProvider(Task.pure(provider))
      val (recorded, stop) = subscribe()
      val conv = newConv("suggest-multi")
      for {
        _ <- runTurn(conv)
        notices <- awaitSuggestions(recorded, conv._id)
      } yield {
        stop()
        notices should have size 1
        notices.head.suggestions should have size 3
        val prompt = provider.suggestPrompts.iterator().asScala.mkString("\n")
        prompt should include("DISTINCT intents")
        prompt should include("3 candidate next messages")
      }
    }

    "skip the consult when the turn already offered structured options" in {
      prepare()
      TestSigil.setReplySuggestions(config)
      val provider = new SuggestingProvider(respondOptionsEvents*)(List("Staging please"))
      TestSigil.setProvider(Task.pure(provider))
      val (recorded, stop) = subscribe()
      val conv = newConv("suggest-options")
      runTurn(conv, "Deploy the new build")
        .flatMap(_ => awaitSuggestions(recorded, conv._id, timeout = 2.seconds))
        .map { notices =>
          stop()
          provider.suggestCalls.get() should be(0)
          notices should be(empty)
        }
    }

    "still suggest after an options turn when skipWhenOptionsOffered is false" in {
      prepare()
      TestSigil.setReplySuggestions(config.copy(skipWhenOptionsOffered = false))
      val provider = new SuggestingProvider(respondOptionsEvents*)(List("Staging please"))
      TestSigil.setProvider(Task.pure(provider))
      val (recorded, stop) = subscribe()
      val conv = newConv("suggest-options-on")
      for {
        _ <- runTurn(conv, "Deploy the new build")
        notices <- awaitSuggestions(recorded, conv._id)
      } yield {
        stop()
        provider.suggestCalls.get() should be(1)
        notices should have size 1
        notices.head.suggestions should be(List("Staging please"))
      }
    }

    "never suggest in a worker scratchpad conversation" in {
      prepare()
      TestSigil.setReplySuggestions(config)
      val provider = new SuggestingProvider(respondEvents("Worker finished the sweep.")*)(List("What did you find?"))
      TestSigil.setProvider(Task.pure(provider))
      val (recorded, stop) = subscribe()
      val conv = newConv("suggest-worker", parent = Some(Conversation.id("suggest-worker-parent")))
      runTurn(conv)
        .flatMap(_ => awaitSuggestions(recorded, conv._id, timeout = 2.seconds))
        .map { notices =>
          stop()
          provider.suggestCalls.get() should be(0)
          notices should be(empty)
        }
    }

    "never suggest in a staging conversation" in {
      prepare()
      TestSigil.setReplySuggestions(config)
      val provider = new SuggestingProvider(respondEvents("Imported 812 records.")*)(List("Show me the first ones"))
      TestSigil.setProvider(Task.pure(provider))
      val (recorded, stop) = subscribe()
      val conv = newConv("suggest-staging", staging = Some(Conversation.id("suggest-staging-target")))
      runTurn(conv)
        .flatMap(_ => awaitSuggestions(recorded, conv._id, timeout = 2.seconds))
        .map { notices =>
          stop()
          provider.suggestCalls.get() should be(0)
          notices should be(empty)
        }
    }

    "swallow a consult failure without a notice and without disturbing the turn" in {
      prepare()
      TestSigil.setReplySuggestions(config)
      val provider = new SuggestingProvider(respondEvents("Run `deploy rollback --last`.")*)(Nil, failConsult = true)
      TestSigil.setProvider(Task.pure(provider))
      val (recorded, stop) = subscribe()
      val conv = newConv("suggest-fail")
      for {
        _ <- runTurn(conv)
        notices <- awaitSuggestions(recorded, conv._id, timeout = 3.seconds)
        events <- TestSigil.withDB(_.conversationEvents(conv._id))
      } yield {
        stop()
        provider.suggestCalls.get() should be(1)
        notices should be(empty)
        val replies = events.collect {
          case m: Message if m.participantId == TestAgent && m.state == EventState.Complete => m
        }
        replies should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
