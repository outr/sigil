package spec

import fabric.*
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.{InputNormalizer, ToolInput}

/**
 * Coverage for sigil bug #58 — `Option[String]` tool-call args
 * coerce empty string `""` to `None` so tool authors' natural
 * `input.field.orElse(default)` idiom works regardless of how the
 * model encodes "no value" in its grammar-constrained output.
 *
 * Three properties verified here:
 *   1. `Option[String]` field carrying `""` becomes `None` after
 *      normalisation → `inputRW.write` materialises `None`.
 *   2. **Required** `String` field carrying `""` is preserved —
 *      the framework doesn't second-guess a deliberately-empty
 *      required string.
 *   3. The coercion only applies to `Opt(Str)`. Other `Option`
 *      wrappers (e.g. `Option[Int]`) and non-empty strings pass
 *      through unchanged.
 */
class InputNormalizerSpec extends AnyWordSpec with Matchers {

  // Tool input shape mirroring Sage's `LoadClaudeStateTool` repro.
  case class LoadInput(includeClaudeMd: Boolean,
                       includeMemoryFiles: Boolean,
                       sessionId: Option[String] = None) extends ToolInput derives RW

  // Required-string variant — sessionId is mandatory; "" must
  // pass through.
  case class RequiredInput(name: String) extends ToolInput derives RW

  // Mixed shape — Option[Int] / Option[String] coexisting.
  case class MixedInput(label: String,
                        retries: Option[Int] = None,
                        note: Option[String] = None) extends ToolInput derives RW

  // Nested Option[String] inside an Option[Obj].
  case class InnerInput(comment: Option[String] = None) derives RW
  case class NestedInput(inner: Option[InnerInput] = None) extends ToolInput derives RW

  "InputNormalizer" should {

    "coerce empty-string Option[String] to Null (so RW materialises None)" in {
      val raw = obj(
        "includeClaudeMd"    -> bool(true),
        "includeMemoryFiles" -> bool(true),
        "sessionId"          -> str("")
      )
      val normalised = InputNormalizer.normalize(raw, summon[RW[LoadInput]].definition)
      val typed = summon[RW[LoadInput]].write(normalised)
      typed.sessionId shouldBe None
    }

    "preserve non-empty Option[String]" in {
      val raw = obj(
        "includeClaudeMd"    -> bool(true),
        "includeMemoryFiles" -> bool(true),
        "sessionId"          -> str("abc-123")
      )
      val normalised = InputNormalizer.normalize(raw, summon[RW[LoadInput]].definition)
      val typed = summon[RW[LoadInput]].write(normalised)
      typed.sessionId shouldBe Some("abc-123")
    }

    "preserve `\"\"` for required (non-Optional) String fields" in {
      val raw = obj("name" -> str(""))
      val normalised = InputNormalizer.normalize(raw, summon[RW[RequiredInput]].definition)
      val typed = summon[RW[RequiredInput]].write(normalised)
      typed.name shouldBe ""
    }

    "leave Option[Int] alone (only the Opt(Str) shape coerces)" in {
      val raw = obj(
        "label"   -> str("x"),
        "retries" -> num(0),  // valid Some(0) for Option[Int]
        "note"    -> str("")
      )
      val normalised = InputNormalizer.normalize(raw, summon[RW[MixedInput]].definition)
      val typed = summon[RW[MixedInput]].write(normalised)
      typed.retries shouldBe Some(0)
      typed.note shouldBe None
    }

    "recurse into nested optional objects" in {
      val raw = obj(
        "inner" -> obj("comment" -> str(""))
      )
      val normalised = InputNormalizer.normalize(raw, summon[RW[NestedInput]].definition)
      val typed = summon[RW[NestedInput]].write(normalised)
      typed.inner.flatMap(_.comment) shouldBe None
    }

    "be a no-op on JSON whose shape doesn't match the definition (e.g. extra fields)" in {
      val raw = obj(
        "includeClaudeMd"    -> bool(true),
        "includeMemoryFiles" -> bool(true),
        "sessionId"          -> str(""),
        "extraField"         -> str("ignored")
      )
      val normalised = InputNormalizer.normalize(raw, summon[RW[LoadInput]].definition)
      // sessionId still coerced; extra field still present.
      normalised.asObj.value.get("sessionId") shouldBe Some(Null)
      normalised.asObj.value.get("extraField") shouldBe Some(str("ignored"))
    }
  }

