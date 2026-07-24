package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.anthropic.AnthropicBatch
import sigil.provider.{OneShotRequest, OneShotResponse}
import sigil.tool.model.ResponseContent
import fabric.io.JsonParser

/**
 * Sigil #299 — Anthropic Message Batches wire-layer regression.
 * Covers the pure-function paths (request-entry render, result-line
 * parse) without needing a live ANTHROPIC_API_KEY.
 */
class AnthropicBatchSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def req(text: String, maxTokens: Int = 1024): OneShotRequest = OneShotRequest(
    model = TestSigil.testModel(sigil.db.Model.id("anthropic", "claude-haiku-4-5")),
    systemPrompt = "You are a classifier.",
    userPrompt = text,
    generationSettings = sigil.provider.GenerationSettings(
      outputTokenCap = sigil.provider.OutputTokenCap.Below(maxTokens)
    )
  )

  "AnthropicBatch.renderRequestEntry" should {

    "produce a Messages-API params block with custom_id correlator" in rapid.Task {
      val r = req("Classify this: dogs", maxTokens = 512)
      val json = AnthropicBatch.renderRequestEntry(r)
      json("custom_id").asString shouldBe r.requestId.value
      val params = json("params")
      params("model").asString shouldBe "claude-haiku-4-5"
      params("max_tokens").asInt shouldBe 512
      params("system").asString shouldBe "You are a classifier."
      val messages = params("messages").asVector
      messages should have size 1
      messages(0)("role").asString shouldBe "user"
      val content = messages(0)("content").asVector
      content(0)("type").asString shouldBe "text"
      content(0)("text").asString shouldBe "Classify this: dogs"
    }

    "default max_tokens when the request doesn't specify one" in rapid.Task {
      val r = OneShotRequest(
        model = TestSigil.testModel(sigil.db.Model.id("anthropic", "claude-haiku-4-5")),
        systemPrompt = "test",
        userPrompt = "hi"
        // no generationSettings → ModelMax outputTokenCap
      )
      val json = AnthropicBatch.renderRequestEntry(r)
      json("params")("max_tokens").asInt shouldBe AnthropicBatch.DefaultMaxTokens
    }

    "render ImageBytes content as base64 image source" in rapid.Task {
      val r = OneShotRequest(
        model = TestSigil.testModel(sigil.db.Model.id("anthropic", "claude-opus-4-7")),
        systemPrompt = "Describe.",
        userPrompt = "",
        userContent = Vector(
          ResponseContent.Text("What's in this image?"),
          ResponseContent.ImageBytes("image/png", "iVBORw0KGgo")
        )
      )
      val json = AnthropicBatch.renderRequestEntry(r)
      val content = json("params")("messages").asVector(0)("content").asVector
      content should have size 2
      content(0)("type").asString shouldBe "text"
      content(1)("type").asString shouldBe "image"
      val src = content(1)("source")
      src("type").asString shouldBe "base64"
      src("media_type").asString shouldBe "image/png"
      src("data").asString shouldBe "iVBORw0KGgo"
    }

    "omit `system` when the systemPrompt is empty" in rapid.Task {
      val r = OneShotRequest(
        model = TestSigil.testModel(sigil.db.Model.id("anthropic", "claude-haiku-4-5")),
        systemPrompt = "",
        userPrompt = "no system"
      )
      val json = AnthropicBatch.renderRequestEntry(r)
      json("params").get("system") shouldBe None
    }
  }

  "AnthropicBatch.parseResultLine" should {

    "decode a succeeded result with content + usage" in rapid.Task {
      val customId = "ab-success-001"
      val line = """{"custom_id":"""" + customId +
        """","result":{"type":"succeeded","message":{"content":[{"type":"text","text":"foo bar"}],"usage":{"input_tokens":12,"output_tokens":3}}}}"""
      val parsed = AnthropicBatch.parseResultLine(line)
      parsed shouldBe defined
      val r = parsed.get
      r.requestId.value shouldBe customId
      r.content.collectFirst { case t: ResponseContent.Text => t.text } shouldBe Some("foo bar")
      r.usage.map(_.promptTokens) shouldBe Some(12)
      r.usage.map(_.completionTokens) shouldBe Some(3)
      r.usage.map(_.totalTokens) shouldBe Some(15)
      r.error shouldBe None
    }

    "decode an errored result" in rapid.Task {
      val customId = "ab-error-002"
      val line = """{"custom_id":"""" + customId +
        """","result":{"type":"errored","error":{"type":"invalid_request_error","message":"prompt too long"}}}"""
      val parsed = AnthropicBatch.parseResultLine(line)
      parsed shouldBe defined
      val r = parsed.get
      r.error shouldBe defined
      r.error.get.message shouldBe "prompt too long"
      r.error.get.code shouldBe Some("invalid_request_error")
    }

    "surface expired results as recoverable errors" in rapid.Task {
      val customId = "ab-expired-003"
      val line = """{"custom_id":"""" + customId + """","result":{"type":"expired"}}"""
      val parsed = AnthropicBatch.parseResultLine(line)
      parsed.get.error.get.code shouldBe Some("expired")
      parsed.get.error.get.recoverable shouldBe true
    }

    "skip blank / malformed lines" in rapid.Task {
      AnthropicBatch.parseResultLine("") shouldBe None
      AnthropicBatch.parseResultLine("{not json") shouldBe None
    }
  }

  "AnthropicProvider.batchSupported" should {
    "be true (native batch override is wired)" in rapid.Task {
      val provider = sigil.provider.anthropic.AnthropicProvider(
        apiKey = "test-key",
        sigilRef = TestSigil
      )
      provider.batchSupported shouldBe true
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
