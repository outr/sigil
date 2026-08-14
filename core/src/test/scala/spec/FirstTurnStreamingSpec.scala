package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, MessageDelta, Signal}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * A conversation's FIRST turn must stream exactly like every later
 * turn: the agent's reply reaches subscribers as incremental
 * `MessageDelta`s while the provider is still emitting, not only as
 * the settled reply.
 *
 * Two wire shapes carry a reply, and a fresh conversation is where
 * they diverge. A model whose forced `tool_choice` was demoted to
 * `auto` answers the opening turn in prose (`TextDelta`) — the
 * transcript holds no `respond` call for it to mimic yet — and wraps
 * later turns in the `respond` tool (`ContentBlockDelta`) once one is
 * in the history. Both shapes must stream.
 */
class FirstTurnStreamingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  private case class TurnTrace(deltas: Int, firstDelta: Int, terminalIndex: Int, terminalKind: String) {
    override def toString: String =
      s"deltas=$deltas firstDelta=$firstDelta terminal=$terminalKind@$terminalIndex"
  }

  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "first-turn-streaming")
  TestSigil.testModel(modelId)

  /** A model demoted to `auto` tool choice — the state in which prose
    * is the model's committed answer rather than drift. */
  private val proseModelId: Id[Model] = Model.id("test", "first-turn-prose")
  TestSigil.testModel(proseModelId)
  Provider.recordForcedToolChoiceRejection(proseModelId)

  private def replyChunks(label: String): List[String] =
    (1 to 60).map(i => s"$label-chunk$i ").toList

  /** Streams the reply as 60 content chunks, then completes the
    * `respond` call — the shape every chat-completions backend
    * produces for a streamed tool call. Content varies per call so the
    * in-turn duplicate-respond suppression never engages. */
  private final class ChunkedRespondProvider extends Provider {
    private val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (!input.tools.exists(_.name.value == "respond")) Stream.empty
      else {
        val callId = CallId(s"call-${rapid.Unique()}")
        val chunks = replyChunks(s"respond${calls.incrementAndGet()}")
        Stream.emits(
          List[ProviderEvent](
            ProviderEvent.ToolCallStart(callId, "respond"),
            ProviderEvent.ContentBlockStart(callId, "Markdown", None)
          ) ::: chunks.map(c => ProviderEvent.ContentBlockDelta(callId, c)) ::: List[ProviderEvent](
            ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
              topicLabel   = "Streaming",
              topicSummary = "Streaming a reply",
              content      = chunks.mkString,
              endsTurn     = true
            )),
            ProviderEvent.Done(StopReason.Complete)
          )
        )
      }
  }

  /** Streams the reply as 60 plain-text chunks with no tool call — what
    * a model running on `auto` emits when the transcript holds no
    * `respond` example yet. Every iteration answers the same way, so
    * the naked-text challenge budget runs out and the prose commits as
    * the turn's reply. */
  private final class ChunkedProseProvider extends Provider {
    private val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (!input.tools.exists(_.name.value == "respond")) Stream.empty
      else Stream.emits(
        replyChunks(s"prose${calls.incrementAndGet()}").map(c => ProviderEvent.TextDelta(c)) :::
          List[ProviderEvent](ProviderEvent.Done(StopReason.Complete)))
  }

  private def makeAgent(model: Id[Model]): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = model,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(400), temperature = Some(0.0)))

  /** A live `signalsFor` subscription that records what a wire client
    * would receive, in arrival order. One per test so the turns of one
    * test never see another's signals. */
  private final class Recorder {
    private val recorded = new AtomicReference[Vector[Signal]](Vector.empty)
    @volatile private var running = true
    TestSigil.signalsFor(TestUser)
      .evalMap(s => Task { recorded.updateAndGet(_ :+ s); () })
      .takeWhile(_ => running)
      .drain
      .startUnit()
    Thread.sleep(200)

    def take(): Vector[Signal] = recorded.getAndSet(Vector.empty)
    def stop(): Unit = running = false
  }

  /** Incremental content deltas observed for `convId`, and where the
    * settled reply landed. The reply settles either as a
    * `MessageDelta(contentReplacement, Complete)` or — on the atomic
    * path — as a whole `Message` carrying its content. */
  private def trace(signals: Vector[Signal], convId: Id[Conversation]): TurnTrace = {
    val terminal = signals.zipWithIndex.collectFirst {
      case (m: Message, i)
        if m.conversationId == convId && m.participantId == TestAgent &&
          m.role == MessageRole.Standard && m.state == EventState.Complete && m.content.nonEmpty =>
        (i, "Message")
      case (d: MessageDelta, i)
        if d.conversationId == convId && d.state.contains(EventState.Complete) &&
          d.contentReplacement.exists(_.nonEmpty) =>
        (i, "MessageDelta")
    }
    val deltaIndexes = signals.zipWithIndex.collect {
      case (d: MessageDelta, i) if d.conversationId == convId && d.content.exists(!_.complete) => i
    }
    TurnTrace(
      deltas        = deltaIndexes.size,
      firstDelta    = deltaIndexes.headOption.getOrElse(-1),
      terminalIndex = terminal.map(_._1).getOrElse(-1),
      terminalKind  = terminal.map(_._2).getOrElse("none")
    )
  }

  private def streamedBeforeSettling(t: TurnTrace): org.scalatest.compatible.Assertion = {
    t.terminalIndex should be >= 0
    t.deltas should be > 0
    t.firstDelta should be < t.terminalIndex
  }

  private def userTurn(convId: Id[Conversation], text: String): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      .map(_.getOrElse(fail("conversation missing")))
      .flatMap { conv =>
        TestSigil.publish(Message(
          participantId  = TestUser,
          conversationId = convId,
          topicId        = conv.currentTopicId,
          content        = Vector(ResponseContent.Text(text)),
          state          = EventState.Complete))
      }
      .flatMap(_ => TestSigil.awaitSettled(convId, timeout = 30.seconds))
      .flatMap(_ => Task.sleep(300.millis))

  "A conversation's first turn" should {

    "stream a tool-wrapped reply before the settled Message" in {
      TestSigil.setProvider(Task.pure(new ChunkedRespondProvider))
      val convId = Conversation.id(s"first-turn-respond-${rapid.Unique()}")
      val recorder = new Recorder
      for {
        _  <- TestSigil.newConversation(createdBy = TestUser,
                participants = List(makeAgent(modelId)), conversationId = convId)
        _  =  recorder.take()
        _  <- userTurn(convId, "Stream me the first answer.")
        t1 =  trace(recorder.take(), convId)
        _  <- userTurn(convId, "Stream me the second answer.")
        t2 =  trace(recorder.take(), convId)
      } yield {
        recorder.stop()
        withClue(s"turn 2 control case — $t2: ")(streamedBeforeSettling(t2))
        withClue(s"turn 1 must stream like turn 2 — turn1=$t1 turn2=$t2: ")(streamedBeforeSettling(t1))
      }
    }

    "stream a naked-prose reply before the settled Message" in {
      TestSigil.setProvider(Task.pure(new ChunkedProseProvider))
      val convId = Conversation.id(s"first-turn-prose-${rapid.Unique()}")
      val recorder = new Recorder
      for {
        _  <- TestSigil.newConversation(createdBy = TestUser,
                participants = List(makeAgent(proseModelId)), conversationId = convId)
        _  =  recorder.take()
        _  <- userTurn(convId, "Stream me the opening answer.")
        t1 =  trace(recorder.take(), convId)
        // Turn 2 — the same conversation now holds a settled reply, so
        // the model wraps this one in `respond`.
        _  =  TestSigil.setProvider(Task.pure(new ChunkedRespondProvider))
        _  <- userTurn(convId, "Stream me the follow-up answer.")
        t2 =  trace(recorder.take(), convId)
      } yield {
        recorder.stop()
        withClue(s"turn 2 control case — $t2: ")(streamedBeforeSettling(t2))
        withClue(s"turn 1 prose must stream like turn 2 — turn1=$t1 turn2=$t2: ")(streamedBeforeSettling(t1))
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
