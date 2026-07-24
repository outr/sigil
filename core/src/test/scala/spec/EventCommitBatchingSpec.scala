package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, MessageDelta}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Coverage for the per-iteration batched-commit path on the
 * `events` store.
 *
 * The split store fsyncs (`IndexWriter.commit`) once per
 * transaction. [[sigil.db.SigilDB.withBatchedEvents]] holds one
 * transaction open across many signal writes so the index commits
 * once per scope instead of once per write; [[sigil.db.SigilDB.apply]]
 * and the publish-path event reads route through
 * [[sigil.db.SigilDB.eventsTransaction]] to join the open scope.
 */
class EventCommitBatchingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def textMessage(convId: Id[Conversation]): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      content = Vector(ResponseContent.Text("batched event body")),
      state = EventState.Complete
    )

  private def seedConversation(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, participants = Nil, _id = convId)
    ))).unit

  "withBatchedEvents" should {

    "use exactly one events transaction for many batched signals" in {
      val convId = Conversation.id(s"batch-one-tx-${rapid.Unique()}")
      for {
        _ <- seedConversation(convId)
        before <- TestSigil.withDB(db => Task.pure(db.eventsWriteCommits))
        _ <- TestSigil.withDB(_.withBatchedEvents(convId) {
          Task.sequence((1 to 12).toList.map(_ => TestSigil.withDB(_.apply(textMessage(convId)))))
        })
        after <- TestSigil.withDB(db => Task.pure(db.eventsWriteCommits))
      } yield
        // One transaction (one commit) for the whole scope —
        // the 12 inserts route into the open scope and add none.
        (after - before) shouldBe 1L
    }

    "open one transaction per signal when unbatched" in {
      val convId = Conversation.id(s"batch-unbatched-${rapid.Unique()}")
      for {
        _ <- seedConversation(convId)
        before <- TestSigil.withDB(db => Task.pure(db.eventsWriteCommits))
        _ <- Task.sequence((1 to 12).toList.map(_ => TestSigil.withDB(_.apply(textMessage(convId)))))
        after <- TestSigil.withDB(db => Task.pure(db.eventsWriteCommits))
      } yield
        // No scope active — each `apply` opens its own fresh
        // transaction, exactly the pre-batching behavior.
        (after - before) shouldBe 12L
    }

    "make batched writes durable and queryable through the conversationId index after the scope" in {
      val convId = Conversation.id(s"batch-durable-${rapid.Unique()}")
      val messages = (1 to 8).toList.map(_ => textMessage(convId))
      for {
        _ <- seedConversation(convId)
        _ <- TestSigil.withDB(_.withBatchedEvents(convId) {
          Task.sequence(messages.map(m => TestSigil.withDB(_.apply(m))))
        })
        // Indexed read AFTER the scope closed — the events are
        // committed and the conversationId index resolves them.
        indexed <- TestSigil.withDB(_.events.transaction(
          _.query.filter(_.conversationId === convId.value).toList
        ))
      } yield indexed.map(_._id).toSet shouldBe messages.map(_._id).toSet
    }

    "read-your-writes: a Delta inside the batch settles an Event inserted earlier in the same batch" in {
      val convId = Conversation.id(s"batch-ryw-${rapid.Unique()}")
      // An Active message that a ContentDelta later settles to Complete.
      val active = Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector.empty,
        state = EventState.Active
      )
      for {
        _ <- seedConversation(convId)
        settled <- TestSigil.withDB(_.withBatchedEvents(convId) {
          for {
            _ <- TestSigil.withDB(_.apply(active))
            // The Delta's `tx.get(d.target)` must see the
            // in-batch insert above, otherwise the upsert
            // is a no-op and the row never settles.
            _ <- TestSigil.withDB(_.apply(MessageDelta(
              target = active._id,
              conversationId = convId,
              contentReplacement = Some(Vector(ResponseContent.Text("delta body"))),
              state = Some(EventState.Complete)
            )))
            row <- TestSigil.withDB(_.eventsTransaction(convId)(_.get(active._id)))
          } yield row
        })
      } yield {
        settled.map(_.state) shouldBe Some(EventState.Complete)
        settled.collect { case m: Message => m.content } shouldBe
          Some(Vector(ResponseContent.Text("delta body")))
      }
    }
  }

  "the agent loop" should {

    "commit the events store once per iteration, not once per streamed signal" in {
      // A provider that streams a multi-block `respond` token by
      // token — every `ContentBlockDelta` becomes a settling
      // `MessageDelta` (one `events` write each). Pre-batching each
      // such write opened its own transaction (its own commit);
      // batched, the whole iteration costs one commit.
      val streamedTokens = 40
      val provider = new Provider {
        override def `type`: ProviderType = ProviderType.LlamaCpp
        override def models: List[Model] = Nil
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
          Task.error(new UnsupportedOperationException("no wire"))
        override def call(input: ProviderCall): Stream[ProviderEvent] = {
          // Stream a `respond` content block token by token — each
          // `ContentBlockDelta` settles a `MessageDelta` into the
          // in-flight agent Message (one `events` write per token).
          // The streamed respond is a user-visible terminal reply,
          // so the loop settles in a single iteration.
          val callId = CallId("commit-count-respond")
          val tokens = (1 to streamedTokens).toList.map(i =>
            ProviderEvent.ContentBlockDelta(callId, s"tok$i "))
          Stream.emits(
            ProviderEvent.ContentBlockStart(callId, "respond", None) ::
              tokens :::
              List(ProviderEvent.Done(StopReason.Complete))
          )
        }
      }
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"batch-loop-${rapid.Unique()}")
      val agent = DefaultAgentParticipant(
        id = TestAgent,
        modelId = Model.id("test", "commit-batching"),
        toolNames = CoreTools.coreToolNames,
        instructions = Instructions(),
        generationSettings = GenerationSettings(temperature = Some(0.0))
      )
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      // Poll until the agent's claim lock has settled to Complete —
      // `releaseClaim` settles the AgentState event to
      // `EventState.Complete` as the very last step of the loop, so
      // a Complete claim means the loop is fully released and the
      // commit count is stable.
      def awaitSettled(deadline: Long): Task[Boolean] =
        TestSigil.withDB(_.eventsTransaction(convId)(_.list)).flatMap { all =>
          val claims = all.collect {
            case s: sigil.event.AgentState if s.conversationId == convId => s
          }
          val released = claims.nonEmpty && claims.forall(_.state == EventState.Complete)
          if (released) Task.pure(true)
          else if (System.currentTimeMillis() > deadline) Task.pure(false)
          else Task.sleep(150.millis).flatMap(_ => awaitSettled(deadline))
        }
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        before <- TestSigil.withDB(db => Task.pure(db.eventsWriteCommits))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("Stream a long reply")),
          state = EventState.Complete
        ))
        _ <- awaitSettled(System.currentTimeMillis() + 15000L)
        // Small settle margin so any post-terminate fiber writes
        // (memory extraction is short-circuited here) land.
        _ <- Task.sleep(500.millis)
        after <- TestSigil.withDB(db => Task.pure(db.eventsWriteCommits))
      } yield {
        val commits = after - before
        scribe.info(s"agent turn streamed $streamedTokens content deltas; events write commits: $commits")
        // The turn drained 40 streamed deltas plus the framework's
        // own per-iteration events. Per-write commits would have
        // produced dozens; per-iteration batching keeps it to a
        // small single-digit count (one per agent-loop iteration).
        commits should be <= 6L
        commits should be > 0L
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
