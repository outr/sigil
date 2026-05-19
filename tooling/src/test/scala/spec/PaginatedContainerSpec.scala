package spec

import fabric.{Json, Obj, Str}
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TopicEntry, TurnInput}
import sigil.event.{Event, Message, MessageRole}
import sigil.maintenance.ConversationContainerCleanupTask
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.tool.output.ToolOutputNode
import sigil.tooling.container.{
  ContainerPredicate, ContainerSupport,
  CreateContainerInput, CreateContainerTool,
  FilterContainerInput, FilterContainerTool,
  PinContainerInput, PinContainerTool
}
import sigil.tooling.dispatch.{DispatchWorkersInput, DispatchWorkersOutput, DispatchWorkersTool}

import scala.concurrent.duration.*

/**
 * Acceptance for the container abstraction and the conversation-
 * level cleanup pass. Covers the five test cases on the bug:
 *
 *  1. CreateContainer + dispatch_workers round-trips.
 *  2. FilterContainer narrows a container into a new derived
 *     container (source untouched).
 *  3. No per-row TTL: create a container, advance the clock, the
 *     container is still readable.
 *  4. Conversation-level cleanup at age threshold: containers in
 *     a stale conversation gone, active-conversation containers
 *     untouched.
 *  5. `pin_container` skips conversation cleanup: pinned survives,
 *     unpinned doesn't.
 */
class PaginatedContainerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  DispatchTestSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 60.seconds

  private def newConversation(idSuffix: String): Conversation = {
    val convId = Conversation.id(s"paginated-container-$idSuffix-${rapid.Unique()}")
    val conv = Conversation(
      topics = List(TopicEntry(DispatchTestTopicId, "test", "test")),
      _id    = convId
    )
    DispatchTestSigil.withDB(_.conversations.transaction(_.upsert(conv))).sync()
    conv
  }

  private def turnContextFor(conv: Conversation): TurnContext =
    TurnContext(
      sigil               = DispatchTestSigil,
      chain               = List(DispatchTestUser),
      conversation        = conv,
      turnInput           = TurnInput(ConversationView(conversationId = conv._id)),
      currentToolInvokeId = Some(Event.id())
    )

  /** Persist a synthetic container by seeding `ToolOutputNode`
    * rows directly — lets the cleanup-cases set explicit `created`
    * timestamps instead of advancing a real clock. */
  private def seedContainer(conversation: Conversation,
                            items: List[Json],
                            created: Timestamp,
                            pinned: Boolean = false): Id[Event] = {
    val callId = Event.id()
    val rows = items.zipWithIndex.map { case (payload, ordinal) =>
      ToolOutputNode(
        conversationId = conversation._id,
        callId         = callId,
        referenceId    = callId.value,
        level          = 0,
        ordinal        = ordinal,
        hasChildren    = false,
        payload        = payload,
        created        = created,
        modified       = created,
        pinned         = pinned
      )
    }
    DispatchTestSigil.withDB(_.toolOutputs.transaction { tx =>
      Task.sequence(rows.map(tx.upsert)).unit
    }).sync()
    callId
  }

  // --------------- the five cases ---------------

  "create_container + dispatch_workers round-trip" should {
    "produce one worker outcome per item when dispatch_workers consumes a 3-item container with confirmed=true" in {
      DispatchTestSigil.reset()
      val ctx = turnContextFor(newConversation("dispatch"))
      val items: List[Json] = List(
        Obj("name" -> Str("a")),
        Obj("name" -> Str("b")),
        Obj("name" -> Str("c"))
      )
      CreateContainerTool.invoke(CreateContainerInput(items), ctx).flatMap { container =>
        container.itemCount shouldBe 3
        val tool = new DispatchWorkersTool(scriptExecutor = Some(new sigil.script.ScalaScriptExecutor()))
        val input = DispatchWorkersInput(
          itemsId   = container.itemsId,
          action    = "items.head(\"name\")",
          confirmed = true
        )
        tool.invoke(input, ctx).map {
          case d: DispatchWorkersOutput.DispatchResult =>
            d.totalItems shouldBe 3
            d.successCount shouldBe 3
            d.perItem.size shouldBe 3
            all(d.perItem.map(_.result.isRight)) shouldBe true
          case other => fail(s"expected DispatchResult, got $other")
        }
      }
    }
  }

  "filter_container" should {
    "narrow a source container into a new derived container without mutating the source" in {
      DispatchTestSigil.reset()
      val ctx = turnContextFor(newConversation("filter"))
      val items: List[Json] = (1 to 100).toList.map { i =>
        Obj("name" -> Str(s"item-$i"), "category" -> Str(if (i % 3 == 0) "foo" else "bar"))
      }
      CreateContainerTool.invoke(CreateContainerInput(items), ctx).flatMap { source =>
        FilterContainerTool.invoke(
          FilterContainerInput(
            sourceId  = source.itemsId,
            predicate = ContainerPredicate.Contains("\"foo\"")
          ),
          ctx
        ).flatMap { derived =>
          derived.itemsId.value should not equal source.itemsId.value
          // Source untouched.
          ContainerSupport.resolveItems(ctx, source.itemsId, None, None).flatMap { sourceItems =>
            sourceItems.size shouldBe 100
            // Derived has the subset.
            ContainerSupport.resolveItems(ctx, derived.itemsId, None, None).map { derivedItems =>
              derivedItems.size should be > 0
              derivedItems.size should be < 100
              all(derivedItems.map(_.asInstanceOf[Obj].value("category"))) shouldBe Str("foo")
            }
          }
        }
      }
    }
  }

  "ToolOutputNode" should {
    "remain readable after the prior 30-minute TTL window would have elapsed" in {
      DispatchTestSigil.reset()
      val ctx = turnContextFor(newConversation("ttl-gone"))
      val items: List[Json] = List(Str("alpha"), Str("beta"))
      CreateContainerTool.invoke(CreateContainerInput(items), ctx).flatMap { created =>
        // Rewrite the container rows' `created` to 24h in the past
        // — the old per-row TTL would have reclaimed everything by
        // now (the default was 30 min). With per-row TTL gone, the
        // container is still resolvable. The conversation-level
        // cleanup doesn't fire here because (a) the test doesn't
        // run it and (b) the conversation is brand new — its
        // "most recent activity" is contemporary with the
        // backdated rows, so the 30-day age window wouldn't trip
        // either.
        val backdated = Timestamp(System.currentTimeMillis() - 24L * 60L * 60L * 1000L)
        DispatchTestSigil.withDB(_.toolOutputs.transaction { tx =>
          tx.list.flatMap { all =>
            val convRows = all.toList.filter(_.conversationId == ctx.conversation._id)
            Task.sequence(convRows.map(r => tx.upsert(r.copy(created = backdated, modified = backdated)))).unit
          }
        }).flatMap { _ =>
          ContainerSupport.resolveItems(ctx, created.itemsId, None, None).map { readBack =>
            readBack shouldBe items
          }
        }
      }
    }
  }

  "ConversationContainerCleanupTask (age)" should {
    "prune stale-conversation containers while leaving active-conversation containers untouched" in {
      DispatchTestSigil.reset()
      val active = newConversation("active")
      val stale  = newConversation("stale")
      val now = System.currentTimeMillis()
      // Active conversation: anchor a recent activity event.
      DispatchTestSigil.withDB(_.events.transaction { tx =>
        tx.upsert(Message(
          participantId  = DispatchTestUser,
          conversationId = active._id,
          topicId        = active.currentTopicId,
          role           = MessageRole.Standard,
          content        = Vector(ResponseContent.Text("recent activity")),
          state          = EventState.Complete,
          timestamp      = Timestamp(now)
        )).unit
      }).sync()
      // Stale conversation: anchor activity 100 days ago.
      val staleTs = now - 100L * 24L * 60L * 60L * 1000L
      DispatchTestSigil.withDB(_.events.transaction { tx =>
        tx.upsert(Message(
          participantId  = DispatchTestUser,
          conversationId = stale._id,
          topicId        = stale.currentTopicId,
          role           = MessageRole.Standard,
          content        = Vector(ResponseContent.Text("old activity")),
          state          = EventState.Complete,
          timestamp      = Timestamp(staleTs)
        )).unit
      }).sync()
      // Seed containers: active recent, stale 200 days in the past.
      seedContainer(active, List(Str("active-1"), Str("active-2")), Timestamp(now))
      val staleContainerTs = now - 200L * 24L * 60L * 60L * 1000L
      seedContainer(stale, List(Str("stale-1"), Str("stale-2")), Timestamp(staleContainerTs))

      val task = ConversationContainerCleanupTask(
        interval  = 1.hour,
        ageWindow = 30.days,
        sizeLimit = 100000
      )
      task.runOnce(DispatchTestSigil).flatMap { _ =>
        DispatchTestSigil.withDB(_.toolOutputs.transaction(_.list)).map { rows =>
          val activeRows = rows.toList.filter(_.conversationId == active._id)
          val staleRows  = rows.toList.filter(_.conversationId == stale._id)
          activeRows.size shouldBe 2
          staleRows shouldBe empty
        }
      }
    }
  }

  "ConversationContainerCleanupTask (pin)" should {
    "skip pinned containers and prune unpinned ones in the same stale conversation" in {
      DispatchTestSigil.reset()
      val conv = newConversation("pinned")
      val now = System.currentTimeMillis()
      val activityTs = now - 100L * 24L * 60L * 60L * 1000L
      DispatchTestSigil.withDB(_.events.transaction { tx =>
        tx.upsert(Message(
          participantId  = DispatchTestUser,
          conversationId = conv._id,
          topicId        = conv.currentTopicId,
          role           = MessageRole.Standard,
          content        = Vector(ResponseContent.Text("stale activity")),
          state          = EventState.Complete,
          timestamp      = Timestamp(activityTs)
        )).unit
      }).sync()
      val rowsTs = now - 200L * 24L * 60L * 60L * 1000L
      val pinnedCallId   = seedContainer(conv, List(Str("pinned-1"), Str("pinned-2")), Timestamp(rowsTs), pinned = true)
      val unpinnedCallId = seedContainer(conv, List(Str("unpinned-1"), Str("unpinned-2")), Timestamp(rowsTs), pinned = false)

      val task = ConversationContainerCleanupTask(
        interval  = 1.hour,
        ageWindow = 30.days,
        sizeLimit = 100000
      )
      task.runOnce(DispatchTestSigil).flatMap { _ =>
        DispatchTestSigil.withDB(_.toolOutputs.transaction(_.list)).map { rows =>
          val convRows     = rows.toList.filter(_.conversationId == conv._id)
          val pinnedRows   = convRows.filter(_.callId == pinnedCallId)
          val unpinnedRows = convRows.filter(_.callId == unpinnedCallId)
          pinnedRows.size shouldBe 2
          unpinnedRows shouldBe empty
        }
      }
    }
  }

  "pin_container tool" should {
    "toggle the pinned flag on every row of a container" in {
      DispatchTestSigil.reset()
      val ctx = turnContextFor(newConversation("pin-tool"))
      val items: List[Json] = List(Str("x"), Str("y"), Str("z"))
      CreateContainerTool.invoke(CreateContainerInput(items), ctx).flatMap { created =>
        PinContainerTool.invoke(PinContainerInput(created.itemsId), ctx).flatMap { pinned =>
          pinned.rowsAffected shouldBe 3
          // Re-pinning is idempotent.
          PinContainerTool.invoke(PinContainerInput(created.itemsId), ctx).flatMap { again =>
            again.rowsAffected shouldBe 0
            ContainerSupport.readItems(DispatchTestSigil, ctx.conversation._id, created.itemsId).map { rows =>
              all(rows.map(_.pinned)) shouldBe true
            }
          }
        }
      }
    }
  }

  "tear down" should {
    "dispose DispatchTestSigil" in DispatchTestSigil.shutdown.map(_ => succeed)
  }
}
