package sigil.conversation

import fabric.define.{DefType, Definition}
import fabric.rw.*
import fabric.{obj, str}

/**
 * Typed key for entries in [[TurnInput.extraContext]] and
 * [[ParticipantProjection.extraContext]].
 *
 * Two namespaces share the map. Framework keys are `_`-prefixed and are
 * control-plane signals the agent reads as context but neither the app
 * nor the model authors — every one of them is a constant below, so a
 * consumer matches on the constant instead of retyping the string. App
 * keys carry no prefix and are whatever the app wants; apps define their
 * own constants for the same reason, and because re-inserting with the
 * same key replaces the prior value rather than accumulating duplicates.
 *
 * Opaque over `String`: the wrapper costs nothing at runtime, and the
 * only ways to build one are [[apply]] and [[internal]], so a bare
 * string can never be mistaken for a key.
 */
opaque type ContextKey = String

object ContextKey {

  /** An app-defined key. The `_` prefix marks framework control-plane
    * keys and belongs to [[internal]]; accepting it here would let an
    * app impersonate a control-plane signal. */
  def apply(value: String): ContextKey = {
    require(!value.startsWith("_"),
      s"ContextKey(\"$value\") is reserved: the `_` prefix marks framework control-plane keys. " +
        "Use ContextKey.internal from framework code, or pick an unprefixed name.")
    value
  }

  /** A framework control-plane key. `name` is given without the `_`;
    * the prefix is stamped here so the convention has one owner. */
  def internal(name: String): ContextKey = "_" + name.stripPrefix("_")

  /** This turn's resolved pinned directives occupy an outsized share of
    * the model's window. The agent decides what to do about it. */
  val BudgetWarning: ContextKey = internal("budgetWarning")

  /** This turn's context was elided under budget pressure — reads were
    * rewritten to stubs, so the agent can only narrate. Guards that
    * would challenge a narration back off when this is present. */
  val ContextPressure: ContextKey = internal("contextPressure")

  /** The agent restated itself instead of acting; the observation is fed
    * back so the next turn breaks the loop. */
  val ParaphraseObservation: ContextKey = internal("paraphraseObservation")

  extension (key: ContextKey) {
    /** The key's wire string, prefix included. */
    def value: String = key
  }

  /** Serialized as `{"value": "..."}` — the shape the original case
    * class produced, and therefore the shape already on disk in every
    * persisted `extraContext`. The object (rather than bare-string)
    * definition also keeps `Map[ContextKey, String]` on fabric's
    * array-of-pairs encoding; a string definition would silently rewrite
    * those maps to JSON objects. */
  given rw: RW[ContextKey] = RW.from(
    r = key => obj("value" -> str(key)),
    w = json => json("value").asString,
    d = Definition(DefType.Obj("value" -> Definition(DefType.Str)), Some("sigil.conversation.ContextKey"))
  )
}
