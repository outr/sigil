package spec

import fabric.rw.*
import fabric.{Json, arr, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, ContextKey, TurnInput}

/**
 * [[ContextKey]] is opaque over `String`, but its serialized shape is
 * the one the original case class produced and is already on disk in
 * every persisted `extraContext`. The literals below are hand-written
 * legacy JSON, not a re-derivation — they fail if the encoding drifts
 * in either direction.
 */
class ContextKeyWireFormatSpec extends AnyWordSpec with Matchers {

  /** Exactly what the case-class-derived RW wrote. */
  private val legacyKey: Json = obj("value" -> str("_budgetWarning"))

  /** A `Map[ContextKey, String]` rides fabric's array-of-pairs encoding
    * because the key's definition is an object, not a string. */
  private val legacyMap: Json = arr(
    obj("key" -> obj("value" -> str("_budgetWarning")), "value" -> str("31% of the window")),
    obj("key" -> obj("value" -> str("focus")), "value" -> str("the config sweep"))
  )

  private val keys: Map[ContextKey, String] = Map(
    ContextKey.BudgetWarning -> "31% of the window",
    ContextKey("focus") -> "the config sweep"
  )

  "ContextKey" should {
    "serialize as the legacy object literal" in {
      ContextKey.BudgetWarning.json shouldBe legacyKey
      legacyKey.as[ContextKey] shouldBe ContextKey.BudgetWarning
    }

    "round-trip a keyed map through the legacy encoding" in {
      keys.json shouldBe legacyMap
      legacyMap.as[Map[ContextKey, String]] shouldBe keys
    }

    "round-trip inside an enclosing record" in {
      val turn = TurnInput(conversationId = Conversation.id("context-key-wire"), extraContext = keys)
      val json = turn.json
      json("extraContext") shouldBe legacyMap
      json.as[TurnInput].extraContext shouldBe keys
    }
  }

  "The two key namespaces" should {
    "stamp the framework prefix exactly once" in {
      ContextKey.internal("budgetWarning") shouldBe ContextKey.BudgetWarning
      ContextKey.internal("_budgetWarning") shouldBe ContextKey.BudgetWarning
      ContextKey.BudgetWarning.value shouldBe "_budgetWarning"
      ContextKey.ContextPressure.value shouldBe "_contextPressure"
      ContextKey.ParaphraseObservation.value shouldBe "_paraphraseObservation"
    }

    "refuse to let an app claim a framework key" in {
      an[IllegalArgumentException] should be thrownBy ContextKey("_budgetWarning")
      ContextKey("focus").value shouldBe "focus"
    }
  }
}