  // Sigil #272 — Haiku-style string-encoded scalars. The schema declares
  // integer / number / boolean; the model emits "168594932069" / "null" /
  // "true" as quoted strings. Coerce against the declared shape before
  // fabric's RW rejects the type mismatch.
  case class ScalarsInput(themeId: Option[Long]      = None,
                          score:   Option[Double]    = None,
                          enabled: Option[Boolean]   = None,
                          page:    Long              = 0L,
                          ratio:   Double            = 0.0,
                          active:  Boolean           = false,
                          name:    String            = "") extends ToolInput derives RW

  "InputNormalizer (sigil #272)" should {

    "coerce string-encoded `\"null\"` to Null for nullable integer fields" in {
      val raw = obj("themeId" -> str("null"))
      val normalised = InputNormalizer.normalize(raw, summon[RW[ScalarsInput]].definition)
      val typed = summon[RW[ScalarsInput]].write(normalised)
      typed.themeId shouldBe None
    }

    "coerce string-encoded integer literals to NumInt for integer fields" in {
      val raw = obj("themeId" -> str("168594932069"), "page" -> str("42"))
      val normalised = InputNormalizer.normalize(raw, summon[RW[ScalarsInput]].definition)
      val typed = summon[RW[ScalarsInput]].write(normalised)
      typed.themeId shouldBe Some(168594932069L)
      typed.page shouldBe 42L
    }

    "coerce string-encoded numeric literals to NumDec for number fields" in {
      val raw = obj("score" -> str("0.85"), "ratio" -> str("1.5"))
      val normalised = InputNormalizer.normalize(raw, summon[RW[ScalarsInput]].definition)
      val typed = summon[RW[ScalarsInput]].write(normalised)
      typed.score shouldBe Some(0.85)
      typed.ratio shouldBe 1.5
    }

    "coerce string-encoded booleans (case-insensitive) for boolean fields" in {
      val raw = obj("enabled" -> str("TRUE"), "active" -> str("false"))
      val normalised = InputNormalizer.normalize(raw, summon[RW[ScalarsInput]].definition)
      val typed = summon[RW[ScalarsInput]].write(normalised)
      typed.enabled shouldBe Some(true)
      typed.active shouldBe false
    }

    "leave string fields alone (don't coerce numeric-looking strings on String fields)" in {
      val raw = obj("name" -> str("12345"))
      val normalised = InputNormalizer.normalize(raw, summon[RW[ScalarsInput]].definition)
      val typed = summon[RW[ScalarsInput]].write(normalised)
      typed.name shouldBe "12345"
    }

    "leave non-matching string shapes alone so fabric can report the real error" in {
      val raw = obj("themeId" -> str("not-a-number"))
      val normalised = InputNormalizer.normalize(raw, summon[RW[ScalarsInput]].definition)
      // The bad-shape string survives so fabric's RW produces an actionable
      // error rather than coercing to a wrong value silently.
      normalised.asObj.value.get("themeId") shouldBe Some(str("not-a-number"))
    }

    "preserve already-correctly-typed scalars" in {
      val raw = obj(
        "themeId" -> num(7L),
        "score"   -> num(0.5),
        "enabled" -> bool(true),
        "page"    -> num(1L),
        "ratio"   -> num(2.0),
        "active"  -> bool(false)
      )
      val normalised = InputNormalizer.normalize(raw, summon[RW[ScalarsInput]].definition)
      val typed = summon[RW[ScalarsInput]].write(normalised)
      typed.themeId shouldBe Some(7L)
      typed.score shouldBe Some(0.5)
      typed.enabled shouldBe Some(true)
      typed.page shouldBe 1L
      typed.ratio shouldBe 2.0
      typed.active shouldBe false
    }
  }
}
