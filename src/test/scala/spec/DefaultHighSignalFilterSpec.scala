package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.compression.extract.DefaultHighSignalFilter

/**
 * Regex coverage for [[DefaultHighSignalFilter]]. Confirms the
 * canonical high-signal shapes (pronouns + factual verbs, named
 * entities, dollar amounts, numbers, lifecycle events) trigger and
 * that small-talk / short utterances do not.
 */
class DefaultHighSignalFilterSpec extends AnyWordSpec with Matchers {
  private val f = DefaultHighSignalFilter

  "DefaultHighSignalFilter" should {
    "reject messages under 50 characters" in {
      f.isHighSignal("i bought a house") should be(false)
    }

    "accept 'i bought ...' phrasing above the length threshold" in {
      f.isHighSignal("I bought a new house in Brooklyn last Tuesday for a great price.") should be(true)
    }

    "accept preference phrases" in {
      f.isHighSignal("I prefer Scala over Python for long-running servers because of the type system.") should be(true)
    }

    "accept lifecycle events" in {
      f.isHighSignal("We just got engaged last weekend after a long conversation about the future.") should be(true)
    }

    "accept phrases with dollar amounts" in {
      f.isHighSignal("I paid $12,000 for my new rig — way more than I expected when I started.") should be(true)
    }

    "accept phrases with multi-digit numbers" in {
      f.isHighSignal("My employee number is 482917 and I need to update my HR profile this afternoon.") should be(true)
    }

    "reject generic small-talk" in {
      f.isHighSignal("that sounds interesting, can you tell me more about how that works overall") should be(false)
    }

    "reject null input" in {
      f.isHighSignal(null) should be(false)
    }
  }
}
