package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.TurnContext
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.event.Message
import sigil.tool.core.{RespondCardTool, RespondCardsTool}
import sigil.tool.model.{Card, RespondCardInput, RespondCardsInput, ResponseContent}

/**
 * Round-trips the `respond_card` / `respond_cards` tools through their
 * `execute` paths and confirms the emitted Message carries Card blocks
 * with all sections preserved — including a recursive nested-card case
 * (Card whose `sections` contain another Card) so the recursion in the
 * `ResponseContent.Card` shape is verified end-to-end. Validates the
 * shape contract apps and renderers depend on; does not exercise the
 * provider/grammar layer.
 */
class CardCompositionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def turnContextFor(convId: Id[Conversation]): TurnContext = {
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    TurnContext(
      sigil = TestSigil,
      chain = List(TestUser, TestAgent),
      conversation = Conversation(topics = TestTopicStack, _id = convId),
      conversationView = view,
      turnInput = TurnInput(view)
    )
  }

  "RespondCardTool" should {
    "emit one Message carrying the submitted Card with all sections preserved" in {
      val convId = Conversation.id(s"card-single-${rapid.Unique()}")
      val card: ResponseContent.Card = Card(
        sections = Vector(
          ResponseContent.Heading("Status"),
          ResponseContent.Field("CPU", "42%"),
          ResponseContent.Field("Memory", "3.4 GB"),
          ResponseContent.ItemList(List("Service A", "Service B"), ordered = false)
        ),
        title = Some("Server Health"),
        kind = Some("metric")
      )
      val events = RespondCardTool
        .execute(RespondCardInput("Status Dashboard", "Server health snapshot.", card), turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val m = list.head.asInstanceOf[Message]
        m.content should have size 1
        val emitted = m.content.head.asInstanceOf[ResponseContent.Card]
        emitted.title shouldBe Some("Server Health")
        emitted.kind shouldBe Some("metric")
        Card.typedSections(emitted) should have size 4
      }
    }

    "preserve nested Card structure (Card.sections contains another Card)" in {
      val convId = Conversation.id(s"card-nested-${rapid.Unique()}")
      val inner: ResponseContent.Card = Card(
        sections = Vector(ResponseContent.Field("nested", "yes")),
        title = Some("Inner")
      )
      val outer: ResponseContent.Card = Card(
        sections = Vector(
          ResponseContent.Heading("Outer Header"),
          inner
        ),
        title = Some("Outer")
      )
      val events = RespondCardTool
        .execute(RespondCardInput("Nested Demo", "Card inside a card.", outer), turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val m = list.head.asInstanceOf[Message]
        val outerCard = m.content.head.asInstanceOf[ResponseContent.Card]
        outerCard.title shouldBe Some("Outer")
        val outerSections = Card.typedSections(outerCard)
        outerSections(1) shouldBe a[ResponseContent.Card]
        val innerCard = outerSections(1).asInstanceOf[ResponseContent.Card]
        innerCard.title shouldBe Some("Inner")
        Card.typedSections(innerCard).head shouldBe ResponseContent.Field("nested", "yes")
      }
    }
  }

  "RespondCardsTool" should {
    "emit one Message containing every submitted card in order" in {
      val convId = Conversation.id(s"card-multi-${rapid.Unique()}")
      val cards: Vector[ResponseContent.Card] = Vector(
        Card(Vector(ResponseContent.Heading("First")), title = Some("A")),
        Card(Vector(ResponseContent.Heading("Second")), title = Some("B")),
        Card(Vector(ResponseContent.Heading("Third")), title = Some("C"))
      )
      val events = RespondCardsTool
        .execute(RespondCardsInput("Search Results", "Three result cards.", cards), turnContextFor(convId))
        .toList
      events.map { list =>
        list should have size 1
        val m = list.head.asInstanceOf[Message]
        m.content should have size 3
        m.content.collect { case c: ResponseContent.Card => c.title }.flatten shouldBe Vector("A", "B", "C")
      }
    }
  }
}
