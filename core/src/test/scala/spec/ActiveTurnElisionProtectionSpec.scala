package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextFrame, ContextSummary, Conversation, ToolCallState, TurnInput}
import sigil.conversation.compression.{ContextCompressor, StandardContextCurator}
import sigil.participant.ParticipantId
import sigil.db.Model
import sigil.event.{Event, Message, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, MessageDelta}
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.ToolName
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for sigil #416 — under budget pressure, stage-2c frame
 * elision starved the ACTIVE turn: any Complete tool frame over the
 * threshold was rewritten to a stub, including the frame the agent
 * produced one iteration ago, so the agent could never act on what it
 * had just read ("every read comes back cut off"). And 2c's relief was
 * ephemeral — a chronically-pressured conversation re-elided on every
 * curate forever while stage 3's durable shed never ran.
 *
 * Pins:
 *   1. Active-turn frames (at/after the last user-authored Message)
 *      survive elision; older oversized frames still elide, and the
 *      curate stamps the `_contextPressure` marker.
 *   2. Last-resort backstop: when protection alone can't reach the cap
 *      after the full cascade, a final unprotected elision pass runs —
 *      bounded starvation beats a provider hard-reject.
 *   3. Chronic elision escalates: after `elisionPressureEscalationStreak`
 *      consecutive eliding curates, stage 3 sheds the UNELIDED history
 *      so the conversation durably shrinks.
 *   4. The naked-text decision challenge backs off on a pressured turn —
 *      a context-starved agent narrating is a symptom, not an unfilled
 *      decision.
 */
class ActiveTurnElisionProtectionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "elision-model")
  TestSigil.testModel(modelId)

  private val bigOld = "O123456789" * 800 // ~8K chars — well over the elision threshold
  private val bigNew = "N123456789" * 800

  private def userMsg(convId: Id[Conversation], text: String, at: Long): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      content = Vector(sigil.tool.model.ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(at)
    )

  private def invoke(convId: Id[Conversation], name: String, at: Long): ToolInvoke =
    ToolInvoke(
      toolName = ToolName.parse(name).fold(sys.error, identity),
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      state = EventState.Complete,
      timestamp = Timestamp(at)
    )

  private def toolFrame(ev: ToolInvoke, content: String): ContextFrame.ToolCall =
    ContextFrame.ToolCall(
      toolName = ev.toolName,
      argsJson = "{}",
      callId = ev._id,
      participantId = TestAgent,
      sourceEventId = ev._id,
      state = ToolCallState.Complete(content, Nil)
    )

  private def textFrame(ev: Message, text: String): ContextFrame.Text =
    ContextFrame.Text(text, TestUser, ev._id)

  private def persist(events: List[Event]): Task[Unit] =
    TestSigil.withDB(_.events.transaction(tx => Task.sequence(events.map(tx.upsert)).unit))

  private def frameText(f: ContextFrame): String = f match {
    case t: ContextFrame.Text => t.content
    case tc: ContextFrame.ToolCall => tc.state match {
        case ToolCallState.Complete(c, _) => c
        case ToolCallState.Active => ""
      }
    case other => other.toString
  }

  /**
   * Two-turn fixture: an old user task + old big read, then the active
   * user task + this-turn big read. Returns (conversationId, TurnInput,
   * per-frame token counts).
   */
  private def fixture(prefix: String): Task[(Id[Conversation], TurnInput)] = {
    val convId = Conversation.id(s"$prefix-${rapid.Unique()}")
    val base = 1_700_000_000_000L
    val u1 = userMsg(convId, "old task", base)
    val t1 = invoke(convId, "read_old", base + 1000)
    val u2 = userMsg(convId, "new task", base + 100_000)
    val t2 = invoke(convId, "read_new", base + 101_000)
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val input = TurnInput(
      conversationId = convId,
      frames = Vector(
        textFrame(u1, "old task"),
        toolFrame(t1, bigOld),
        textFrame(u2, "new task"),
        toolFrame(t2, bigNew)
      )
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- persist(List(u1, t1, u2, t2))
    } yield (convId, input)
  }

  private def totalTokens(input: TurnInput): Int =
    input.frames.iterator.map(f => HeuristicTokenizer.count(frameText(f))).sum

  // Deterministic char-math for cap sizing.
  private def curator(keepMin: Int = 4): StandardContextCurator =
    StandardContextCurator(TestSigil, budgetTokenizer = HeuristicTokenizer, keepMinimum = keepMin)

  "Stage-2c elision (sigil #416)" should {

    "protect the active turn, elide older reads, and stamp the pressure marker" in {
      for {
        (_, input) <- fixture("protect")
        // Over budget by a little: eliding ONLY the old read must suffice.
        cap = totalTokens(input) - 100
        out <- curator().refit(input, modelId, List(TestUser, TestAgent), capOverride = Some(cap))
      } yield {
        val texts = out.frames.map(frameText)
        // This-turn read intact (full content, not the 240-char stub) —
        // the agent can act on what it just read.
        texts.exists(_.contains(bigNew)) shouldBe true
        // Exactly one frame elided, and it's the old read (its full
        // content is gone).
        texts.count(_.contains("elided")) shouldBe 1
        texts.exists(_.contains(bigOld)) shouldBe false
        out.extraContext.contains(StandardContextCurator.ContextPressureKey) shouldBe true
      }
    }

    "fall back to unprotected elision when protection alone cannot reach the cap" in {
      for {
        (_, input) <- fixture("last-resort")
        // A cap far below even the protected active-turn read: the
        // cascade must end with EVERYTHING oversized elided rather than
        // returning a result the provider would hard-reject.
        out <- curator().refit(input, modelId, List(TestUser, TestAgent), capOverride = Some(200))
      } yield {
        val texts = out.frames.map(frameText)
        // The active-turn read's FULL content is gone too — the backstop
        // elided it rather than shipping an over-cap request.
        texts.exists(_.contains(bigNew)) shouldBe false
        out.extraContext.contains(StandardContextCurator.ContextPressureKey) shouldBe true
      }
    }

    "escalate to the durable stage-3 shed after consecutive eliding curates" in {
      val convId = Conversation.id(s"escalate-${rapid.Unique()}")
      val base = 1_700_000_000_000L
      val u1 = userMsg(convId, "old task", base)
      val oldReads = (1 to 5).toList.map(i => invoke(convId, s"read_old_$i", base + i * 1000))
      val u2 = userMsg(convId, "new task", base + 100_000)
      val t2 = invoke(convId, "read_new", base + 101_000)
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val input = TurnInput(
        conversationId = convId,
        frames = Vector(textFrame(u1, "old task")) ++
          oldReads.map(toolFrame(_, bigOld)).toVector ++
          Vector(textFrame(u2, "new task"), toolFrame(t2, bigNew))
      )
      // Room for the active read + stubs of the old ones — every curate
      // needs elision, none needs a frame drop.
      val cap = HeuristicTokenizer.count(bigNew) + 800
      // The durable shed drops frames strictly via summary, so escalation
      // requires a real compressor (a NoOp one is deliberately excluded
      // from the escalation path). A stub summary is enough here.
      val stubCompressor = new ContextCompressor {
        override def compress(sigil: _root_.sigil.Sigil,
                              callerModelId: Id[Model],
                              chain: List[ParticipantId],
                              frames: Stream[ContextFrame],
                              conversationId: Id[Conversation]): Task[Option[ContextSummary]] =
          frames.toList.map { fs =>
            Some(ContextSummary(
              text = s"summary of ${fs.size} frames",
              conversationId = conversationId,
              tokenEstimate = 10
            ))
          }
      }
      val cur = StandardContextCurator(
        TestSigil,
        budgetTokenizer = HeuristicTokenizer,
        keepMinimum = 1,
        compressor = stubCompressor)
      def pass(): Task[TurnInput] =
        cur.refit(input, modelId, List(TestUser, TestAgent), capOverride = Some(cap))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- persist(List(u1, u2, t2) ++ oldReads)
        r1 <- pass()
        r2 <- pass()
        r3 <- pass() // streak reaches elisionPressureEscalationStreak (3)
      } yield {
        // Passes 1-2: ephemeral relief — all frames survive, old reads elided.
        r1.frames.size shouldBe input.frames.size
        r2.frames.size shouldBe input.frames.size
        // Pass 3: escalation sheds the unelided history durably.
        r3.frames.size should be < input.frames.size
        // The active-turn read survives escalation with full content.
        r3.frames.map(frameText).exists(_.contains(bigNew)) shouldBe true
      }
    }
  }

  "The naked-text decision challenge (sigil #416)" should {

    "back off on a context-pressured turn — commit the prose instead of challenging" in {
      val convId = Conversation.id(s"pressured-challenge-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val turnInput = TurnInput(
        conversationId = convId,
        extraContext = Map(StandardContextCurator.ContextPressureKey -> "3 frame(s) elided")
      )
      val provider = new Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[Model] = Nil
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] =
          Stream.emits(List[ProviderEvent](
            ProviderEvent.ContentBlockDelta(CallId("naked-0"), "Next I'll finish the sweep."),
            ProviderEvent.Done(StopReason.Complete)
          ))
      }
      val request = ConversationRequest(
        conversationId = convId,
        model = TestSigil.testModel(modelId),
        instructions = Instructions(),
        turnInput = turnInput,
        currentMode = ConversationMode,
        currentTopic = TestTopicEntry,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
        tools = Vector.empty,
        chain = List(TestUser, TestAgent),
        toolResultCacheRef = new AtomicReference(Map.empty)
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        // The prose commits as the terminal reply — no challenge round-trip
        // against an agent whose context was stubs.
        signals.collect { case md: MessageDelta if md.terminalReply => md } should not be empty
        signals.collect {
          case ti: sigil.event.ToolInvoke if ti.toolName.value == Orchestrator.TurnDecisionToolName => ti
        } shouldBe empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
