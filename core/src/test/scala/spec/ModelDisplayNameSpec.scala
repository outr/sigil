package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Message
import sigil.participant.ParticipantId
import sigil.provider.llamacpp.LlamaCpp

import fabric.io.JsonFormatter
import fabric.rw.*

/**
 * Coverage for the friendly `displayName` field on [[Model]] and its
 * convenience mirror on [[Message.modelDisplayName]].
 *
 *   - LlamaCpp's catalog parser derives a sensible label from the
 *     gguf basename (strips quantization suffix, dedupes vendor
 *     prefix, title-cases).
 *   - Model round-trips through fabric's RW with the field populated.
 *   - Message round-trips through fabric's RW with
 *     modelDisplayName populated.
 *
 * OpenRouter integration is verified indirectly — the merge step in
 * [[sigil.controller.OpenRouter.loadModels]] is exercised by the
 * existing OpenRouter live tests when network is available.
 */
class ModelDisplayNameSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  "LlamaCpp.displayNameFromBasename" should {

    "strip the iq quantization suffix" in rapid.Task {
      LlamaCpp.displayNameFromBasename("qwen_qwen3.6-35b-a3b-iq4_xs") shouldBe Some("Qwen3.6-35b-a3b")
    }

    "strip the q4_k_m quantization suffix" in rapid.Task {
      LlamaCpp.displayNameFromBasename("llama-3.1-8b-instruct-q4_k_m") shouldBe Some("Llama-3.1-8b-instruct")
    }

    "strip f16 / bf16 / f32 suffixes" in rapid.Task {
      LlamaCpp.displayNameFromBasename("phi-3-mini-4k-f16")  shouldBe Some("Phi-3-mini-4k")
      LlamaCpp.displayNameFromBasename("phi-3-mini-4k-bf16") shouldBe Some("Phi-3-mini-4k")
      LlamaCpp.displayNameFromBasename("phi-3-mini-4k-f32")  shouldBe Some("Phi-3-mini-4k")
    }

    "dedupe vendor_vendor prefix" in rapid.Task {
      LlamaCpp.displayNameFromBasename("qwen_qwen3.5-9b") shouldBe Some("Qwen3.5-9b")
    }

    "leave names without quant suffix or vendor dupe almost untouched (just title-case)" in rapid.Task {
      LlamaCpp.displayNameFromBasename("mixtral-8x22b") shouldBe Some("Mixtral-8x22b")
    }

    "return None on empty input" in rapid.Task {
      LlamaCpp.displayNameFromBasename("") shouldBe None
    }
  }

  "Model" should {

    "round-trip displayName through fabric RW" in rapid.Task {
      val m = stubModel(provider = "openai", model = "gpt-5.5", display = Some("GPT-5.5"))
      val json = m.json
      val back = json.as[Model]
      back.displayName shouldBe Some("GPT-5.5")
      back._id        shouldBe m._id
    }

    "default displayName to None when not provided" in rapid.Task {
      val m = stubModel(provider = "openai", model = "gpt-3.5-turbo", display = None)
      m.displayName shouldBe None
    }
  }

  "Message.modelDisplayName" should {

    "round-trip through fabric RW" in rapid.Task {
      val convId  = sigil.conversation.Conversation.id("display-spec")
      val topicId = sigil.conversation.Topic.id("t1")
      val msg = Message(
        participantId    = SpecParticipant,
        conversationId   = convId,
        topicId          = topicId,
        modelId          = Some(Model.id("openai", "gpt-5.5")),
        modelDisplayName = Some("GPT-5.5")
      )
      val json = msg.json
      val back = json.as[Message]
      back.modelId          shouldBe Some(Model.id("openai", "gpt-5.5"))
      back.modelDisplayName shouldBe Some("GPT-5.5")
    }

    "default to None when not stamped" in rapid.Task {
      val convId  = sigil.conversation.Conversation.id("display-default")
      val topicId = sigil.conversation.Topic.id("t1")
      val msg = Message(
        participantId  = SpecParticipant,
        conversationId = convId,
        topicId        = topicId
      )
      msg.modelDisplayName shouldBe None
    }
  }

  private def stubModel(provider: String, model: String, display: Option[String]): Model = Model(
    canonicalSlug        = s"$provider/$model",
    huggingFaceId        = "",
    name                 = model,
    displayName          = display,
    description          = "",
    contextLength        = 32_000,
    architecture         = ModelArchitecture("text->text", List("text"), List("text"), "Unknown", None),
    pricing              = ModelPricing(BigDecimal(0), BigDecimal(0), None, None),
    topProvider          = ModelTopProvider(Some(32_000L), Some(8_192L), false),
    perRequestLimits     = None,
    supportedParameters  = Set("temperature"),
    defaultParameters    = ModelDefaultParameters(),
    knowledgeCutoff      = None,
    expirationDate       = None,
    links                = ModelLinks(""),
    created              = Timestamp(),
    modified             = Timestamp(),
    _id                  = Model.id(provider, model)
  )

  private case object SpecParticipant extends ParticipantId {
    val value: String = "display-spec-user"
  }
  ParticipantId.register(RW.static(SpecParticipant))
}
