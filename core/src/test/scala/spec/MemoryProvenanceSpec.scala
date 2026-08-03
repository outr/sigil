package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, MemorySource, ToolCallState, UpsertMemoryResult}
import sigil.conversation.compression.extract.{ExtractionTurn, MemoryExtractor}
import sigil.db.Model
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tool.ToolName
import sigil.tool.util.SleepTool

/**
 * Coverage for event-grain memory provenance
 * ([[ContextMemory.sourceEventIds]]):
 *
 *   - a persisted memory carries the extraction window's event ids;
 *   - a `Refreshed` keyed upsert unions the prior record's ids with
 *     the new extraction's;
 *   - a `Versioned` keyed upsert starts the new record with only the
 *     new extraction's ids while the archived version keeps its own;
 *   - the default [[MemoryExtractor.extractFromFrames]] hands the
 *     slice's source event ids to [[MemoryExtractor.extractTurn]].
 */
class MemoryProvenanceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def eventId(tag: String): Id[Event] = Id[Event](s"$tag-${rapid.Unique()}")

  private def keyed(fact: String, key: String, ids: List[Id[Event]]): ContextMemory = ContextMemory(
    fact = fact,
    label = key,
    summary = fact,
    source = MemorySource.Compression,
    spaceId = MemoryTestSpace,
    key = Some(key),
    sourceEventIds = ids
  )

  "memory provenance" should {
    val e1 = eventId("e1")
    val e2 = eventId("e2")
    val e3 = eventId("e3")
    val e4 = eventId("e4")
    val key = s"user.provenance-${rapid.Unique()}"

    "stamp extraction window ids on the stored record" in {
      TestSigil.reset()
      TestSigil.upsertMemoryByKey(keyed("The deploy target is us-east-1.", key, List(e1, e2))).map { result =>
        result should be(a[UpsertMemoryResult.Stored])
        result.memory.sourceEventIds should be(List(e1, e2))
      }
    }

    "union ids on a Refreshed upsert" in {
      TestSigil.upsertMemoryByKey(keyed("The deploy target is us-east-1.", key, List(e2, e3))).map { result =>
        result should be(a[UpsertMemoryResult.Refreshed])
        result.memory.sourceEventIds should be(List(e1, e2, e3))
      }
    }

    "replace ids on a Versioned upsert while the archived version keeps its own" in {
      TestSigil.upsertMemoryByKey(keyed("The deploy target is eu-west-1.", key, List(e4))).flatMap {
        case UpsertMemoryResult.Versioned(fresh, archived) =>
          fresh.sourceEventIds should be(List(e4))
          archived.sourceEventIds should be(List(e1, e2, e3))
          Task.pure(succeed)
        case other => Task.pure(fail(s"expected Versioned, got $other"))
      }
    }

    "flow the frame slice's event ids through extractFromFrames" in {
      class RecordingExtractor extends MemoryExtractor {
        @volatile var lastTurn: Option[ExtractionTurn] = None
        override def extract(sigil: Sigil,
                             conversationId: Id[Conversation],
                             modelId: Id[Model],
                             chain: List[ParticipantId],
                             userMessage: String,
                             agentResponse: String): Task[List[ContextMemory]] = Task.pure(Nil)
        override def extractTurn(sigil: Sigil,
                                 conversationId: Id[Conversation],
                                 modelId: Id[Model],
                                 chain: List[ParticipantId],
                                 turn: ExtractionTurn): Task[List[ContextMemory]] = {
          lastTurn = Some(turn)
          Task.pure(Nil)
        }
      }
      val recorder = new RecordingExtractor
      val f1 = eventId("f1")
      val f2 = eventId("f2")
      val frames: Vector[ContextFrame] = Vector(
        ContextFrame.Text("I moved the deploy to eu-west-1", TestUser, f1),
        ContextFrame.Text("Noted — deploy target updated.", TestAgent, f2)
      )
      recorder.extractFromFrames(
        sigil = TestSigil,
        conversationId = Conversation.id(s"prov-${rapid.Unique()}"),
        modelId = Id[Model]("test-model"),
        chain = List(TestUser, TestAgent),
        frames = frames
      ).map { _ =>
        val turn = recorder.lastTurn
        turn.isDefined should be(true)
        turn.get.sourceEventIds should be(List(f1, f2))
      }
    }

    "carry the shed slice's settled mutations as extraction evidence" in {
      class RecordingExtractor extends MemoryExtractor {
        @volatile var lastTurn: Option[ExtractionTurn] = None
        override def extract(sigil: Sigil,
                             conversationId: Id[Conversation],
                             modelId: Id[Model],
                             chain: List[ParticipantId],
                             userMessage: String,
                             agentResponse: String): Task[List[ContextMemory]] = Task.pure(Nil)
        override def extractTurn(sigil: Sigil,
                                 conversationId: Id[Conversation],
                                 modelId: Id[Model],
                                 chain: List[ParticipantId],
                                 turn: ExtractionTurn): Task[List[ContextMemory]] = {
          lastTurn = Some(turn)
          Task.pure(Nil)
        }
      }
      val recorder = new RecordingExtractor
      val mutating = eventId("m1")
      val readOnly = eventId("m2")
      def call(toolName: ToolName, id: Id[Event]): ContextFrame.ToolCall = ContextFrame.ToolCall(
        toolName = toolName,
        argsJson = "{}",
        callId = id,
        participantId = TestAgent,
        sourceEventId = id,
        state = ToolCallState.Complete("done")
      )
      val frames: Vector[ContextFrame] = Vector(
        ContextFrame.Text("Apply the change.", TestUser, eventId("m0")),
        call(MutatingSpecTool.name, mutating),
        call(SleepTool.name, readOnly)
      )
      recorder.extractFromFrames(
        sigil = TestSigil,
        conversationId = Conversation.id(s"prov-mut-${rapid.Unique()}"),
        modelId = Id[Model]("test-model"),
        chain = List(TestUser, TestAgent),
        frames = frames
      ).map { _ =>
        // Only the mutating tool counts as evidence; a read-only call
        // says nothing about whether the slice changed the world.
        recorder.lastTurn.map(_.settledMutations) should be(Some(List(MutatingSpecTool.name)))
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
