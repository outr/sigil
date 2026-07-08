package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{Sigil, TurnCost}
import sigil.conversation.Conversation
import sigil.db.{Model, ModelPricing}
import sigil.event.{Message, MessageRole}
import sigil.provider.TokenUsage
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

import scala.collection.mutable

/**
 * Sigil #406 — the framework hands a fully-attributed [[TurnCost]] to
 * [[Sigil.onTurnCost]] at the point it folds a turn's USD cost into
 * `Conversation.cost`, so a consumer can persist its own itemized cost ledger
 * (per model · mode · participant) without reconstructing the pieces.
 */
class TurnCostHookSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private val captured = mutable.ListBuffer.empty[TurnCost]

  private val dbName = "TurnCostHookSpec"

  private def deleteDb(): Unit = {
    val p = java.nio.file.Path.of("db", "test", dbName)
    if (java.nio.file.Files.exists(p)) {
      val stream = java.nio.file.Files.walk(p)
      try {
        import scala.jdk.CollectionConverters.*
        stream.iterator().asScala.toList.reverse.foreach(java.nio.file.Files.deleteIfExists(_))
      } finally stream.close()
    }
  }

  private val host: Sigil = {
    deleteDb()
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(s"db/test/$dbName"))))
    new Sigil {
      override type DB = sigil.db.DefaultSigilDB
      override protected def buildDB(directory: Option[java.nio.file.Path],
                                     storeManager: lightdb.store.CollectionManager,
                                     appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
        new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)
      override def modelResolver: sigil.provider.ModelResolver = _ => None
      override protected def participantIds: List[fabric.rw.RW[? <: sigil.participant.ParticipantId]] =
        List(fabric.rw.RW.static(TestUser), fabric.rw.RW.static(TestAgent))
      override def onTurnCost(entry: TurnCost): Task[Unit] =
        Task(captured.synchronized { captured += entry; () })
    }
  }

  private val modelId: Id[Model] = Model.id("test", "cost-model")
  private val model: Model =
    TestSigil.testModel(modelId).copy(pricing = ModelPricing(BigDecimal(2), BigDecimal(4), None, None))
  private val usage = TokenUsage(promptTokens = 1000, completionTokens = 500, totalTokens = 1500)

  "Sigil.onTurnCost (#406)" should {

    "fire once per charged turn with full attribution (participant, model, mode, cost, usage, eventId)" in {
      val convId = Conversation.id(s"turncost-${rapid.Unique()}")
      val conv   = Conversation(topics = TestTopicStack, _id = convId)
      val msg = Message(
        participantId  = TestUser,
        conversationId = convId,
        topicId        = TestTopicEntry.id,
        content        = Vector(ResponseContent.Text("hi")),
        usage          = usage,
        modelId        = Some(modelId),
        state          = EventState.Complete,
        role           = MessageRole.Standard
      )
      captured.clear()
      for {
        _ <- host.instance
        _ <- host.cache.merge(List(model))
        _ <- host.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- host.publish(msg)
      } yield {
        withClue(s"captured=${captured.toList}\n") {
          captured should have size 1
          val tc = captured.head
          tc.conversationId shouldBe convId
          tc.eventId shouldBe msg._id
          tc.participantId shouldBe TestUser
          tc.modelId shouldBe modelId
          tc.mode shouldBe sigil.provider.ConversationMode.name
          tc.usage shouldBe usage
          // cost = fresh-input × prompt-rate + completion × completion-rate; both rates non-zero.
          tc.cost should be > BigDecimal(0)
        }
      }
    }
  }

  "tear down" should {
    "dispose" in host.shutdown.map(_ => succeed)
  }
}
