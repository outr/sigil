package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, TopicEntry}

/**
 * Coverage for [[sigil.Sigil.totalCostFor]] — sub-conversation cost
 * rollup. Worker delegation creates a hierarchy, and apps showing
 * total cost for a top-level conversation want the inclusive
 * figure (own cost + recursive sum of every descendant's cost).
 */
class TotalCostForSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def conv(suffix: String,
                   cost: BigDecimal,
                   parent: Option[lightdb.id.Id[Conversation]] = None): Conversation =
    Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      cost = cost,
      parentConversationId = parent,
      _id = Conversation.id(s"cost-rollup-$suffix-${rapid.Unique()}")
    )

  "totalCostFor" should {

    "return zero for a conversation that doesn't exist" in
      TestSigil.totalCostFor(Conversation.id("missing")).map(_ shouldBe BigDecimal(0))

    "return the conversation's own cost when it has no children" in {
      val solo = conv("solo", BigDecimal(0.42))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(solo)))
        total <- TestSigil.totalCostFor(solo._id)
      } yield total shouldBe BigDecimal(0.42)
    }

    "sum a parent + a single direct child" in {
      val parent = conv("parent", BigDecimal(0.10))
      val child = conv("child", BigDecimal(0.25), parent = Some(parent._id))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(parent)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(child)))
        total <- TestSigil.totalCostFor(parent._id)
      } yield total shouldBe BigDecimal(0.35)
    }

    "recurse through grand-children (worker delegating sub-workers)" in {
      val gp = conv("gp", BigDecimal(0.05))
      val parent = conv("p", BigDecimal(0.10), parent = Some(gp._id))
      val childA = conv("ca", BigDecimal(0.20), parent = Some(parent._id))
      val childB = conv("cb", BigDecimal(0.30), parent = Some(parent._id))
      val grand = conv("gc", BigDecimal(0.40), parent = Some(childA._id))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(gp)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(parent)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(childA)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(childB)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(grand)))
        total <- TestSigil.totalCostFor(gp._id)
      } yield total shouldBe BigDecimal(0.05) + BigDecimal(0.10) + BigDecimal(0.20) + BigDecimal(0.30) + BigDecimal(0.40)
    }

    "isolate sibling subtrees" in {
      val sharedRoot = conv("root", BigDecimal(0))
      val unrelated = conv("unrelated", BigDecimal(99)) // separate top-level convo, no parent linkage
      val child = conv("child", BigDecimal(1), parent = Some(sharedRoot._id))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(sharedRoot)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(unrelated)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(child)))
        total <- TestSigil.totalCostFor(sharedRoot._id)
      } yield total shouldBe BigDecimal(1) // 0 + 1; unrelated's 99 stays in its own tree
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
