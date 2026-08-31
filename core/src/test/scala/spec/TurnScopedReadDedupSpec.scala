package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder, ToolCallState, TurnInput}
import sigil.event.ToolInvoke
import sigil.db.Model
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId,
  ConversationMode,
  ConversationRequest,
  GenerationSettings,
  Instructions,
  Provider,
  ProviderCall,
  ProviderEvent,
  ProviderType,
  StopReason
}
import sigil.tool.{
  CachedToolRead,
  DiscoverySpec,
  Effect,
  Freshness,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolContext,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolSpec
}
import spice.http.HttpRequest

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * Sigil #411 — #87 dedups identical tool calls WITHIN one completion, but a
 * retry (no-tool-call retry / recoverable provider-error re-iterate) re-runs the
 * model in a fresh `State`, re-executing the same call. A turn-scoped cache
 * (threaded via `ConversationRequest.toolResultCacheRef`) serves an identical
 * READ from cache across iterations instead of re-executing — but WRITES always
 * run so a retry can never double-submit.
 */
class TurnScopedReadDedupSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "dedup-model")
  TestSigil.testModel(modelId)

  case class PingInput() extends ToolInput derives RW
  ToolInput.register(RW.static(PingInput()))

  /**
   * Test tool that counts executions and returns a fixed result. `ro` toggles
   * the read-only (cacheable) effect.
   */
  private class CountingTool(override val name: ToolName, ro: Boolean, val counter: AtomicInteger) extends Tool {
    type Input = PingInput
    type Output = TextToolOutput
    val io: ToolIO[PingInput, TextToolOutput] = ToolIO.derived[PingInput, TextToolOutput]
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = "counting test tool",
      profile = ToolProfile(effect =
        if (ro) Effect.ReadOnly(Freshness.Stable) else Effect.Mutating(MutationTargeting.none)
      ),
      discovery = DiscoverySpec(keywords = Set("test", "counting"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

    private def executeOutput(input: PingInput, ctx: ToolContext): Task[TextToolOutput] = Task {
      counter.incrementAndGet()
      TextToolOutput("ping-result")
    }
  }

  /**
   * Emits one call to `toolName` with the SAME `PingInput` — so two runs share
   * a canonical args key.
   */
  private class PingProvider(tool: CountingTool) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(s"c-${rapid.Unique()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, tool.name.value),
        ProviderEvent.toolCall(cid, tool)(PingInput()),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def requestFor(convId: Id[Conversation],
                         tool: Tool,
                         cacheRef: AtomicReference[Map[String, CachedToolRead]]): ConversationRequest =
    ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools = Vector(tool),
      chain = List(TestUser, TestAgent),
      toolResultCacheRef = cacheRef
    )

  "Turn-scoped read dedup (#411)" should {

    "execute a readOnly tool ONCE across two iterations sharing the turn cache" in {
      val counter = new AtomicInteger(0)
      val tool = new CountingTool(ToolName("ping_read"), ro = true, counter)
      val cacheRef = new AtomicReference(Map.empty[String, CachedToolRead])
      val convId = Conversation.id(s"dedup-read-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = requestFor(convId, tool, cacheRef)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- Orchestrator.process(TestSigil, new PingProvider(tool), request, conv).toList
        _ <- Orchestrator.process(TestSigil, new PingProvider(tool), request, conv).toList
      } yield withClue(s"cache keys=${cacheRef.get().keys}: ") {
        counter.get() shouldBe 1 // second iteration served from cache
      }
    }

    "hand the served iteration the SAME result the executed iteration produced" in {
      // A served call that settles with an outcome but no payload leaves the
      // model reading an empty result for a call it just re-issued — the
      // strongest possible invitation to issue it a third time. The served
      // invoke must replay the original's payload so both render identically.
      val counter = new AtomicInteger(0)
      val tool = new CountingTool(ToolName("ping_served"), ro = true, counter)
      val cacheRef = new AtomicReference(Map.empty[String, CachedToolRead])
      val convId = Conversation.id(s"dedup-served-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = requestFor(convId, tool, cacheRef)
      def iterate: Task[Unit] =
        Orchestrator.process(TestSigil, new PingProvider(tool), request, conv).toList.flatMap { signals =>
          signals.foldLeft(Task.unit)((acc, signal) => acc.flatMap(_ => TestSigil.publish(signal).map(_ => ())))
        }
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- iterate
        _ <- iterate
        events <- TestSigil.withDB(_.conversationEventsConsistent(convId))
      } yield {
        val rendered = events.collect {
          case ti: ToolInvoke if ti.toolName == tool.name =>
            FrameBuilder.computeFrame(ti).collect {
              case tc: ContextFrame.ToolCall =>
                tc.state match {
                  case ToolCallState.Complete(content, _) => content
                  case ToolCallState.Active => "(active)"
                }
            }.getOrElse("(no frame)")
        }.toList
        withClue(s"rendered results across the two iterations: $rendered: ") {
          rendered.size shouldBe 2
          rendered.distinct.size shouldBe 1
          rendered.head should include("ping-result")
        }
      }
    }

    "execute a WRITE (non-readOnly) tool every iteration — never served from cache" in {
      val counter = new AtomicInteger(0)
      val tool = new CountingTool(ToolName("ping_write"), ro = false, counter)
      val cacheRef = new AtomicReference(Map.empty[String, CachedToolRead])
      val convId = Conversation.id(s"dedup-write-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = requestFor(convId, tool, cacheRef)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- Orchestrator.process(TestSigil, new PingProvider(tool), request, conv).toList
        _ <- Orchestrator.process(TestSigil, new PingProvider(tool), request, conv).toList
      } yield counter.get() shouldBe 2 // writes always execute — no double-submit hazard from cache
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
