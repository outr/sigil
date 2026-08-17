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
  CallId,
  GenerationSettings,
  Instructions,
  Provider,
  ProviderCall,
  ProviderEvent,
  ProviderMessage,
  ProviderType,
  StopReason
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*

/**
 * Assistant prose emitted alongside a tool call in one completion — the
 * model narrating its plan before it acts — must survive the turn on every
 * wire family: it settles as a preamble Message ahead of the invoke, becomes
 * a frame, and renders back into the NEXT iteration's request so the model
 * reads its own narration instead of re-deriving the plan.
 *
 * Two wire shapes carry that prose and both are pinned here:
 *
 *   - `ContentBlockDelta` — Anthropic / Google / OpenAI Responses.
 *   - `TextDelta` — every OpenAI-compatible chat-completions backend.
 *
 * The assertions are deliberately identical for both: one behaviour, not a
 * per-wire dialect.
 */
class PreambleProseAlongsideToolCallSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val blockModelId: Id[Model] = Model.id("test", "preamble-block")
  TestSigil.testModel(blockModelId)

  private val textModelId: Id[Model] = Model.id("test", "preamble-text")
  TestSigil.testModel(textModelId)

  private val residueModelId: Id[Model] = Model.id("test", "preamble-residue")
  TestSigil.testModel(residueModelId)

  private val duplicateModelId: Id[Model] = Model.id("test", "preamble-duplicate")
  TestSigil.testModel(duplicateModelId)

  private val preamble = "Let me look up the magic number before I answer."
  private val finalAnswer = "The magic number is 42."

  /** The rendered history the SECOND provider call received — what the model
    * actually reads on the iteration after the preamble turn. */
  private val secondCallMessages = new AtomicReference[Vector[ProviderMessage]](Vector.empty)

  /**
   * One completion carrying prose AND a tool call, then a plain `respond` on
   * every later iteration. `wireProse` picks which wire shape the prose rides
   * — the ONLY difference between the two scenarios. `speakBeforeReplying`
   * moves the prose in front of the `respond` instead, the shape in which it
   * is the answer said twice rather than a narrated plan.
   */
  private final class PreambleThenToolProvider(wireProse: String => List[ProviderEvent],
                                               speakBeforeReplying: Boolean = false) extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val n = calls.incrementAndGet()
      if (n == 2) secondCallMessages.set(input.messages)
      if (n == 1 && speakBeforeReplying) {
        val cid = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(
          wireProse(finalAnswer) ::: List[ProviderEvent](
            ProviderEvent.ToolCallStart(cid, "respond"),
            ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
              topicLabel   = TestTopicEntry.label,
              topicSummary = TestTopicEntry.summary,
              content      = finalAnswer,
              endsTurn     = true
            )),
            ProviderEvent.Done(StopReason.ToolCall)
          )
        )
      } else if (n == 1) {
        val cid = CallId(s"magic-${rapid.Unique()}")
        Stream.emits(
          wireProse(preamble) ::: List[ProviderEvent](
            ProviderEvent.ToolCallStart(cid, GetMagicNumberTool.name.value),
            ProviderEvent.toolCall(cid, GetMagicNumberTool)(GetMagicNumberInput()),
            ProviderEvent.Done(StopReason.ToolCall)
          )
        )
      } else {
        val cid = CallId(s"respond-${rapid.Unique()}")
        Stream.emits(List[ProviderEvent](
          ProviderEvent.ToolCallStart(cid, "respond"),
          ProviderEvent.toolCall(cid, RespondTool)(RespondInput(
            topicLabel   = TestTopicEntry.label,
            topicSummary = TestTopicEntry.summary,
            content      = finalAnswer,
            endsTurn     = true
          )),
          ProviderEvent.Done(StopReason.ToolCall)
        ))
      }
    }
  }

  /** Anthropic / Google / Responses: prose is a content block. */
  private def blockWire(text: String): List[ProviderEvent] = {
    val cid = CallId("preamble-block")
    List(ProviderEvent.ContentBlockStart(cid, "Text", None), ProviderEvent.ContentBlockDelta(cid, text))
  }

  /** OpenAI-compatible chat completions: prose is `delta.content`. */
  private def textWire(text: String): List[ProviderEvent] = List(ProviderEvent.TextDelta(text))

  /**
   * The same wire, mis-splitting a reasoning block: llama.cpp serving
   * Qwen leaves the thinking tail and its closing tag in `delta.content`
   * before the tool call it was reasoning toward. That is monologue, not
   * something the model said, and it must not become a Message.
   */
  private def residueWire(text: String): List[ProviderEvent] = List(
    ProviderEvent.ThinkingDelta("The user wants the magic number. I should look it up."),
    ProviderEvent.TextDelta("42\n"),
    ProviderEvent.TextDelta("</think>"),
    ProviderEvent.TextDelta("\n\n"),
    ProviderEvent.TextDelta(text)
  )

  private def makeAgent(mid: Id[Model]): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = mid,
      toolNames          = CoreTools.coreToolNames ::: List(ToolName("get_magic_number")),
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))
    )

  private def runUserTurn(wireProse: String => List[ProviderEvent],
                          mid: Id[Model],
                          speakBeforeReplying: Boolean = false): Task[Id[Conversation]] = {
    secondCallMessages.set(Vector.empty)
    // One instance for the whole turn — `setProvider` takes its argument
    // by name, so constructing inline would mint a fresh (call-count 1)
    // provider on every iteration.
    val provider = new PreambleThenToolProvider(wireProse, speakBeforeReplying)
    TestSigil.setProvider(Task.pure(provider))
    val convId = Conversation.id(s"preamble-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, participants = List(makeAgent(mid)), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
             participantId  = TestUser,
             conversationId = convId,
             topicId        = TestTopicEntry.id,
             content        = Vector(ResponseContent.Text("What is the magic number?")),
             state          = EventState.Complete
           ))
      _ <- TestSigil.awaitSettled(convId, timeout = 30.seconds)
    } yield convId
  }

  /** Every settled, user-visible agent Message's content, rendered. */
  private def agentReplies(convId: Id[Conversation]): Task[List[String]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.collect {
      case m: Message if m.conversationId == convId && m.participantId == TestAgent &&
        m.role == MessageRole.Standard && m.state == EventState.Complete =>
        m.content.mkString(" ")
    })

  /** Assistant prose the next iteration's rendered request carried back. */
  private def renderedAssistantText: String =
    secondCallMessages.get().collect { case a: ProviderMessage.Assistant => a.content }.mkString("\n")

  private def verify(wireProse: String => List[ProviderEvent], mid: Id[Model], wire: String) =
    runUserTurn(wireProse, mid).flatMap(convId => agentReplies(convId).map { replies =>
      withClue(s"[$wire] settled agent replies: $replies\n") {
        replies.exists(_.contains(preamble)) shouldBe true
      }
      withClue(s"[$wire] rendered second-iteration assistant text: '$renderedAssistantText'\n") {
        renderedAssistantText should include(preamble)
      }
      withClue(s"[$wire] the turn still answered: $replies\n") {
        replies.exists(_.contains(finalAnswer)) shouldBe true
      }
    })

  "Prose emitted alongside a tool call" should {

    "settle as a preamble Message and render into the next iteration (ContentBlockDelta wire)" in
      verify(blockWire, blockModelId, "block")

    "settle as a preamble Message and render into the next iteration (TextDelta wire)" in
      verify(textWire, textModelId, "text")

    "keep the prose but drop the mis-split reasoning tail that shares its wire field" in
      runUserTurn(residueWire, residueModelId).flatMap(convId => agentReplies(convId).map { replies =>
        withClue(s"settled agent replies: $replies\n") {
          replies.exists(_.contains(preamble)) shouldBe true
          replies.exists(_.contains("</think>")) shouldBe false
          replies.exists(_.contains("The user wants the magic number")) shouldBe false
        }
      })

    "say the answer once when the prose beside the call is the reply itself" in
      runUserTurn(textWire, duplicateModelId, speakBeforeReplying = true)
        .flatMap(convId => agentReplies(convId).map { replies =>
          withClue(s"settled agent replies: $replies\n") {
            replies.count(_.contains(finalAnswer)) shouldBe 1
          }
        })
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
