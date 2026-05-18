package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, XmlToolCallSanitizer
}
import sigil.signal.{Signal, XmlToolCallLeak}
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.model.{RespondInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil bug #225 — defensive handling of XML-format
 * tool-call syntax that some models occasionally embed inside
 * `respond.content` (or similar typed content fields) when
 * mode-mixing the JSON `tool_calls` protocol with their training-
 * time XML conventions.
 *
 * Two layers under test:
 *
 *  1. Preventive prompt anchor in `Provider.renderSystem` warning
 *     the model not to emit `<tool_call>` / `<function=…>` inside
 *     content strings (fires whenever the conversation has tools).
 *  2. Defensive `XmlToolCallSanitizer` applied at the
 *     `RespondInput.content` boundary: replaces matched XML spans
 *     with a placeholder before the Message is published, and
 *     publishes an `XmlToolCallLeak` Notice so operators can
 *     diagnose which model + context triggered the leak.
 */
class XmlToolCallLeakSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "xml-leak-model")

  "XmlToolCallSanitizer (sigil bug #225 — layer 2 unit)" should {

    "remove a complete `<tool_call>` span and leave the surrounding text intact" in {
      val raw = "I'll help.\n\n<tool_call><function=find_capability><parameter=keywords>foo</parameter></function></tool_call>"
      val r = XmlToolCallSanitizer.sanitize(raw)
      r.content should startWith("I'll help.\n\n")
      r.content should include(XmlToolCallSanitizer.Placeholder)
      r.content should not include "<tool_call>"
      r.leakedSpans.size shouldBe 1
      r.leakedSpans.head should startWith("<tool_call>")
      Task.pure(succeed)
    }

    "remove a bare `<function=…>` span" in {
      val raw = "Header text\n<function=foo>arg=bar</function>\nTrailing."
      val r = XmlToolCallSanitizer.sanitize(raw)
      r.content should not include "<function="
      r.content should include(XmlToolCallSanitizer.Placeholder)
      r.leakedSpans.size shouldBe 1
      Task.pure(succeed)
    }

    "sanitize an unterminated `<tool_call>` span (model cut off mid-XML)" in {
      val raw = "Some intro.\n<tool_call><function=foo><parameter=keywords>bind workspace"
      val r = XmlToolCallSanitizer.sanitize(raw)
      r.content should not include "<tool_call>"
      r.content should include(XmlToolCallSanitizer.Placeholder)
      r.leakedSpans.size shouldBe 1
      Task.pure(succeed)
    }

    "pass non-leak content through unchanged (mentions the words but no XML)" in {
      val raw = "This response discusses tool calls and the word function but uses no XML tags."
      val r = XmlToolCallSanitizer.sanitize(raw)
      r.content shouldBe raw
      r.leakedSpans shouldBe empty
      Task.pure(succeed)
    }
  }

  "Provider.renderSystem prompt anchor (sigil bug #225 — layer 1)" should {

    "include the XML-leak prohibition and the `endsTurn = false` alternative when tools are present" in {
      val captured = new ConcurrentLinkedQueue[ProviderCall]()
      TestSigil.setProvider(Task.pure(new RecordingProvider(captured)))
      val convId = Conversation.id(s"xml-leak-prompt-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("hi")),
               state          = sigil.signal.EventState.Complete
             ))
        _ <- Task.sleep(1.second)
      } yield {
        captured.asScala.toList should not be empty
        val systemPrompt = captured.asScala.toList.head.system
        // Must mention the specific leak shapes — the model needs a
        // concrete prohibition to pattern-match against.
        systemPrompt should include("<tool_call>")
        systemPrompt should include("<function=")
        // Must name the legitimate alternative the model should reach
        // for instead.
        systemPrompt should include("endsTurn")
      }
    }
  }

  "RespondTool.executeTyped end-to-end (sigil bug #225 — layer 2 integration)" should {

    "sanitize XML leak in `respond.content` and publish an XmlToolCallLeak notice" in {
      TestSigil.setProvider(Task.pure(new RespondingWithLeakProvider))
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestSigil.signals
        .takeWhile(_ => running)
        .evalMap(s => Task { recorded.add(s); () })
        .drain
        .startUnit()
      Thread.sleep(100)
      val convId = Conversation.id(s"xml-leak-e2e-${rapid.Unique()}")
      val agent  = makeAgent()
      val conv   = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
               participantId  = TestUser,
               conversationId = convId,
               topicId        = TestTopicEntry.id,
               content        = Vector(ResponseContent.Text("test")),
               state          = sigil.signal.EventState.Complete
             ))
        _ <- Task.sleep(2.seconds)
        _ <- Task { running = false; () }
      } yield {
        val signals = recorded.asScala.toList
        val agentMessages = signals.collect { case m: Message if m.participantId == TestAgent => m }
        agentMessages should not be empty
        val agentText = agentMessages.flatMap(_.content).collect {
          case ResponseContent.Text(t)     => t
          case ResponseContent.Markdown(m) => m
        }.mkString
        // Sanitization replaced the XML span with the placeholder.
        agentText should include(XmlToolCallSanitizer.Placeholder)
        agentText should not include "<tool_call>"
        // The diagnostic notice fired with the model id + first excerpt.
        val leaks = signals.collect { case n: XmlToolCallLeak => n }
        leaks.size should be >= 1
        leaks.head.conversationId shouldBe convId
        leaks.head.modelId shouldBe Some(modelId)
        leaks.head.leakedSpanCount should be >= 1
        leaks.head.firstLeakedExcerpt should startWith("<tool_call>")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = CoreTools.coreToolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  /** Provider that records every incoming `ProviderCall` so the test
    * can inspect the rendered system prompt; emits a clean `respond`
    * to settle the agent loop without producing any XML leak. */
  private final class RecordingProvider(captured: ConcurrentLinkedQueue[ProviderCall]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      captured.add(input)
      val cid = CallId("respond-clean")
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          cid,
          RespondInput(topicLabel = "T", topicSummary = "summary", content = "ok", endsTurn = true)
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Provider that emits a `respond` whose `content` carries the
    * field-repro XML leak — the agent's apparent intent was to do a
    * `find_capability` follow-up via XML tag syntax. The sanitizer
    * + notice must catch this before it reaches the user. */
  private final class RespondingWithLeakProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("respond-leak")
      Stream.emits(List[ProviderEvent](
        ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
        ProviderEvent.ToolCallComplete(
          cid,
          RespondInput(
            topicLabel   = "Project Connection",
            topicSummary = "Connecting the Sigil project workspace",
            content      =
              "I'll help you connect.\n\n<tool_call>\n<function=find_capability>\n<parameter=keywords>\nbind workspace set project root\n</parameter>\n</function>\n</tool_call>",
            endsTurn     = true
          )
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }
}
